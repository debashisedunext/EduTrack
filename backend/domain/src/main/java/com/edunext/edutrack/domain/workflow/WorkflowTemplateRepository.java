package com.edunext.edutrack.domain.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, Long> {

    Optional<WorkflowTemplate> findByName(String name);

    List<WorkflowTemplate> findByIsActiveTrueOrderByNameAsc();

    /**
     * The fallback when a project × task type has no explicit mapping. More
     * than one row can carry {@code is_default} — the schema does not forbid it
     * — so this returns the set and leaves the choice to the service rather
     * than throwing on a data condition the database permits.
     */
    List<WorkflowTemplate> findByIsDefaultTrueAndIsActiveTrue();
}
