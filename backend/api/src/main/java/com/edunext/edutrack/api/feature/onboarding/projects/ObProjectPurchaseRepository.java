package com.edunext.edutrack.api.feature.onboarding.projects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one write this package makes outside {@code ob_projects}: the purchase
 * row.
 *
 * <h2>Why a project create has to touch {@code ob_client_applications} at all</h2>
 *
 * <p>{@code PurchasedProductAccess} guards every journey read on "did this
 * client buy this product", and {@code ObJourneyInstantiationService} refuses to
 * instantiate anything without it. A project created without the row would
 * therefore create journeys the ribbon page answers 404 for — the project's own
 * detail page, unreachable from the moment it was made.
 *
 * <p>So the row is written first, in the same transaction, which is the order
 * {@code ObApplicationService} and {@code ObClientWriteService} already use and
 * for the same reason.
 *
 * <h2>Licence columns are left null, and that is the honest answer</h2>
 *
 * <p>{@code license_type}, {@code units}, {@code license_start} and
 * {@code license_end} are commercial facts the New Project form does not ask
 * for — they left the capture path with the wizard. Writing a placeholder would
 * put invented commercial terms on a row the renewals index reads. They stay
 * null and the purchases panel on the client page is still where somebody fills
 * them in.
 *
 * <h2>{@code INSERT IGNORE}, deliberately</h2>
 *
 * <p>{@code uq_ob_client_applications} makes a second row for the same pair
 * impossible, and a project create that reaches an existing purchase is the
 * ordinary case rather than an error: the client bought the product through the
 * old purchases panel, and is only now getting a project for it. Ignoring the
 * duplicate leaves whatever licence terms are already recorded untouched, which
 * an {@code ON DUPLICATE KEY UPDATE} would overwrite with the nulls above.
 */
@Repository
class ObProjectPurchaseRepository {

    private static final String INSERT = """
            INSERT IGNORE INTO ob_client_applications (ob_client_id, product_id)
            VALUES (:obClientId, :productId)
            """;

    private final JdbcClient jdbc;

    ObProjectPurchaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void recordPurchase(long obClientId, long productId) {
        jdbc.sql(INSERT)
                .param("obClientId", obClientId)
                .param("productId", productId)
                .update();
    }
}
