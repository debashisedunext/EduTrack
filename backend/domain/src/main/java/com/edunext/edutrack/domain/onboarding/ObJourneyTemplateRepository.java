package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObJourneyTemplateRepository extends JpaRepository<ObJourneyTemplate, Long> {

    /** Any version at all — a product's very first template has none. */
    boolean existsByProductId(Long productId);

    /**
     * Every Module Service the product currently publishes, in the order
     * they instantiate and display ({@code sequence}, plan §5.5), with
     * {@code id} as the tiebreak so two services sharing a position still
     * come back in a stable order.
     *
     * <p><b>This replaced {@code findByProductIdAndIsActiveTrue}.</b> That
     * method returned an {@code Optional} and its correctness rested
     * entirely on {@code uq_ob_journey_templates_active} making a second
     * active row per product impossible. {@code V20260910_0030} made it
     * possible on purpose, at which point the old signature would not have
     * failed a test — it would have thrown
     * {@code IncorrectResultSizeDataAccessException} the first time an admin
     * published a second service, in production. Deleted rather than
     * deprecated so nothing reaches for it again.
     */
    List<ObJourneyTemplate> findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(Long productId);

    /** One named service of a product, at whatever version is live now. */
    Optional<ObJourneyTemplate> findByProductIdAndNameAndIsActiveTrue(Long productId, String name);

    List<ObJourneyTemplate> findByProductIdOrderByVersionDesc(Long productId);

    /** Next version number for a product is this row's {@code version + 1}. */
    Optional<ObJourneyTemplate> findTopByProductIdOrderByVersionDesc(Long productId);

    /**
     * The head of one <em>service's</em> version chain.
     *
     * <p>{@code beginRevision} numbers from this rather than from
     * {@link #findTopByProductIdOrderByVersionDesc}: a product sells several
     * named services, and a version counts the edits to one of them. Keyed on
     * name because {@code uq_ob_journey_templates_version (product_id, name,
     * version)} is — see {@code V20260909_1900}.
     */
    Optional<ObJourneyTemplate> findTopByProductIdAndNameOrderByVersionDesc(Long productId, String name);

    /** Every version of every service a product sells, newest first. */
    List<ObJourneyTemplate> findByProductIdOrderByNameAscVersionDesc(Long productId);

    /** C-123 · the whole Module Service catalogue — one row per product with an active version. */
    List<ObJourneyTemplate> findByIsActiveTrueOrderBySequenceAsc();

    /** Batch enrichment for {@code GET /onboarding/products}, one statement rather than N. */
    List<ObJourneyTemplate> findByProductIdInAndIsActiveTrue(java.util.Collection<Long> productIds);
}
