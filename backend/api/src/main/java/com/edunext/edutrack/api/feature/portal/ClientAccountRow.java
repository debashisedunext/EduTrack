package com.edunext.edutrack.api.feature.portal;

import java.time.Instant;

/**
 * A-125 · exactly the columns authenticating a portal login needs, and no
 * others.
 *
 * <p>{@code AuthUserRow}'s shape, deliberately — the two are the same kind of
 * thing one table over, and the reasons that record gives apply here unchanged:
 * authentication runs on every attempt including the failing ones and wants a
 * single narrow row, not an aggregate root with lazy associations that would
 * either N+1-query or over-fetch on the hot path.
 *
 * <p><b>Not a JPA entity, and this one has a second reason.</b> A mapped
 * {@code ClientAccount} entity would sit in {@code domain} beside {@code
 * ObClient} and {@code User}, one association away from both — and the whole
 * point of {@code client_accounts} being its own table is that a portal caller
 * cannot be reached from a staff query by following a relationship. A flat
 * read-only projection has no relationships to follow.
 *
 * <p><b>There is no {@code roleCode}.</b> A staff row has one and every
 * downstream consumer switches on it; a client has no role in the blueprint §2
 * sense, and inventing a {@code CLIENT} entry in {@code roles} to fill the hole
 * would put an external party inside the matrix that
 * {@code PermissionMatrixTest} reads. What a portal caller may do is decided by
 * being a CLIENT at all, plus which client rows it owns — see
 * {@link #clientId} and {@link #obClientId}.
 *
 * @param id            {@code client_accounts.id}. The CLIENT principal's
 *                      subject. <b>Not a {@code users} id and never
 *                      interchangeable with one</b> — the two sequences overlap,
 *                      so a value of 7 is a valid staff id and a valid account
 *                      id at the same time. Nothing may compare them, and
 *                      {@code CallerIdentity} keeps them apart by principal
 *                      type rather than by hoping the ranges do not collide.
 * @param username      generated at issue, never chosen. The login key.
 * @param passwordHash  Argon2id, written by {@code PasswordHashing.argon2id()} —
 *                      the same encoder {@code users.password_hash} uses, so
 *                      that a verification costs the same on both paths.
 *                      Never leaves this package.
 * @param clientId      the ticketing master's row, or null.
 * @param obClientId    the onboarding master's row, or null. At least one of
 *                      the two is non-null; {@code ck_client_accounts_has_a_master}
 *                      is what guarantees it, so nothing downstream has to
 *                      handle an account belonging to nobody.
 * @param displayName   who this login is, denormalised from the contact it was
 *                      issued to. The migration's header says why it is not a
 *                      join: contacts are deactivated and replaced, and a login
 *                      whose identity is a join to a deactivatable row stops
 *                      being able to describe itself.
 * @param email         where the credential and every sign-off request goes.
 *                      Deliberately not unique — one consultant can be the SPOC
 *                      at two clients.
 * @param active        {@code is_active}. A deactivated account is rejected,
 *                      and rejected with the same generic failure as a wrong
 *                      password, so deactivation is not observable from
 *                      outside.
 * @param mustChangePassword A-026's rule, unchanged for the portal. Defaults to
 *                      true in the schema so an account created by any path —
 *                      the OB-04 wizard, an admin panel, a fixture — starts in
 *                      the state that forces the change.
 * @param failedAttempts A-021. Consecutive failures since the last success or
 *                      lock. This is the internet-facing half of the product,
 *                      so it needs the lockout more than staff logins do, not
 *                      less.
 * @param lockedUntil   A-021. When the lock lapses, or null if not locked.
 * @param lastLoginAt   most recent successful login, or null if never.
 */
record ClientAccountRow(
        long id,
        String username,
        String passwordHash,
        Long clientId,
        Long obClientId,
        String displayName,
        String email,
        boolean active,
        boolean mustChangePassword,
        int failedAttempts,
        Instant lockedUntil,
        Instant lastLoginAt) {

    /**
     * The client rows this login owns, as the pair a scope resolver needs.
     *
     * <p>Returned as a pair rather than a single "the client id" because the
     * two masters are different tables and an id from one means nothing in the
     * other. A-126's {@code ClientScopeResolver} pins every portal query to
     * whichever of these is non-null for the tree being queried —
     * {@code /portal/tickets/**} to {@link #clientId},
     * {@code /portal/onboarding/**} to {@link #obClientId} — and a null one
     * means that tree is empty for this caller, never that it is unfiltered.
     */
    boolean owns(Long ticketingClientId, Long onboardingClientId) {
        return (ticketingClientId != null && ticketingClientId.equals(clientId))
                || (onboardingClientId != null && onboardingClientId.equals(obClientId));
    }
}
