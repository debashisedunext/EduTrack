package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxJson;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * B-110 · claiming and stamping {@code ob_notification_outbox} rows.
 *
 * <p>Raw JDBC, as D-010's {@code OutboxRepository} is: {@code FOR UPDATE SKIP
 * LOCKED} has no JPA expression, and claiming wants to touch exactly the
 * columns below rather than load and dirty-check an aggregate.
 *
 * <h2>SENDING is the lease, and {@code next_attempt_at} is its deadline</h2>
 *
 * <p>A-107 gave this table a {@code SENDING} status — the ticketing
 * {@code email_log} has none, and D-010 leases by pushing {@code next_attempt_at}
 * forward while leaving the row QUEUED. Here the status moves, because the
 * generated {@code queued_dedupe_key} is defined over {@code PENDING} and
 * {@code SENDING}: a row in flight still holds its dedupe slot, so a scanner
 * pass overlapping a send cannot queue the same event a second time.
 *
 * <p>The deadline still lives in {@code next_attempt_at}. A worker killed
 * mid-send leaves the row SENDING; {@link #reclaimExpiredLeases} puts it back
 * to PENDING once that deadline passes, and the next poll retries it. No
 * reaper process, no stuck rows — and, as with D-010, at-least-once delivery:
 * a crash after the provider accepted but before the stamp re-sends on lease
 * expiry. At-most-once would mean stamping SENT before sending and silently
 * losing a sign-off request on a crash, which is the worse failure.
 *
 * <p>Every stamp is guarded with {@code AND status = 'SENDING'}. A late stamp
 * from a worker whose lease had already expired and been reclaimed cannot
 * then overwrite what the reclaiming worker recorded.
 */
@Repository
public class ObOutboxRepository {

    private static final Logger log = LoggerFactory.getLogger(ObOutboxRepository.class);

    /** {@code last_error VARCHAR(1000)}. */
    private static final int LAST_ERROR_MAX = 1000;

    private static final String RECLAIM = """
            UPDATE ob_notification_outbox
               SET status = 'PENDING',
                   next_attempt_at = :now
             WHERE status = 'SENDING'
               AND next_attempt_at <= :now
            """;

    /**
     * Ids only, so the row lock covers this table and nothing it joins to.
     * Served by {@code ix_ob_outbox_due (status, next_attempt_at, id)}.
     */
    private static final String CLAIM = """
            SELECT id
              FROM ob_notification_outbox
             WHERE status = 'PENDING'
               AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
               AND channel IN (:channels)
             ORDER BY next_attempt_at, id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """;

    private static final String LEASE = """
            UPDATE ob_notification_outbox
               SET status = 'SENDING',
                   next_attempt_at = :leaseUntil
             WHERE id IN (:ids)
            """;

    /**
     * The recipient is resolved here, at claim, so the address is the one on
     * file at send time. {@code is_active} on either side is what lets the
     * dispatcher refuse to write to somebody who has been deactivated since
     * the event was queued.
     */
    private static final String LOAD = """
            SELECT o.id, o.event_key, o.channel, o.recipient_type,
                   o.recipient_user_id, o.recipient_contact_id,
                   o.ob_client_id, o.journey_id, o.step_id, o.payload, o.attempts,
                   COALESCE(u.full_name, c.name)      AS recipient_name,
                   COALESCE(u.email, c.email)         AS recipient_email,
                   COALESCE(u.mobile, c.phone)        AS recipient_phone,
                   COALESCE(c.whatsapp_opt_in, 0)     AS whatsapp_opt_in,
                   COALESCE(u.is_active, c.is_active, 0) AS recipient_active
              FROM ob_notification_outbox o
              LEFT JOIN users u              ON u.id = o.recipient_user_id
              LEFT JOIN ob_client_contacts c ON c.id = o.recipient_contact_id
             WHERE o.id IN (:ids)
             ORDER BY o.id
            """;

    private static final String MARK_SENT = """
            UPDATE ob_notification_outbox
               SET status = 'SENT',
                   sent_at = :now,
                   provider_message_id = :providerMessageId,
                   attempts = :attempts,
                   next_attempt_at = NULL,
                   last_error = NULL
             WHERE id = :id
               AND status = 'SENDING'
            """;

    private static final String MARK_FOR_RETRY = """
            UPDATE ob_notification_outbox
               SET status = 'PENDING',
                   attempts = :attempts,
                   next_attempt_at = :nextAttemptAt,
                   last_error = :lastError
             WHERE id = :id
               AND status = 'SENDING'
            """;

    private static final String MARK_FAILED = """
            UPDATE ob_notification_outbox
               SET status = 'FAILED',
                   failed_at = :now,
                   attempts = :attempts,
                   next_attempt_at = NULL,
                   last_error = :lastError
             WHERE id = :id
               AND status = 'SENDING'
            """;

    private static final String DUE_COUNT = """
            SELECT COUNT(*) FROM ob_notification_outbox
             WHERE status = 'PENDING'
               AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            """;

    private static final String UNROUTABLE_BY_CHANNEL = """
            SELECT channel, COUNT(*) AS n
              FROM ob_notification_outbox
             WHERE status = 'PENDING'
               AND channel NOT IN (:channels)
             GROUP BY channel
            """;

    private static final String PENDING_BY_CHANNEL = """
            SELECT channel, COUNT(*) AS n
              FROM ob_notification_outbox
             WHERE status = 'PENDING'
             GROUP BY channel
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<ObOutboxMessage> mapper;

    public ObOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = this::map;
    }

    /**
     * Put rows whose lease has lapsed back into the queue.
     *
     * @return how many were reclaimed — worth a warning in the log, because a
     *         reclaimed row means a worker died mid-send or a send outran the
     *         configured lease
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimExpiredLeases(Instant now) {
        return jdbc.update(RECLAIM, new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    /**
     * Claim up to {@code batchSize} due rows on the given channels and mark
     * them SENDING until {@code now + lease}.
     *
     * <p>{@code REQUIRES_NEW} so the claim commits on its own. The row locks
     * are held until commit, so claiming inside a transaction that also spans
     * the sends would hold every claimed row locked for the slowest round-trip
     * and serialise the workers this is meant to parallelise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ObOutboxMessage> claimBatch(Collection<ObChannel> channels, int batchSize,
                                            Duration lease, Instant now) {
        if (channels.isEmpty()) {
            return List.of();
        }
        List<Long> ids = jdbc.queryForList(CLAIM,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("channels", channels.stream().map(Enum::name).toList())
                        .addValue("batchSize", batchSize),
                Long.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.update(LEASE, new MapSqlParameterSource()
                .addValue("leaseUntil", Timestamp.from(now.plus(lease)))
                .addValue("ids", ids));
        return jdbc.query(LOAD, new MapSqlParameterSource("ids", ids), mapper);
    }

    public void markSent(long id, String providerMessageId, int attempts, Instant sentAt) {
        jdbc.update(MARK_SENT, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", Timestamp.from(sentAt))
                .addValue("providerMessageId", providerMessageId)
                .addValue("attempts", attempts));
    }

    public void markForRetry(long id, int attempts, Instant nextAttemptAt, String lastError) {
        jdbc.update(MARK_FOR_RETRY, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("attempts", attempts)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("lastError", truncate(lastError)));
    }

    public void markFailed(long id, int attempts, String lastError, Instant failedAt) {
        jdbc.update(MARK_FAILED, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", Timestamp.from(failedAt))
                .addValue("attempts", attempts)
                .addValue("lastError", truncate(lastError)));
    }

    /** Backlog due now, on any channel — the depth worth alerting on. */
    public int dueCount(Instant now) {
        Integer count = jdbc.queryForObject(DUE_COUNT,
                new MapSqlParameterSource("now", Timestamp.from(now)), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * PENDING rows on channels this deployment has no adapter for. They are
     * not lost — they wait — but a queue silently filling with WhatsApp rows
     * nobody can send is worth a line in the log.
     */
    public Map<String, Integer> unroutableByChannel(Collection<ObChannel> supported) {
        String sql = supported.isEmpty() ? PENDING_BY_CHANNEL : UNROUTABLE_BY_CHANNEL;
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!supported.isEmpty()) {
            params.addValue("channels", supported.stream().map(Enum::name).toList());
        }
        return jdbc.query(sql, params, rs -> {
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("channel"), rs.getInt("n"));
            }
            return counts;
        });
    }

    private ObOutboxMessage map(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        String rawChannel = rs.getString("channel");
        ObChannel channel = ObChannel.of(rawChannel).orElseThrow(() ->
                new IllegalStateException("ob_notification_outbox row " + id
                        + " carries an unknown channel " + rawChannel));

        Long userId = (Long) rs.getObject("recipient_user_id");
        Long contactId = (Long) rs.getObject("recipient_contact_id");
        ObRecipient recipient = userId != null
                ? new ObRecipient.Staff(userId)
                : new ObRecipient.Client(contactId);

        ObOutboxMessage.RecipientDetails details = new ObOutboxMessage.RecipientDetails(
                rs.getString("recipient_name"),
                rs.getString("recipient_email"),
                rs.getString("recipient_phone"),
                rs.getBoolean("whatsapp_opt_in"),
                rs.getBoolean("recipient_active"));

        return new ObOutboxMessage(
                id,
                rs.getString("event_key"),
                channel,
                recipient,
                details,
                (Long) rs.getObject("ob_client_id"),
                (Long) rs.getObject("journey_id"),
                (Long) rs.getObject("step_id"),
                payloadOf(id, rs.getString("payload")),
                rs.getInt("attempts"));
    }

    private static Map<String, Object> payloadOf(long id, String raw) {
        try {
            return ObOutboxJson.read(raw);
        } catch (IllegalArgumentException e) {
            // MySQL guarantees the column holds valid JSON, so this cannot
            // happen through the schema; if it ever does, a template gets no
            // variables rather than the dispatcher stalling on one row.
            log.warn("ob-outbox: payload of row {} is unreadable; rendering with none", id, e);
            return Map.of();
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= LAST_ERROR_MAX ? error : error.substring(0, LAST_ERROR_MAX);
    }
}
