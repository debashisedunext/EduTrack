package com.edunext.edutrack.domain.onboarding.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.OptionalLong;

/**
 * B-110 · the write half of the onboarding outbox.
 *
 * <p>Lives in {@code domain} for the reason {@code OutboxEnqueuer} (D-010)
 * does: {@code api} enqueues from inside business transactions — a sign-off
 * request (B-115), a client escalation (A-128), a scanner event (D-102) — and
 * {@code worker} drains, and neither module depends on the other. Stream B
 * owns this file; it is a domain service, not part of any entity model, and
 * speaks JDBC so it cannot collide with the aggregates B-102 and C-101 bring.
 *
 * <p><strong>The propagation is the feature.</strong> {@code REQUIRED} joins
 * the caller's transaction, so the queue row commits if and only if the
 * business change does. A sign-off that rolls back cannot leave a phantom
 * "please sign" mail queued, and a sign-off that commits cannot lose its mail
 * to a broker that was briefly unreachable. Calling this from a
 * {@code REQUIRES_NEW} context, or after the caller has committed, gives up the
 * reason the outbox exists.
 *
 * <h2>Dedupe is the database's, not this class's</h2>
 *
 * <p>A-107's {@code uq_ob_outbox_queued} is unique over the generated
 * {@code queued_dedupe_key}, which is {@code dedupe_key} while the row is
 * PENDING or SENDING and NULL once it has left. So a second enqueue of an
 * event that is still in the queue is a duplicate-key error, and that is the
 * signal — not a {@code SELECT} first, which two overlapping scanner passes
 * would both pass. The error is caught and reported as "not queued"; on MySQL
 * a failed statement does not poison the enclosing transaction, so the
 * caller's business write is untouched. <strong>Do not switch this to
 * {@code INSERT IGNORE}</strong>: that also swallows foreign-key failures,
 * which would turn a row pointing at a deleted step into silence.
 *
 * <p>{@code @Lazy} for the reason every JDBC bean in {@code domain} is:
 * {@code api}'s {@code ApplicationSmokeTest} starts a context with no
 * datasource, and nothing enqueues during startup.
 */
@Component
@Lazy
public class ObOutboxEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(ObOutboxEnqueuer.class);

    private static final String INSERT = """
            INSERT INTO ob_notification_outbox
                   (event_key, channel, recipient_type, recipient_user_id, recipient_contact_id,
                    ob_client_id, journey_id, step_id, payload,
                    status, attempts, next_attempt_at, dedupe_key)
            VALUES (:eventKey, :channel, :recipientType, :recipientUserId, :recipientContactId,
                    :obClientId, :journeyId, :stepId, CAST(:payload AS JSON),
                    'PENDING', 0, :now, :dedupeKey)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ObOutboxEnqueuer(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Queue one notification, in the caller's transaction.
     *
     * <p>{@code next_attempt_at} is now, so the next dispatcher poll picks it
     * up. Delaying a send is a matter of that column, not of holding the event
     * in memory.
     *
     * @return the {@code ob_notification_outbox} id, or empty when the same
     *         event (by {@code dedupeKey}) is already waiting in the queue
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OptionalLong enqueue(ObNotification notification) {
        Long userId = null;
        Long contactId = null;
        switch (notification.recipient()) {
            case ObRecipient.Staff s -> userId = s.userId();
            case ObRecipient.Client c -> contactId = c.contactId();
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventKey", notification.eventKey())
                .addValue("channel", notification.channel().name())
                .addValue("recipientType", notification.recipient().type())
                .addValue("recipientUserId", userId)
                .addValue("recipientContactId", contactId)
                .addValue("obClientId", notification.obClientId())
                .addValue("journeyId", notification.journeyId())
                .addValue("stepId", notification.stepId())
                // A payload the caller built from its own values and that
                // cannot be serialised is a programming error at the call
                // site; failing the business transaction is the honest response.
                .addValue("payload", ObOutboxJson.write(notification.payload()))
                .addValue("now", Timestamp.from(clock.instant()))
                .addValue("dedupeKey", notification.dedupeKey());

        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(INSERT, params, keys);
        } catch (DuplicateKeyException alreadyQueued) {
            log.debug("ob-outbox: {} already queued for {} — not queued again",
                    notification.eventKey(), notification.dedupeKey());
            return OptionalLong.empty();
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("ob_notification_outbox insert returned no generated key");
        }
        return OptionalLong.of(id.longValue());
    }
}
