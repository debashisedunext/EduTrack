package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ObSignoffRepository extends JpaRepository<ObSignoff, Long> {

    /** C-106's sign-off gate: has this step got an accepted `STEP` sign-off? */
    boolean existsByStepIdAndKindAndStatus(Long stepId, ObSignoffKind kind, ObSignoffStatus status);
}
