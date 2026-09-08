package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

/**
 * A-117 · the four refusals OB-08 can produce, and the status each carries.
 *
 * <p>Grouped in one file because they are one decision table rather than four
 * independent conditions, and reading them apart is how a 403 quietly becomes
 * a 404 in the next task. The contract fixes every status below; none is a
 * judgement made here.
 */
final class ObModuleAccessExceptions {

    private ObModuleAccessExceptions() {
    }

    /**
     * 403 — not an OB Admin.
     *
     * <h2>Why 403 here when the rest of the module answers 404</h2>
     *
     * <p>CLAUDE.md's rule is that an out-of-scope id answers 404 so no
     * existence leaks. That rule is about <b>rows</b>: it protects the fact
     * that a particular client or ticket exists. This resource is
     * organisation-wide administration, and there is no grant whose existence a
     * 404 could protect — the caller is refused the screen, not a row.
     *
     * <p>The contract states it in those words on all three operations, and it
     * also notes what makes the 403 safe: the caller has already passed
     * {@code ModuleGuard} to arrive here at all, so they are known to hold
     * onboarding access. Telling them they are not the administrator tells them
     * nothing they could not learn from the absence of the screen.
     */
    static final class NotAnOnboardingAdminException extends RuntimeException {
        NotAnOnboardingAdminException() {
            super("Only an onboarding administrator may manage module access.");
        }
    }

    /**
     * 409 — the user already holds a live grant for this module.
     *
     * <p>{@code uq_user_module_access_live} would refuse the insert anyway, so
     * this check is not what makes the invariant true. What it makes is the
     * <em>answer</em>: without it the caller gets a constraint-violation 500
     * naming a MySQL index, which is the failure the client master hit and
     * A-125 corrected. The database stays the guarantee; this is the sentence.
     *
     * <p>Changing somebody's role is revoke-then-grant, which is the message
     * this carries, because an admin who reads only "already granted" will
     * reasonably conclude the screen is broken.
     */
    static final class DuplicateGrantException extends RuntimeException {
        DuplicateGrantException(String module) {
            super("This user already has live access to " + module
                    + ". To change their role, revoke the existing grant first.");
        }
    }

    /**
     * 422 — already revoked.
     *
     * <p>Not 404: the grant exists and the caller may see it. Not 409 either,
     * which would suggest a conflicting concurrent state to retry past — there
     * is nothing to retry, the row is in its final state.
     */
    static final class AlreadyRevokedException extends RuntimeException {
        AlreadyRevokedException() {
            super("This grant has already been revoked.");
        }
    }

    /**
     * 422 — the last live {@code OB_ADMIN} grant in the organisation.
     *
     * <p>Refused rather than allowed, on the contract's reasoning: a module
     * with no administrator cannot grant anybody access to itself, including
     * the access needed to undo this. Recovering means an edit to the database
     * by hand.
     *
     * <p>The message names the way out — grant somebody else first — because a
     * refusal that does not is indistinguishable from a bug.
     */
    static final class LastAdminGrantException extends RuntimeException {
        LastAdminGrantException() {
            super("This is the last onboarding administrator. Grant OB_ADMIN to somebody else "
                    + "before revoking this one, or the module will have nobody who can.");
        }
    }

    /** 400 — the body named a user, module or role the schema does not allow. */
    static final class GrantValidationException extends RuntimeException {
        GrantValidationException(String message) {
            super(message);
        }
    }

    /**
     * 404 — no such grant.
     *
     * <p>The one 404 in the package, and it is an ordinary missing row rather
     * than a scope refusal: an Admin sees every grant, so there is no id that
     * exists and is nonetheless invisible.
     */
    static final class GrantNotFoundException extends RuntimeException {
        GrantNotFoundException(long grantId) {
            super("No module-access grant with id " + grantId + ".");
        }
    }
}
