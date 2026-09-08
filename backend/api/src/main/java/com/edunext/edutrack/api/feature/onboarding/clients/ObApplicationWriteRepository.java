package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * B-104 · the two writes behind OB-05's purchases panel.
 *
 * <h2>SQL and no {@code @Entity}, on the decision B-102 deferred to here</h2>
 *
 * <p>{@code ObClientChildWriteRepository} declined to map {@code
 * ob_client_applications} and named B-104 as the task that would own the
 * choice — "the purchases and their licence window". This is that task, and it
 * comes out the same way {@code ObContactWriteRepository} did one task earlier,
 * for the same first reason and a different second one.
 *
 * <ul>
 *   <li><b>The read side is already SQL.</b> {@code
 *       ObClientReadRepository.applicationsOf} projects the purchase joined to
 *       its product into {@code ApplicationRow}, because OB-05 wants the product
 *       code and name inline rather than a lazy association per row. An entity
 *       here would be a second mapping of one table, which is precisely what
 *       B-102's note was avoiding.</li>
 *   <li><b>There is nothing for an entity to be useful about.</b> A purchase has
 *       no lifecycle of its own, no children and no derived state — it is five
 *       columns and a foreign key. The behaviour that makes B-104 non-trivial
 *       lives in what happens <em>around</em> the write (the journey it
 *       instantiates, the product it may not be repointed at), none of which a
 *       mapping would help with.</li>
 * </ul>
 *
 * <h2>No delete method, and its absence is the design</h2>
 *
 * <p>There is no {@code delete} here because there is no {@code DELETE} route —
 * {@link ObApplicationService} sets out why at length. The short version is that
 * {@code fk_ob_journeys_application} is {@code RESTRICT} and every purchase has
 * a journey standing on it from the moment it is made, so a delete would either
 * be refused by MySQL or would have to reach into {@code ob_journeys} and
 * destroy work this package does not own.
 */
@Repository
class ObApplicationWriteRepository {

    private final JdbcClient jdbc;

    ObApplicationWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one purchase.
     *
     * <p>The same statement {@code ObClientChildWriteRepository.insertApplications}
     * issues for the wizard, deliberately: one row shape written two ways is how
     * a column added to one path goes missing on the other. The generated key is
     * not read back — unlike a contact, nothing downstream of this insert needs
     * the purchase's id. {@code ObJourneyInstantiationService} finds the purchase
     * by {@code (ob_client_id, product_id)}, which is the pair its foreign key is
     * on and the pair that is unique.
     */
    void insert(long obClientId, ObClientDtos.ObApplicationWriteRequest application) {
        jdbc.sql("""
                INSERT INTO ob_client_applications
                    (ob_client_id, product_id, license_type, units, license_start, license_end)
                VALUES (:clientId, :productId, :licenseType, :units, :start, :end)
                """)
                .param("clientId", obClientId)
                .param("productId", application.productId())
                .param("licenseType", trimmedOrNull(application.licenseType()))
                .param("units", application.units())
                .param("start", application.licenseStart())
                .param("end", application.licenseEnd())
                .update();
    }

    /**
     * The whole representation, rewritten — every editable column moves on every
     * save, so an absent licence end is a cleared one.
     *
     * <p><b>{@code product_id} is not in the {@code SET} list</b>, and that is
     * the enforcement rather than only a convention: even if a future caller
     * slipped past {@link ObApplicationService}'s check, this statement could not
     * repoint the purchase. See {@link ApplicationProductImmutableException} for
     * what repointing would do to the journey pinned to it.
     *
     * <p>{@code license_start} and {@code license_end} are bound as {@link
     * java.time.LocalDate}, which Connector/J maps to {@code DATE} with no zone
     * involved at all. This is <em>not</em> the {@code Timestamp.from} case
     * {@code ObContactWriteRepository.Consent.atTimestamp} documents: that one is
     * a {@code DATETIME(6)} carrying an instant, where the JVM default zone gets
     * applied on the way in. A licence runs from a calendar date to a calendar
     * date in the client's own reckoning, and giving it an instant is what would
     * introduce the offset rather than what avoids one.
     */
    void update(long applicationId, ObClientDtos.ObApplicationWriteRequest application) {
        jdbc.sql("""
                UPDATE ob_client_applications
                   SET license_type = :licenseType,
                       units = :units,
                       license_start = :start,
                       license_end = :end
                 WHERE id = :id
                """)
                .param("id", applicationId)
                .param("licenseType", trimmedOrNull(application.licenseType()))
                .param("units", application.units())
                .param("start", application.licenseStart())
                .param("end", application.licenseEnd())
                .update();
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
