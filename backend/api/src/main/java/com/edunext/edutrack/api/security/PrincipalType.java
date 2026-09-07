package com.edunext.edutrack.api.security;

import java.util.Locale;

/**
 * A-125 · what kind of thing is holding this token.
 *
 * <h2>Why this exists rather than a role</h2>
 *
 * <p>The obvious design is a {@code CLIENT} row in {@code roles} and a normal
 * {@link CallerIdentity} carrying it. That design is wrong in a way that only
 * shows up later, and it is worth writing down because it will look tempting
 * again:
 *
 * <p><b>{@code client_accounts.id} and {@code users.id} are separate sequences
 * that overlap.</b> The value 7 is a valid staff id and a valid portal account
 * id at the same time. If a portal caller were a {@code CallerIdentity}, then
 * every staff scope check — {@code ScopeResolver}'s {@code assigned_to = me},
 * {@code OnboardingScopeResolver}'s owner predicate, every
 * {@code reportees.contains(userId)} — would compare a client's account id
 * against staff ids and silently succeed on the collisions. Not throw. Not
 * deny. Return somebody else's rows.
 *
 * <p>So the two are kept apart by <em>type</em>, and the separation is enforced
 * at the one place both are built from: a token that says {@code CLIENT} can
 * never become a {@code CallerIdentity}, and a token that does not say it can
 * never become a {@link com.edunext.edutrack.api.feature.portal.ClientPrincipal}.
 * Neither can be reached from the other by any predicate, because there is no
 * predicate — there is a type boundary.
 *
 * <h2>Absent means STAFF, and that is safe</h2>
 *
 * <p>Every token minted before A-125 carries no {@code principal_type} claim,
 * and there are live ones. Reading absent as {@code STAFF} keeps them working.
 *
 * <p>That default is only safe because the claim cannot be removed from a token
 * that had it: the JWT is signed, so stripping the claim invalidates it. The
 * fallback therefore describes old tokens, never tampered ones.
 *
 * <p>There is a second, independent barrier behind it. A portal token carries
 * no {@code role} claim, and {@code CallerIdentity.fromToken} already refuses a
 * token with no role. So a client token would fail to become a staff identity
 * even if this enum did not exist — belt and braces, deliberately, because the
 * cost of the barrier failing is a client reading staff rows.
 */
public enum PrincipalType {

    /** A member of staff. A row in {@code users}. */
    STAFF,

    /** A-125 · a client contact holding a portal login. A row in {@code client_accounts}. */
    CLIENT;

    /** The claim name. Written only for {@link #CLIENT}; see the class note on why absent is safe. */
    public static final String CLAIM = "principal_type";

    /**
     * The type a claim value names, defaulting to {@link #STAFF}.
     *
     * <p>An <em>unrecognised</em> value also reads as {@code STAFF}, which
     * looks like the wrong default and is the right one: the only way to hold a
     * token carrying a value this enum does not know is to hold a token we
     * signed with a newer deploy's vocabulary, and such a token belongs to a
     * member of staff on a version that understands it. A client token is
     * refused by the {@code role} barrier regardless — so an unknown value
     * cannot let a client through, and reading it as CLIENT would lock out
     * staff mid-deploy.
     */
    public static PrincipalType of(String claim) {
        if (claim == null || claim.isBlank()) {
            return STAFF;
        }
        return CLIENT.name().equals(claim.trim().toUpperCase(Locale.ROOT)) ? CLIENT : STAFF;
    }
}
