package com.edunext.edutrack.domain.outbox;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write half of the transactional outbox (D-010).
 *
 * <p>Lives in {@code domain} because both sides need it: {@code api} enqueues
 * from inside business transactions and {@code worker} drains, and neither
 * module depends on the other (PLAN.md §2.3). Stream D owns this file; it is a
 * domain service, not part of Stream B's entity model, and speaks JDBC so it
 * cannot collide with B-005.
 *
 * <p><strong>The propagation is the feature.</strong> {@code REQUIRED} joins
 * the caller's transaction, so the mail row commits if and only if the business
 * change does. A handoff that rolls back cannot leave a phantom mail queued,
 * and — the direction people forget — a handoff that commits cannot lose its
 * notification to a broker that was briefly unreachable. This is the guarantee
 * PLAN.md §2.2 says BullMQ could not have offered, so calling this from a
 * {@code REQUIRES_NEW} context, or after the transaction has committed, gives
 * up the entire reason the outbox exists.
 *
 * <p>{@code @Lazy} because this is scanned into every application including
 * contexts that deliberately run without infrastructure — {@code api}'s
 * {@code ApplicationSmokeTest} excludes {@code DataSourceAutoConfiguration} to
 * prove the context wires on a laptop with nothing installed, and an eager
 * JDBC bean would break that. Nothing enqueues during startup, so deferring
 * construction to first use costs nothing.
 */
@Component
@Lazy
public class OutboxEnqueuer {

    private static final String INSERT = """
            INSERT INTO email_log (ticket_id, event_code, template_id, to_user_id,
                                   to_email, subject, status, retry_count, next_attempt_at)
            VALUES (:ticketId, :eventCode, :templateId, :toUserId,
                    :toEmail, :subject, 'QUEUED', 0, CURRENT_TIMESTAMP(6))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxEnqueuer(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Queue one mail for delivery, in the caller's transaction.
     *
     * <p>{@code next_attempt_at} defaults to now, so the next poll picks it up.
     * Delaying a send is a matter of updating that column, not of holding the
     * message in memory.
     *
     * @return the {@code email_log} id
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public long enqueue(NewMail mail) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(INSERT,
                new MapSqlParameterSource()
                        .addValue("ticketId", mail.ticketId())
                        .addValue("eventCode", mail.eventCode())
                        .addValue("templateId", mail.templateId())
                        .addValue("toUserId", mail.toUserId())
                        .addValue("toEmail", mail.toEmail())
                        .addValue("subject", mail.subject()),
                keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("email_log insert returned no generated key");
        }
        return id.longValue();
    }
}
