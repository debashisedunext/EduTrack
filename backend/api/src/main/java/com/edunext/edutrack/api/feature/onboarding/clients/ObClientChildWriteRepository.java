package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B-102 · the three child tables the OB-04 create writes in the same
 * transaction as the client row: {@code ob_client_contacts},
 * {@code ob_client_applications} and {@code ob_client_requirements}.
 *
 * <h2>Plain SQL, and no {@code @Entity} for any of the three</h2>
 *
 * <p>Each of these tables is the subject of a later task that owns its
 * behaviour — <b>B-103</b> the SPOCs (and the {@code whatsapp_opt_in}
 * timestamp and source it still has to add), <b>B-104</b> the purchases and
 * their licence window, <b>B-106</b> the requirements and their rich text. If
 * B-102 declared entities for them, each of those tasks would arrive to find a
 * mapping written by somebody solving a different problem, and would either
 * inherit it or add a second one. Two JPA mappings of one table is the failure
 * {@code ObClient}'s own note names, and B-101 declined to declare entities for
 * exactly this reason a task earlier.
 *
 * <p>So this class writes the rows the create cannot be atomic without, in the
 * form the migration already defines, and claims nothing about how they are
 * later managed. {@code PurchasedProductAccess} (C-103) made the identical call
 * about the identical table one package over.
 *
 * <h2>Why the create writes them at all</h2>
 *
 * <p>Because the contract makes them mandatory and the reasons are not
 * administrative. A client with no contacts has no primary SPOC, and the
 * primary SPOC is where the kickoff mail, the portal password and every
 * sign-off request go — "a client without one cannot be onboarded, only
 * stored". A client with no applications has no journeys, because a journey is
 * instantiated per purchased product; boarding one would create a client with
 * nothing to onboard them through. Both are states somebody would have to
 * notice and repair by hand.
 */
@Repository
class ObClientChildWriteRepository {

    private final JdbcClient jdbc;

    ObClientChildWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The SPOCs.
     *
     * <p>{@code is_primary} is written as given; the service has already
     * checked that exactly one carries it, and
     * {@code uq_ob_client_contacts_primary} refuses a second regardless — the
     * check is the good error message, the index is what is true under a race.
     *
     * <p><b>B-103 · the consent stamp is written here, not left for the SPOC
     * panel to fill in later.</b> A contact boarded through the wizard with the
     * box ticked and no basis recorded is precisely the row that has to be
     * re-approached before a message can go out, and OB-04 is where the
     * conversation that produced the consent actually happened —
     * {@code ObClientWriteService} refuses the create rather than storing a
     * bare {@code true}. Every one of them also opens the consent journal with
     * its first event, so a consent given at boarding and withdrawn later is
     * still provable to have stood in between.
     *
     * @param callerId the boarder, recorded as who attested each consent
     * @param at       one instant for the whole create, so every contact of one
     *                 client carries the same consent timestamp — they were
     *                 given in one conversation and a per-row {@code NOW()}
     *                 would suggest otherwise
     */
    void insertContacts(long clientId, List<ObClientDtos.ObContactWriteRequest> contacts,
                        Long callerId, Instant at) {
        for (ObClientDtos.ObContactWriteRequest contact : contacts) {
            boolean optedIn = contact.optedIn();
            String source = optedIn
                    ? ObConsentSource.parse(contact.whatsappOptInSource())
                            .orElseThrow(() -> new IllegalStateException(
                                    "consent source passed validation and did not parse: "
                                            + contact.whatsappOptInSource()))
                            .name()
                    : null;

            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO ob_client_contacts
                        (ob_client_id, name, designation, email, phone,
                         whatsapp_opt_in, whatsapp_opt_in_at, whatsapp_opt_in_source,
                         whatsapp_opt_in_by, is_primary)
                    VALUES (:clientId, :name, :designation, :email, :phone,
                            :optIn, :optInAt, :optInSource, :optInBy, :primary)
                    """)
                    .param("clientId", clientId)
                    .param("name", contact.name().trim())
                    .param("designation", trimmedOrNull(contact.designation()))
                    .param("email", contact.email().trim())
                    .param("phone", trimmedOrNull(contact.phone()))
                    .param("optIn", optedIn)
                    .param("optInAt", optedIn ? Timestamp.from(at) : null)
                    .param("optInSource", source)
                    .param("optInBy", optedIn ? callerId : null)
                    .param("primary", contact.primary())
                    .update(keys);

            if (!optedIn) {
                continue;
            }
            Number contactId = keys.getKey();
            if (contactId == null) {
                throw new IllegalStateException(
                        "ob_client_contacts insert returned no generated key for client " + clientId);
            }
            jdbc.sql("""
                    INSERT INTO ob_contact_consent_events
                        (ob_client_contact_id, channel, opted_in, source, recorded_by, recorded_at)
                    VALUES (:contactId, 'WHATSAPP', 1, :source, :recordedBy, :recordedAt)
                    """)
                    .param("contactId", contactId.longValue())
                    .param("source", source)
                    .param("recordedBy", callerId)
                    .param("recordedAt", Timestamp.from(at))
                    .update();
        }
    }

    /** The purchases. A journey is instantiated per row of this table, by C-103's service. */
    void insertApplications(long clientId, List<ObClientDtos.ObApplicationWriteRequest> applications) {
        for (ObClientDtos.ObApplicationWriteRequest application : applications) {
            jdbc.sql("""
                    INSERT INTO ob_client_applications
                        (ob_client_id, product_id, license_type, units, license_start, license_end)
                    VALUES (:clientId, :productId, :licenseType, :units, :start, :end)
                    """)
                    .param("clientId", clientId)
                    .param("productId", application.productId())
                    .param("licenseType", trimmedOrNull(application.licenseType()))
                    .param("units", application.units())
                    .param("start", application.licenseStart())
                    .param("end", application.licenseEnd())
                    .update();
        }
    }

    /**
     * The requirements, numbered in the order they were entered.
     *
     * <p>{@code sequence} is that order and is the order OB-05 shows them in.
     * Blank entries are dropped rather than stored: a wizard textarea produces
     * them by accident, and an empty requirement is a row somebody has to work
     * through that says nothing.
     */
    void insertRequirements(long clientId, List<String> requirements, Long createdBy) {
        int sequence = 0;
        for (String body : requirements) {
            if (body == null || body.isBlank()) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO ob_client_requirements (ob_client_id, sequence, body, created_by)
                    VALUES (:clientId, :sequence, :body, :createdBy)
                    """)
                    .param("clientId", clientId)
                    .param("sequence", sequence++)
                    .param("body", body.trim())
                    .param("createdBy", createdBy)
                    .update();
        }
    }

    /**
     * Which of these product ids exist and are still sold.
     *
     * <p>Asked before the insert so a stale multi-select is answered with a
     * field-keyed 400 naming every bad id at once, rather than with a foreign
     * key violation naming one MySQL constraint. {@code is_active} is part of
     * the question because a retired product is out of OB-04's picker by
     * definition — buying one today would instantiate a journey from a template
     * nobody maintains.
     */
    Set<Long> sellableProductIds(Set<Long> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("SELECT id FROM ob_products WHERE id IN (:ids) AND is_active = 1")
                .param("ids", productIds)
                .query(Long.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Which of these products have a published journey template to instantiate
     * from.
     *
     * <p>{@code is_active = 1} is what {@code ObJourneyInstantiationService}
     * itself looks for, and {@code uq_ob_journey_templates_active} guarantees
     * there is at most one per product — so this asks precisely the question
     * that service will ask, one step earlier and about every product at once.
     * See {@link ProductWithoutTemplateException} for why earlier is better
     * than catching.
     */
    Set<Long> productIdsWithActiveTemplate(Set<Long> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT product_id FROM ob_journey_templates
                 WHERE product_id IN (:ids) AND is_active = 1
                """)
                .param("ids", productIds)
                .query(Long.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Which of these user ids exist and are active — the sales owner named on the record. */
    boolean isActiveUser(long userId) {
        return Boolean.TRUE.equals(jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = :id AND is_active = 1)")
                .param("id", userId)
                .query(Boolean.class)
                .single());
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
