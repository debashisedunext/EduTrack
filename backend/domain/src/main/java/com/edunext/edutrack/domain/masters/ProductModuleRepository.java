package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductModuleRepository extends JpaRepository<ProductModule, Integer> {

    /**
     * B-064 · the whole master, retired rows included, in the order §7.3 lists
     * it.
     *
     * <p><b>There is deliberately no {@code findByIsActiveTrue…} beside this
     * one, which is where this master parts company with {@link
     * TaskTypeRepository}.</b> That interface carries both queries because S-11
     * is two screens — a grid that shows every row and a picker that shows the
     * live ones. This master has one route and no admin screen, and its
     * contract says every caller gets every row: a picker offers only the
     * active ones, a grid still has to render the name of a module some old
     * ticket was raised against. Adding the narrower query would be adding the
     * means to answer a question no caller here is allowed to ask.
     *
     * <p>{@code id} breaks the tie because {@code seq} is not unique — the
     * column has no unique index and the migration seeds it in tens, so two
     * rows sharing a value would otherwise swap places between reads. Same
     * reason {@code TaskTypeRepository.findAllByOrderBySeqAscIdAsc} gives.
     */
    List<ProductModule> findAllByOrderBySeqAscIdAsc();

    /** Codes are unique ({@code uq_product_modules_code}). */
    Optional<ProductModule> findByCode(String code);
}
