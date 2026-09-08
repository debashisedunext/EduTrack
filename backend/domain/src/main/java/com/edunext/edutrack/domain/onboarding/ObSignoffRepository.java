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
}
