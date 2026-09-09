package com.edunext.edutrack.api.feature.portal;

import java.time.Instant;

/**
 * A-130 · the refusals the portal's authentication surface can make.
 *
 * <p>Grouped like {@code ObModuleAccessExceptions}, and small on purpose: the
 * count of distinct failures a caller can tell apart <em>is</em> the security
 * design here, so having them all readable on one screen is the point.
 */
final class PortalAuthExceptions {

    private PortalAuthExceptions() {
    }

    /**
     * Unknown username, wrong password, or a deactivated account — one
     * exception carrying no reason, exactly as {@code InvalidCredentialsException}
     * does for staff.
     *
     * <p>It matters more here than it does there. A portal username is
     * derived from the client's own name ({@code PortalUsernames} builds
     * {@code ACME.ravi}), the population is one per client contact, and the
     * form is reachable by anyone on the internet. A refusal that distinguished
     * "no such login" from "wrong password" would turn that form into a
     * directory of which of our customers have portal access.
     */
    static final class InvalidPortalCredentials extends RuntimeException {
        InvalidPortalCredentials() {
            super("Invalid portal credentials", null, false, false);
        }
    }

    /**
     * The account is locked, and the caller has <b>already proved they know the
     * password</b>.
     *
     * <p>That precondition is what makes it safe to say so, and it is the same
     * bargain {@code AccountLockedException} strikes for staff: the check runs
     * after the hash comparison, never before. Checking first would answer a
     * locked account faster than an unlocked one and hand back through timing
     * exactly what the uniform refusal above exists to withhold.
     */
    static final class PortalAccountLocked extends RuntimeException {
        private final transient Instant lockedUntil;

        PortalAccountLocked(Instant lockedUntil) {
            super("Portal account locked", null, false, false);
            this.lockedUntil = lockedUntil;
        }

        Instant lockedUntil() {
            return lockedUntil;
        }
    }

    /**
     * The credential link is expired, already spent, or was never issued.
     *
     * <p>One exception for all three, and {@code 410 Gone} for all three. The
     * surface is unauthenticated, so distinguishing them would let anybody
     * holding a random string learn whether it was ever real — and whether the
     * account behind it has already been activated, which is a fact about a
     * customer.
     */
    static final class InvalidCredentialLink extends RuntimeException {
        InvalidCredentialLink() {
            super("Credential link is not valid", null, false, false);
        }
    }

    /**
     * The chosen password does not meet the policy.
     *
     * <p>Specific, unlike everything above it, and safely so: the caller is
     * redeeming a link only they could have received, and the rule they broke
     * is one they have to be told in order to satisfy it. Withholding it would
     * produce a form that refuses without saying why.
     */
    static final class WeakPortalPassword extends RuntimeException {
        WeakPortalPassword(String detail) {
            super(detail, null, false, false);
        }
    }
}
