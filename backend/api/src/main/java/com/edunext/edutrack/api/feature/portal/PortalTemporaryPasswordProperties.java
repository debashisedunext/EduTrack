package com.edunext.edutrack.api.feature.portal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code edutrack.portal.temporary-password.*} — how a client's first
 * password is produced.
 *
 * <h2>This was a development switch and is now the product</h2>
 *
 * <p>It began life as {@code edutrack.portal.dev-credentials}: off by default,
 * refused outside a development profile, and existing so a demo could sign in
 * as a client it had just invented. The flow it stood in for — mail the contact
 * a one-time link, let them choose their own password, never show a credential
 * to staff — was the product.
 *
 * <p>That was reversed deliberately. The link flow requires a working mail
 * path, and a deployment without one creates accounts that <b>nobody can ever
 * sign in to</b>: the stored password is a hash of bytes that were discarded,
 * so an undelivered link is not a delayed credential, it is no credential at
 * all. The operator who ticked the box is told a login exists, and it does not.
 *
 * <p>So the temporary password is now how a portal login is issued, on every
 * deployment, and the client changes it at first sign-in —
 * {@code PortalPasswordChangeGate} is what makes that change compulsory rather
 * than suggested. The credential link still exists and is still mailed; it is
 * the recovery path, not the only way in.
 *
 * <h2>What the old gate protected, and where that protection moved</h2>
 *
 * <p>{@link ClientAccountAdminDtos} states the rule this relaxes: a credential
 * on the account response "would put a live credential on a staff screen, in a
 * browser cache and in whatever the reader pastes it into". That is still true,
 * and it is now an accepted cost rather than a refused one — the same cost
 * {@code TemporaryPasswords} accepts for a staff account one table over.
 *
 * <p>Two things bound it. The password is <b>single-use in practice</b>: it
 * opens exactly one session, which can do nothing but change it. And it is
 * <b>random per account</b> unless a deployment says otherwise — which
 * {@link PortalTemporaryPasswordConfig} permits only under a development
 * profile, because a shared value means one demo account discloses the initial
 * password of every client created after it.
 *
 * @param enabled  whether create and reset set a password the operator can read
 *                 back. <b>True by default</b> — see above. Setting it false
 *                 returns to the link-only flow, which is correct only where
 *                 {@code edutrack.ob-outbox.email.transport} is {@code smtp}
 *                 and the mail is known to arrive.
 * @param fixed    a single password shared by every account this issues. Blank
 *                 — the default, and the only value a real deployment may hold
 *                 — means one is generated per account. A value is offered
 *                 because a demo that signs in as six clients in ten minutes
 *                 wants one password rather than six.
 *                 <p>Must satisfy {@link PortalPasswordRules} (12 characters or
 *                 more, upper, lower, a digit and a symbol) or the account is
 *                 created with a password its own portal would refuse;
 *                 validated on use rather than at startup, so a
 *                 misconfiguration fails the request that needed it rather than
 *                 the whole application.
 */
@ConfigurationProperties(prefix = "edutrack.portal.temporary-password")
record PortalTemporaryPasswordProperties(
        Boolean enabled,
        String fixed
) {
    PortalTemporaryPasswordProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (fixed == null) fixed = "";
    }

    /**
     * Whether to issue a readable temporary password at all.
     *
     * <p>A method rather than a raw {@code enabled} read, because the record
     * component is a boxed {@code Boolean} to let the compact constructor
     * supply the default — and an unboxing call site is one {@code null} away
     * from a {@code NullPointerException} in the branch that decides how a
     * client gets in.
     */
    boolean issuesTemporaryPassword() {
        return Boolean.TRUE.equals(enabled);
    }

    /** Whether a single shared password is configured, as opposed to per-account. */
    boolean hasFixedPassword() {
        return fixed != null && !fixed.isBlank();
    }
}
