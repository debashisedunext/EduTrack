package com.edunext.edutrack.worker.onboarding.outbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * B-112 · writes one row into {@code ob_notifications}.
 *
 * <h2>In {@code worker}, not in {@code domain}</h2>
 *
 * <p>{@link com.edunext.edutrack.domain.notifications.NotificationWriter} and
 * {@link com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer} both
 * live in {@code domain} for one stated reason: {@code api} and {@code worker}
 * each write them and neither module depends on the other. That is not true
 * here. Exactly one thing writes a bell entry — {@link InAppChannelAdapter},
 * draining the queue — and {@code api} only reads. Putting it in {@code domain}
 * would advertise a second write path that does not exist, and the first caller
 * to take up the invitation would be one that bypasses the queue's dedupe,
 * ordering and retry.
 *
 * <p>Anything wanting a bell entry enqueues an {@code IN_APP} row instead. That
 * is the whole point of the outbox: the notification commits with the business
 * change or not at all.
 *
 * <h2>The duplicate is expected, and is the delivery guarantee working</h2>
 *
 * <p>{@code uq_ob_notifications_outbox} makes exactly-once the database's
 * answer. B-110's dispatcher reclaims a lapsed lease and re-delivers, so this
 * runs twice for one queue row whenever a send outlives its lease — on EMAIL
 * that costs a duplicate mail, and here it would cost a duplicate bell entry
 * the reader cannot tell from two real events. So a duplicate key is reported
 * as "already written", not as a failure: the row it collided with <em>is</em>
 * the delivery.
 *
 * <p>Deliberately not {@code INSERT IGNORE}, on {@code ObOutboxEnqueuer}'s
 * reason — that also swallows foreign-key failures, turning an entry pointing
 * at a deleted journey into silence.
 */
@Component
class ObInAppNotificationWriter {

    private static final String INSERT = """
            INSERT INTO ob_notifications
                   (recipient_user_id, event_key, category, title, body, link_url,
                    ob_client_id, journey_id, step_id, outbox_id)
            VALUES (:userId, :eventKey, :category, :title, :body, :linkUrl,
                    :obClientId, :journeyId, :stepId, :outboxId)
            """;

    private static final String FIND_BY_OUTBOX = """
            SELECT id FROM ob_notifications WHERE outbox_id = :outboxId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ObInAppNotificationWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the id written, or empty when this queue row already has an entry
     */
    OptionalLong write(long recipientUserId, ObOutboxMessage message, ObInAppContent content) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", recipientUserId)
                .addValue("eventKey", message.eventKey())
                .addValue("category", content.category().name())
                .addValue("title", content.title())
                .addValue("body", content.body())
                .addValue("linkUrl", content.linkUrl())
                .addValue("obClientId", message.obClientId())
                .addValue("journeyId", message.journeyId())
                .addValue("stepId", message.stepId())
                .addValue("outboxId", message.id());

        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(INSERT, params, keys);
        } catch (DuplicateKeyException alreadyWritten) {
            return OptionalLong.empty();
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("ob_notifications insert returned no generated key");
        }
        return OptionalLong.of(id.longValue());
    }

    /** The entry a duplicate collided with, so the log and the send record name it. */
    OptionalLong findByOutboxId(long outboxId) {
        return jdbc.queryForStream(FIND_BY_OUTBOX,
                        new MapSqlParameterSource("outboxId", outboxId),
                        (rs, rowNum) -> rs.getLong("id"))
                .mapToLong(Long::longValue)
                .findFirst();
    }
}
