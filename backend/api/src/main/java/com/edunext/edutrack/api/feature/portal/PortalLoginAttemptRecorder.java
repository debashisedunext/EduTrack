package com.edunext.edutrack.api.feature.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * C-121 · the write half of portal login — {@code LoginAttemptRecorder}'s own
 * shape and its own reason for being a separate bean, restated because the
 * first draft of this task got it wrong and it is worth leaving the
 * correction visible.
 *
 * <h2>Why {@code REQUIRES_NEW} on a separate bean, and not a private method</h2>
 *
 * <p>{@link PortalLoginService#login} throws {@link
 * PortalInvalidCredentialsException} immediately after a failed verification,
 * and Spring rolls a {@code @Transactional} method back on any unchecked
 * exception. Recording the failure inside that same transaction — as this
 * task's own first draft did — means the increment is undone by the very
 * throw that follows it, and no account would ever reach five failures.
 * {@code REQUIRES_NEW} forces the write to commit in a transaction the
 * caller's rollback cannot reach, and it only takes effect across a bean
 * boundary — a private or self-called method on the same class bypasses the
 * proxy and the annotation is silently ignored.
 */
@Component
class PortalLoginAttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(PortalLoginAttemptRecorder.class);

    /** {@code LoginAttemptRecorder}'s own numbers — blueprint §10.1's lockout, unchanged for the portal. */
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final java.time.Duration LOCK_DURATION = java.time.Duration.ofMinutes(15);

    private final ClientAccountRepository accounts;

    PortalLoginAttemptRecorder(ClientAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(ClientAccountRow account, Instant now) {
        accounts.incrementFailedAttempts(account.id());
        int attempts = account.failedAttempts() + 1;
        if (attempts < MAX_FAILED_ATTEMPTS) {
            return;
        }
        Instant lockedUntil = now.plus(LOCK_DURATION);
        accounts.applyLock(account.id(), lockedUntil);
        log.info("portal auth: account {} locked until {} after {} failed attempts",
                account.id(), lockedUntil, attempts);
    }
}
