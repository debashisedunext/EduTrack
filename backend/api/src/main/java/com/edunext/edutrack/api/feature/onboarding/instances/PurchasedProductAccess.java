package com.edunext.edutrack.api.feature.onboarding.instances;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * C-103 · "one journey per purchased product" (plan §5.2) needs one fact
 * from {@code ob_client_applications} — a table OB1's client capture (A-101)
 * owns and this stream does not otherwise touch. Plain SQL through
 * {@link JdbcClient} rather than a JPA entity for a table this feature
 * package has no other reason to model, the same choice
 * {@code ReceivingRoleRepository} (C-050) made for {@code project_members}
 * for the identical reason: a one-row existence question does not need a
 * full repository over someone else's table.
 */
@Repository
class PurchasedProductAccess {

    private static final String IS_PURCHASED = """
            SELECT EXISTS (
              SELECT 1 FROM ob_client_applications
               WHERE ob_client_id = :obClientId
                 AND product_id   = :productId)
            """;

    private final JdbcClient jdbc;

    PurchasedProductAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean isPurchased(long obClientId, long productId) {
        return Boolean.TRUE.equals(jdbc.sql(IS_PURCHASED)
                .param("obClientId", obClientId)
                .param("productId", productId)
                .query(Boolean.class)
                .single());
    }
}
