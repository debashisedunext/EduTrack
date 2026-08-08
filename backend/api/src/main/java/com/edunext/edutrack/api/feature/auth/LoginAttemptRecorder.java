package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A-021 · the write half of login. Blueprint §S-01: "after 5 failed attempts →
 * 15-minute lockout + email to Admin".
 *
 * <p><b>Why this is a separate bean, and not two methods on
 * {@link AuthenticationService}.</b> Two independent reasons, either of which
 * on its own would be enough:
 *
 * <ol>
 *   <li><b>{@code REQUIRES_NEW} is load-bearing.</b> Recording a failure ends
 *       with {@code AuthenticationService} throwing, and Spring rolls a
 *       transaction back on any unchecked exception. Increment and throw in one
 *       transaction and the increment is undone — the counter would sit at zero
 *       forever and no account would ever lock. The write has to commit in a
 *       transaction of its own that the caller's rollback cannot reach.</li>
 *   <li><b>{@code @Transactional} is proxy-based.</b> A private or self-called
 *       method on the same bean bypasses the proxy entirely and the annotation
 *       is silently ignored — no error, no transaction, and the bug above
 *       reappears looking like a database problem. Crossing a bean boundary is
 *       what makes the proxy apply.</li>
 * </ol>
 *
 * <p>Both failure modes are invisible in a passing unit test that mocks the
 * repository, which is why {@code AuthLoginIT} exercises the counter against a
 * real database instead.
 */
@Component
class LoginAttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptRecorder.class);

    /** Blueprint §S-01. Fixed, not configurable — see {@code PasswordHashing} for the same reasoning. */
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** Not one of §4B.6's ticket events — that table is scoped to ticket notifications. */
    private static final String EVENT_CODE = "ACCOUNT_LOCKED";

    private final AuthUserRepository users;
    private final OutboxEnqueuer outbox;

    LoginAttemptRecorder(AuthUserRepository users, OutboxEnqueuer outbox) {
        this.users = users;
        this.outbox = outbox;
    }

    /**
     * Counts one failure and locks the account on the fifth.
     *
     * <p>Called only for a username that exists. A failure against an unknown
     * username has nothing to count against, and inventing a per-username
     * counter for non-existent users would hand back the enumeration signal
     * A-020 spent its whole design closing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(AuthUserRow user, Instant now) {
        int attempts = users.incrementFailedAttempts(user.id());
        if (attempts < MAX_FAILED_ATTEMPTS) {
            return;
        }

        Instant lockedUntil = now.plus(LOCK_DURATION);
        users.applyLock(user.id(), lockedUntil);
        log.info("Account {} locked until {} after {} failed attempts", user.id(), lockedUntil, attempts);
        notifyAdmins(user, lockedUntil);
    }

    /**
     * Clears the counter after a good password, but only when there is
     * something to clear — the overwhelming majority of logins are by someone
     * who did not mistype, and an unconditional {@code UPDATE users} would put
     * a write, a row lock and a replication event on the hot path of every
     * single sign-in to set zero to zero.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordSuccess(AuthUserRow user) {
        if (user.failedAttempts() == 0 && user.lockedUntil() == null) {
            return;
        }
        users.clearLockoutState(user.id());
    }

    /**
     * <p>Queued through Stream D's outbox, so the mail commits with the lock:
     * an announced lockout that rolled back, or a lock with no announcement,
     * are both worse than either alone.
     *
     * <p><b>Two known limits, stated rather than hidden.</b> D-035 throttles the
     * outbox to one mail per recipient per ticket per minute; these carry no
     * ticket, so a burst locking several accounts within a minute tells the
     * Admin about the first only. That is the right trade for an inbox — but it
     * means the mail is a prompt to look, never the audit record. A-071's audit
     * log is the complete one.
     *
     * <p>And there are no Admins to find until Stream B's B-001 seeds the six
     * system roles, so today this reliably logs a warning and sends nothing. The
     * lock still applies; announcing it is best-effort, holding it is not.
     */
    private void notifyAdmins(AuthUserRow lockedUser, Instant lockedUntil) {
        List<AdminRecipient> admins = users.findActiveAdmins();
        if (admins.isEmpty()) {
            log.warn("Account {} locked until {} but no active Admin exists to notify",
                    lockedUser.id(), lockedUntil);
            return;
        }

        String subject = "[EduTrack] Account locked: %s after %d failed sign-in attempts"
                .formatted(lockedUser.username(), MAX_FAILED_ATTEMPTS);

        for (AdminRecipient admin : admins) {
            outbox.enqueue(new NewMail(null, EVENT_CODE, null, admin.userId(), admin.email(), subject));
        }
    }
}
