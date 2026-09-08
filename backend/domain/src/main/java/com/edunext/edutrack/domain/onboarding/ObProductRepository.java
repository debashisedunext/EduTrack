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
}
