package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

/** C-115 · the API side's door to one rung — acknowledge and resolve are ordinary row updates. */
public interface ObEscalationRepository extends JpaRepository<ObEscalation, Long> {
}
