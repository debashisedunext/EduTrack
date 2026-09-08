package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObPrereqTemplateTaskRepository extends JpaRepository<ObPrereqTemplateTask, Long> {

    List<ObPrereqTemplateTask> findByVersionIdOrderBySequenceAsc(Long versionId);

    /** Next sequence within a version is this row's {@code sequence + 1}. */
    Optional<ObPrereqTemplateTask> findTopByVersionIdOrderBySequenceDesc(Long versionId);

    /**
     * What {@code publishObPrereqTemplate} refuses a draft for. A checklist
     * with nothing mandatory clears its own gate the moment it is
     * instantiated, so every journey would open at boarding and the gate
     * would look present while doing nothing — plan §5.3 calls it a hard
     * gate, and publish time is where that is enforced.
     */
    long countByVersionIdAndIsMandatoryTrue(Long versionId);

    long countByVersionId(Long versionId);
}
