package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskTypeRepository extends JpaRepository<TaskType, Integer> {

    /** Codes are unique ({@code uq_task_types_code}) and are what imports match on. */
    Optional<TaskType> findByCode(String code);

    /** The type picker, in the order the master defines — S-11. */
    List<TaskType> findByIsActiveTrueOrderBySeqAsc();
}
