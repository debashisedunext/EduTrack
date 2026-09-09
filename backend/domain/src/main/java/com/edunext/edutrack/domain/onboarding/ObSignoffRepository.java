package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ObSignoffRepository extends JpaRepository<ObSignoff, Long> {

    /** C-106's sign-off gate: has this step got an accepted `STEP` sign-off? */
    boolean existsByStepIdAndKindAndStatus(Long stepId, ObSignoffKind kind, ObSignoffStatus status);

    /**
     * A-120 · the only way a public token becomes a row.
     *
     * <p>By hash, because the plaintext is never stored — A-107's migration
     * puts a SHA-256 here so that our own database cannot yield a working link.
     *
     * <p>Deliberately not {@code findByTokenHashAndStatusAndTokenExpiresAtAfter}.
     * The freshness rule would then live in this signature and have to be
     * repeated in every finder that ever looks a token up, and the one that
     * forgets it is a working link that never expires. {@code ObSignoffTokens}
     * judges usability in one place instead.
     */
    Optional<ObSignoff> findByTokenHash(String tokenHash);

    /**
     * B-119 · "this go-live has already been surveyed" — asked across every
     * {@code GO_LIVE} sign-off for a client, not only the one row a given
     * session addresses.
     *
     * <p>A client can hold several {@code GO_LIVE} sign-offs, one per
     * journey/product (B-118's own note: "a client with three purchased
     * products earns three GO_LIVE sign-offs"), and the contract's rule is
     * "one answer per client" — not one answer per journey. So the guard
     * this backs is scoped by {@code obClientId}, and it is {@code exists}
     * rather than a lookup because the caller only ever needs the boolean:
     * whichever journey's session is being used to submit CSAT, a survey
     * already answered through a different journey's session still refuses
     * this one with the contract's {@code 422}.
     */
    boolean existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(Long obClientId, ObSignoffKind kind);
}
