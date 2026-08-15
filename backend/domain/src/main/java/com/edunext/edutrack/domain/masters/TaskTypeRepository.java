package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskTypeRepository extends JpaRepository<TaskType, Integer> {

    /** Codes are unique ({@code uq_task_types_code}) and are what imports match on. */
    Optional<TaskType> findByCode(String code);

    /** The type picker, in the order the master defines — S-11. */
    List<TaskType> findByIsActiveTrueOrderBySeqAsc();

    /**
     * The S-11 grid — <b>every</b> row, retired ones included.
     *
     * <p>A ticket raised last year against a type since deactivated still has to
     * render its name, so the master screen and the pickers ask different
     * questions and get different queries. {@code id} breaks the tie because
     * {@code seq} is not unique and two rows sharing one would otherwise swap
     * places between page loads.
     */
    List<TaskType> findAllByOrderBySeqAscIdAsc();

    /** B-020 · the create's uniqueness check, before the index has to make it. */
    boolean existsByCode(String code);

    /**
     * B-020 · names are unique too, and only this rule says so.
     *
     * <p>There is no {@code uq_task_types_name}. Two types rendering the same
     * label are indistinguishable in every picker in the product, and the
     * create form's §4B.2 client-mandatory rule matches on the name — so a
     * duplicate would silently take the rule with it.
     */
    Optional<TaskType> findByNameIgnoreCase(String name);
}
