package com.edunext.edutrack.api.feature.portal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code edutrack.portal.dev-credentials.*} — the switch that lets a
 * non-production deployment hand the operator a client's portal password
 * instead of mailing them a link.
 *
 * <h2>This exists to relax a property, so it says which one</h2>
 *
 * <p>{@link ClientAccountAdminDtos} states the rule this suspends: a credential
 * on the account response "would put a live credential on a staff screen, in a
 * browser cache and in whatever the reader pastes it into, and would make the
 * audit answer to <i>who could have used this login</i> everyone who has ever
 * opened the client". Every word of that is still true when this is on. What
 * changes is where it is true — a demo database whose clients are invented and
 * whose mail transport is {@code logging}, against which the argument is about
 * a risk that does not exist.
 *
 * <p><b>Off by default, and the default is the product.</b> The gate is a
 * property rather than a Spring profile because a profile is a set of things
 * that travel together and this must be able to travel alone: switched on for a
 * demo box, never implied by {@code dev} on somebody's laptop pointed at a real
 * database. {@code ClientAccountAdminService} reads it once per call rather
 * than caching a boolean, so turning it off takes effect without a redeploy.
 *
 * @param enabled  whether create and reset set a password the operator can read
 *                 back. False unless a deployment says otherwise, which is the
 *                 shipped behaviour and the behaviour every test that does not
 *                 name this property gets.
 * @param password the password to set, shared by every account this issues.
 *                 Blank — the default — means one is generated per account
 *                 instead, which is the safer of the two and still readable
 *                 from the response. A fixed value is offered because a demo
 *                 that logs in as six clients in ten minutes wants one password
 *                 rather than six, and typing it is the whole point.
 *                 <p>Must satisfy {@link PortalPasswordRules} (12 characters or
 *                 more) or the account is created with a password its own
 *                 portal would refuse; validated on use rather than here, so a
 *                 misconfiguration fails the request that needed it rather than
 *                 the application's startup.
 */
@ConfigurationProperties(prefix = "edutrack.portal.dev-credentials")
record PortalDevCredentialProperties(
        Boolean enabled,
        String password
) {
    PortalDevCredentialProperties {
        if (enabled == null) enabled = Boolean.FALSE;
        if (password == null) password = "";
    }

    /**
     * Whether to issue a readable password at all.
     *
     * <p>A method rather than a raw {@code enabled} read, because the record
     * component is a boxed {@code Boolean} to let the compact constructor
     * supply the default — and an unboxing call site is one {@code null} away
     * from a {@code NullPointerException} in the branch that decides whether to
     * expose a credential. That branch fails closed here instead.
     */
    boolean issuesReadablePassword() {
        return Boolean.TRUE.equals(enabled);
    }

    /** Whether a single shared password is configured, as opposed to per-account. */
    boolean hasFixedPassword() {
        return password != null && !password.isBlank();
    }
}
