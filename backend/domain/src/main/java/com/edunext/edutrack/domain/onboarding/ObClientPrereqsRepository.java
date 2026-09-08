package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ObClientPrereqsRepository extends JpaRepository<ObClientPrereqs, Long> {

    /** One checklist per client, by unique index. */
    Optional<ObClientPrereqs> findByObClientId(Long obClientId);

    boolean existsByObClientId(Long obClientId);
}
