package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObPrereqTemplateTaskDocRepository extends JpaRepository<ObPrereqTemplateTaskDoc, Long> {

    List<ObPrereqTemplateTaskDoc> findByTemplateTaskIdOrderBySequenceAsc(Long templateTaskId);

    List<ObPrereqTemplateTaskDoc> findByTemplateTaskIdInOrderBySequenceAsc(List<Long> templateTaskIds);

    Optional<ObPrereqTemplateTaskDoc> findTopByTemplateTaskIdOrderBySequenceDesc(Long templateTaskId);
}
