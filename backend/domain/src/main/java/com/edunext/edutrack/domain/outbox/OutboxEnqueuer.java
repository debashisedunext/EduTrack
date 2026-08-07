package com.edunext.edutrack.domain.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

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

    private static final Logger log = LoggerFactory.getLogger(OutboxEnqueuer.class);

    private static final String INSERT = """
            INSERT INTO email_log (ticket_id, event_code, template_id, to_user_id,
                                   to_email, subject, status, retry_count, next_attempt_at)
            VALUES (:ticketId, :eventCode, :templateId, :toUserId,
                    :toEmail, :subject, 'QUEUED', 0, CURRENT_TIMESTAMP(6))
            """;

    /**
     * D-035. {@code <=>} is MySQL's NULL-safe equality: a non-ticket mail has
     * {@code ticket_id IS NULL}, and plain {@code =} would never match NULL to
     * NULL, so every system mail would bypass the limit entirely.
     *
     * <p>Served by {@code ix_email_log_rate (to_email, ticket_id, queued_at)},
     * which A-006 added for exactly this check.
     */
    private static final String RECENT_TO_SAME_RECIPIENT = """
            SELECT COUNT(*) FROM email_log
             WHERE to_email = :toEmail
               AND ticket_id <=> :ticketId
               AND queued_at > :since
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration rateLimitWindow;

    public OutboxEnqueuer(NamedParameterJdbcTemplate jdbc,
                          @Value("${edutrack.mail.rate-limit-window:PT1M}") Duration rateLimitWindow) {
        this.jdbc = jdbc;
        this.rateLimitWindow = rateLimitWindow;
    }

    /**
     * Queue one mail for delivery, in the caller's transaction.
     *
     * <p>{@code next_attempt_at} defaults to now, so the next poll picks it up.
     * Delaying a send is a matter of updating that column, not of holding the
     * message in memory.
     *
     * <p><strong>D-035 rate limit.</strong> At most one mail per recipient per
     * ticket per minute (blueprint §4B.6): a ticket edited five times in a
     * minute should not put five mails in the assignee's inbox, because the
     * assignee who gets that stops reading any of them. The throttled mail is
     * dropped rather than delayed — spreading a burst out over five minutes
     * still delivers five mails and achieves nothing.
     *
     * <p>Nothing is lost that the user needed: the in-app notification path
     * (D-040) is not rate limited, and the ticket itself holds the authoritative
     * state. Only the redundant <em>email</em> goes away.
     *
     * <p>Note this is <em>not</em> the "critical mails cannot be disabled" rule.
     * Blueprint §4B.6 scopes that exemption to user <em>preferences</em>
     * (D-036/D-042), and states this limit unconditionally, so assignment and
     * escalation mails are throttled like any other. If that turns out to be
     * wrong in practice — a breach suppressed because an assignment went out
     * 30 seconds earlier — D-036 is where the exemption belongs.
     *
     * <p>The check and the insert share the caller's transaction, but this is a
     * read-then-write without a lock: two concurrent enqueues can both see an
     * empty window and both insert. That is accepted. Making it exact needs a
     * unique constraint on a minute bucket, which turns a spam-prevention
     * heuristic into something that can fail a business transaction.
     *
     * @return the {@code email_log} id, or empty if the mail was throttled
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OptionalLong enqueue(NewMail mail) {
        if (isRateLimited(mail)) {
            log.debug("outbox: throttled {} to {} for ticket {} — one already queued this window",
                    mail.eventCode(), mail.toEmail(), mail.ticketId());
            return OptionalLong.empty();
        }

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
        return OptionalLong.of(id.longValue());
    }

    private boolean isRateLimited(NewMail mail) {
        Integer recent = jdbc.queryForObject(RECENT_TO_SAME_RECIPIENT,
                new MapSqlParameterSource()
                        .addValue("toEmail", mail.toEmail())
                        .addValue("ticketId", mail.ticketId())
                        .addValue("since", Timestamp.from(Instant.now().minus(rateLimitWindow))),
                Integer.class);
        return recent != null && recent > 0;
    }
}
