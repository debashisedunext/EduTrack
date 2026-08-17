package com.edunext.edutrack.domain.outbox;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
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

    /** D-031. Primary-key lookup for the subject prefix. */
    private static final String TICKET_CODE = """
            SELECT ticket_code FROM tickets WHERE id = :ticketId
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
    private final NotificationPreferences preferences;
    private final Duration rateLimitWindow;

    public OutboxEnqueuer(NamedParameterJdbcTemplate jdbc,
                          NotificationPreferences preferences,
                          @Value("${edutrack.mail.rate-limit-window:PT1M}") Duration rateLimitWindow) {
        this.jdbc = jdbc;
        this.preferences = preferences;
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
     * <p>Note this is <em>not</em> the "critical mails cannot be disabled" rule,
     * which now exists alongside it (D-036). §4B.6 scopes that exemption to user
     * <em>preferences</em> and states this limit unconditionally, so a mandatory
     * mail is still throttled: {@link NotificationEvent#isMandatoryMail()}
     * defeats a preference, never the rate limit. The two answer different
     * questions — "did you ask not to receive this" and "have we just sent you
     * one" — and collapsing them would let a burst of assignments become a burst
     * of mail again.
     *
     * <p>The residual risk is real and accepted: a breach mail suppressed
     * because an assignment for the same ticket went out 30 seconds earlier. The
     * in-app path is not rate limited, so the alert still lands; if that proves
     * too weak in practice, the fix is a per-event window here rather than an
     * exemption in D-036.
     *
     * <p>The check and the insert share the caller's transaction, but this is a
     * read-then-write without a lock: two concurrent enqueues can both see an
     * empty window and both insert. That is accepted. Making it exact needs a
     * unique constraint on a minute bucket, which turns a spam-prevention
     * heuristic into something that can fail a business transaction.
     *
     * @return the {@code email_log} id, or empty if the mail was throttled or
     *         suppressed by a preference (D-042)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OptionalLong enqueue(NewMail mail) {
        if (hasNoEmailChannel(mail)) {
            log.debug("outbox: {} has no email channel in §11 — not queued", mail.eventCode());
            return OptionalLong.empty();
        }
        if (isSuppressedByPreference(mail)) {
            log.debug("outbox: {} to {} suppressed by preference", mail.eventCode(), mail.toEmail());
            return OptionalLong.empty();
        }
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
                        // D-031. Composed here rather than trusted from the
                        // caller, so no §4B.6 event can ship without its code.
                        .addValue("subject", TicketMailSubject.compose(
                                ticketCodeOf(mail.ticketId()), mail.subject())),
                keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("email_log insert returned no generated key");
        }
        return OptionalLong.of(id.longValue());
    }

    /**
     * D-042 · the user turned this mail off · D-036 · unless they may not.
     *
     * <p>Checked at enqueue rather than at send, so a suppressed mail never
     * enters {@code email_log} at all. Queueing it and dropping it later would
     * put a row in the delivery log that D-033 reads as "we tried", and §17
     * wants that log to answer "did they get it" without a second story about
     * which QUEUED rows were never really going anywhere.
     *
     * <p>A mail with no {@code toUserId} — a client contact, who is not a user
     * — has no preferences to consult and is always enqueued.
     */
    /**
     * D-040 · §11 says this event has no email column at all.
     *
     * <p>Checked <strong>before</strong> the preference and before the rate
     * limit, because it is a different kind of statement from either. A
     * preference is a user's choice about a mail that exists; this is the
     * product saying the mail does not exist. Ordering it after the preference
     * check would let {@link NotificationEvent#isMandatoryMail()} force-queue a
     * mail the blueprint never wanted — the exact contradiction D-040 found in
     * {@code TICKET_REASSIGNED_AWAY}, which is an assignment and therefore
     * "mandatory", and which §11 gives a dash in the Email column.
     *
     * <p>{@code OPT_IN} is treated as having a channel, not as suppressed: it
     * is off by <em>default</em>, and D-042 stores only deviations, so the
     * preference lookup below is what turns it on for somebody who asked.
     *
     * <p>An event code this build does not know is <strong>not</strong> refused.
     * Mail is raised by producers across three modules and a row from a newer
     * deploy must still send — the enum is authoritative about what it declares,
     * not about what exists.
     */
    private boolean hasNoEmailChannel(NewMail mail) {
        return NotificationEvent.of(mail.eventCode())
                .map(event -> event.mail() == NotificationEvent.Mail.NEVER)
                .orElse(false);
    }

    private boolean isSuppressedByPreference(NewMail mail) {
        if (mail.toUserId() == null) {
            return false;
        }
        return !preferences.allows(mail.toUserId(), mail.eventCode(), NotificationChannel.EMAIL);
    }

    /**
     * D-031 · the code that goes at the front of the subject.
     *
     * <p>One extra read per enqueue, on the primary key. Enqueue is not a hot
     * path — it happens once per recipient per event — and the alternative is
     * threading the code through every caller, where the one that forgets is
     * invisible until somebody cannot find a mail.
     */
    private String ticketCodeOf(Long ticketId) {
        if (ticketId == null) {
            return null;
        }
        try {
            return jdbc.queryForObject(TICKET_CODE,
                    new MapSqlParameterSource("ticketId", ticketId), String.class);
        } catch (EmptyResultDataAccessException e) {
            // A foreign key makes this unreachable, and it degrades rather than
            // throws anyway: an unprefixed subject is a worse mail, while an
            // exception here would roll back the handoff that raised it.
            log.warn("outbox: no ticket {} for subject prefix — sending unprefixed", ticketId);
            return null;
        }
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
