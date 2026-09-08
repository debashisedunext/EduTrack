package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * A-124 · reads and writes over {@code ob_products}.
 *
 * <p>A {@code Repository} rather than {@code CrudRepository}, the choice the
 * append-only repositories make and for a milder version of the same reason:
 * inheriting {@code deleteAll} would put a delete on a catalogue whose entire
 * lifecycle is {@code isActive}, and the method that exists is the method
 * somebody eventually calls.
 */
public interface ObProductRepository extends Repository<ObProduct, Long> {

    ObProduct save(ObProduct product);

    Optional<ObProduct> findById(Long id);

    /**
     * Ordered by name, as the contract says. {@code isActive} is not part of
     * the ordering: a retired product sorts among the live ones so the
     * catalogue reads as one list rather than two.
     */
    List<ObProduct> findAllByOrderByNameAsc();

    List<ObProduct> findAllByIsActiveOrderByNameAsc(boolean isActive);

    /**
     * Case-insensitive by the column's own collation, so this matches what the
     * unique index would refuse rather than a narrower thing.
     */
    Optional<ObProduct> findByCode(String code);

    /**
     * {@code hasActiveTemplate} for the whole page in one query.
     *
     * <p>The contract makes this field the OB-04 picker's gate — "a product
     * with no journey template cannot be bought" — so the list needs it for
     * every row. Per-row it would be one query per product; this is one query
     * for the page, which is the difference between a catalogue screen and a
     * catalogue screen that gets slower as the catalogue grows.
     */
    @Query("""
            select t.productId from ObJourneyTemplate t
            where t.isActive = true and t.productId in :productIds
            """)
    List<Long> findProductIdsWithAnActiveTemplate(@Param("productIds") List<Long> productIds);

    /**
     * {@code totalTatDays} for the whole page in one query — the Σ of the active
     * template's step TATs, in working days, which the contract calls what a
     * journey for this product <em>costs</em>.
     *
     * <p>Batched for the reason
     * {@link #findProductIdsWithAnActiveTemplate(List)} gives, and joined on the
     * id rather than through an association because
     * {@code ObJourneyTemplateStep.templateId} is a plain column — the shape
     * every template-step read in this package already takes.
     *
     * <p><b>A product absent from the result is not the same as zero.</b> An
     * active template with no steps yet sums nothing and produces no row, and
     * that product's total is {@code 0}, not {@code null}; {@code null} is
     * reserved for having no active template at all. The service decides which
     * it is from {@link #findProductIdsWithAnActiveTemplate(List)}, so this
     * query never has to encode the distinction.
     */
    @Query("""
            select t.productId as productId, sum(s.tatDays) as tally
            from ObJourneyTemplate t, ObJourneyTemplateStep s
            where s.templateId = t.id
              and t.isActive = true
              and t.productId in :productIds
            group by t.productId
            """)
    List<Tally> sumActiveTemplateTatDays(@Param("productIds") List<Long> productIds);

    /**
     * {@code journeyCount} for the whole page in one query — journeys
     * instantiated from each product, across all clients.
     *
     * <p>Every journey, not only the live ones. The contract puts this figure
     * inside the {@code ETag} "because it is what a retire decision is made
     * against", and a completed journey is exactly as much evidence that the
     * product was sold as a running one. A product nothing was ever boarded
     * against is absent from the result rather than present as zero.
     *
     * <p>Not the {@code COUNT(*)} CLAUDE.md forbids: that rule is about
     * dashboards, which are read constantly and have summary tables built for
     * them. This is one grouped count over an admin catalogue.
     */
    @Query("""
            select j.productId as productId, count(j) as tally
            from ObJourney j
            where j.productId in :productIds
            group by j.productId
            """)
    List<Tally> countJourneysByProduct(@Param("productIds") List<Long> productIds);

    /**
     * One row of a grouped count, keyed by product.
     *
     * <p>An interface projection rather than a constructor expression: both
     * queries above aggregate, {@code count} and {@code sum} disagree about
     * whether they widen to {@code Long}, and an alias-bound projection does not
     * care — which is one less thing that can only be discovered at bootstrap.
     */
    interface Tally {

        Long getProductId();

        long getTally();
    }
}
