package com.edunext.edutrack.worker.outbox;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Claiming and stamping {@code email_log} rows. {@code email_log} <em>is</em>
 * the queue (PLAN.md §2.2) — there is no second queue to keep in step with it.
 *
 * <p>Raw JDBC rather than JPA, for three reasons: {@code FOR UPDATE SKIP
 * LOCKED} has no portable JPA expression; the entity model (B-005) does not
 * exist yet and the worker should not wait on it; and claiming wants to touch
 * exactly the columns below, not load and dirty-check a whole aggregate.
 */
@Repository
public class OutboxRepository {

    /**
     * Claim by <em>leasing</em>: push {@code next_attempt_at} beyond the claim
     * window so other workers stop seeing the row, rather than moving it to a
     * SENDING status.
     *
     * <p>The blueprint fixes {@code status} at QUEUED|SENT|BOUNCED|FAILED, and
     * adding a fifth value would put a transient runtime detail into a column
     * that reports on delivery. Leasing also self-heals: a worker killed
     * mid-send leaves the row QUEUED, and it becomes claimable again when the
     * lease lapses. No reaper, no stuck rows.
     *
     * <p>The cost is at-least-once delivery — a crash after the SMTP handshake
     * but before the stamp re-sends on lease expiry. At-most-once would mean
     * stamping SENT before sending and silently losing mail on a crash, which
     * is the worse failure for a system whose §17 goal is that a missed alert
     * is provable. D-035's per-recipient rate limit blunts the duplicate.
     */
    private static final String CLAIM = """
            SELECT id, ticket_id, event_code, template_id, to_user_id,
                   to_email, subject, retry_count
              FROM email_log
             WHERE status = 'QUEUED'
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at, id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """;

    private static final String LEASE = """
            UPDATE email_log
               SET next_attempt_at = :leaseUntil
             WHERE id IN (:ids)
            """;

    private static final String MARK_SENT = """
            UPDATE email_log
               SET status = 'SENT',
                   sent_at = :now,
                   provider_msg_id = :providerMsgId,
                   retry_count = :retryCount,
                   error_text = NULL
             WHERE id = :id
            """;

    private static final String MARK_FOR_RETRY = """
            UPDATE email_log
               SET status = 'QUEUED',
                   retry_count = :retryCount,
                   next_attempt_at = :nextAttemptAt,
                   error_text = :errorText
             WHERE id = :id
            """;

    private static final String MARK_FAILED = """
            UPDATE email_log
               SET status = 'FAILED',
                   retry_count = :retryCount,
                   error_text = :errorText
             WHERE id = :id
            """;

    private static final String QUEUE_DEPTH = """
            SELECT COUNT(*) FROM email_log
             WHERE status = 'QUEUED' AND next_attempt_at <= :now
            """;

    private static final RowMapper<OutboxMessage> MAPPER = (rs, rowNum) -> new OutboxMessage(
            rs.getLong("id"),
            (Long) rs.getObject("ticket_id"),
            rs.getString("event_code"),
            (Long) rs.getObject("template_id"),
            (Long) rs.getObject("to_user_id"),
            rs.getString("to_email"),
            rs.getString("subject"),
            rs.getInt("retry_count"));

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claim up to {@code batchSize} due messages and lease them.
     *
     * <p>{@code REQUIRES_NEW} so the claim commits on its own. The row locks
     * taken by {@code FOR UPDATE} are held until commit, so claiming inside a
     * transaction that also spans the sends would keep every claimed row
     * locked for the length of the slowest SMTP round-trip and serialise the
     * workers it is meant to parallelise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxMessage> claimBatch(int batchSize, Duration lease, Instant now) {
        List<OutboxMessage> claimed = jdbc.query(CLAIM,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("batchSize", batchSize),
                MAPPER);

        if (claimed.isEmpty()) {
            return List.of();
        }

        jdbc.update(LEASE, new MapSqlParameterSource()
                .addValue("leaseUntil", Timestamp.from(now.plus(lease)))
                .addValue("ids", claimed.stream().map(OutboxMessage::id).toList()));

        return claimed;
    }

    public void markSent(long id, String providerMsgId, int retryCount, Instant sentAt) {
        jdbc.update(MARK_SENT, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", Timestamp.from(sentAt))
                .addValue("providerMsgId", providerMsgId)
                .addValue("retryCount", retryCount));
    }

    public void markForRetry(long id, int retryCount, Instant nextAttemptAt, String errorText) {
        jdbc.update(MARK_FOR_RETRY, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("retryCount", retryCount)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("errorText", truncate(errorText)));
    }

    public void markFailed(long id, int retryCount, String errorText) {
        jdbc.update(MARK_FAILED, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("retryCount", retryCount)
                .addValue("errorText", truncate(errorText)));
    }

    /** Backlog of messages due now — the depth PLAN.md §7 wants alerting on. */
    public int dueCount(Instant now) {
        Integer count = jdbc.queryForObject(QUEUE_DEPTH,
                Map.of("now", Timestamp.from(now)), Integer.class);
        return count == null ? 0 : count;
    }

    /** {@code error_text} is TEXT; keep a runaway stack trace from filling it. */
    private static String truncate(String errorText) {
        if (errorText == null) {
            return null;
        }
        return errorText.length() <= 2000 ? errorText : errorText.substring(0, 2000);
    }
}
