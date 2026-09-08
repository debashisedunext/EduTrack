package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.common.pagination.PageMeta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * B-102 · the wire shapes for {@code /onboarding/clients}, matching
 * {@code contracts/openapi.yaml}'s {@code ObClient} family.
 *
 * <p>The contract composes {@code ObClientDetail} with {@code allOf} over
 * {@code ObClient}. Java has no such thing and a supertype would put the list
 * row's fields behind an inheritance relationship that exists for no other
 * reason, so {@link ObClientDetail} restates them. {@code ObClientContractTest}
 * is what keeps the two documents in step, rather than a comment asking the
 * next person to remember.
 */
final class ObClientDtos {

    private ObClientDtos() {
    }

    /** {@code UserRef} — duplicated per package on {@code ObEscalationDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /** {@code ObProductRef} — three fields, because it is inlined into every purchase and every journey. */
    record ObProductRef(long id, String code, String name) {
    }

    /**
     * {@code ObContact} — {@code ob_client_contacts}, the SPOCs.
     *
     * <p>B-103 widened this by three fields rather than adding a second contact
     * shape: {@code isActive}, without which OB-05 could not tell a departed
     * SPOC from a current one on a list that deliberately carries both, and the
     * two consent fields.
     *
     * @param whatsappOptInSource carried as a {@code String} rather than as
     *                            {@link ObConsentSource}, and deliberately. The
     *                            enum's job is to refuse {@code UNRECORDED} on
     *                            the way <em>in</em>; on the way out that value
     *                            has to be readable, because a SPOC whose
     *                            consent predates its capture is exactly the one
     *                            OB-05 has to show as needing re-approach. An
     *                            enum-typed field would have to carry a constant
     *                            no caller may send, which invites somebody to
     *                            open {@link ObConsentSource#parse} back up.
     * @param isPrimary           false while the contact is inactive, whatever
     *                            the stored flag says — {@code is_primary_key},
     *                            the generated column the unique index is on, is
     *                            1 only while a contact is also active
     */
    record ObContact(long id, String name, String designation, String email, String phone,
                     boolean whatsappOptIn, Instant whatsappOptInAt, String whatsappOptInSource,
                     boolean isPrimary, boolean isActive) {
    }

    /** {@code ObApplication} — one purchased product. No amount, no invoice: plan §1.2. */
    record ObApplication(long id, ObProductRef product, String licenseType, Integer units,
                         LocalDate licenseStart, LocalDate licenseEnd) {
    }

    /** {@code ObStepDot} — one dot on OB-05's collapsed strip. */
    record ObStepDot(long id, int sequence, String name, String status, String rag, Long dependsOnStepId) {
    }

    /**
     * {@code ObJourneyStrip} — one accordion strip on OB-05.
     *
     * <p>{@code utilizedHours} is <b>absent rather than zero</b>. It is a
     * roll-up over {@code ob_step_clock_events} that C-120 owns and has not
     * built; reporting {@code 0.0} would be a figure on screen that says every
     * client has consumed no time at all, which reads as data rather than as an
     * unbuilt feature. Null renders as an em dash.
     */
    record ObJourneyStrip(long id, ObProductRef product, String gateStatus, String rag,
                          int percentComplete, Long heldByJourneyId,
                          Integer totalTatDays, Double utilizedHours, List<ObStepDot> steps) {
    }

    /**
     * {@code ObClient} — the OB-03 list row.
     *
     * <p>No PAN and no address, and that is the contract's own line: "identity
     * data belongs to the detail read, where the masking rule and its audit
     * apply, and a list is the wrong place to leak it a page at a time".
     */
    record ObClientSummary(long id, String name, LocalDate onboardingDate, String status,
                           String rag, String gateStatus, int journeyCount, int journeysComplete,
                           List<ObProductRef> products, UserRef salesPerson, ObContact primaryContact,
                           Instant liveAt, boolean hasPortalLogin) {
    }

    /**
     * {@code ObClientDetail} — the OB-05 page in one document.
     *
     * @param pan masked to the last four by {@code PanService}, for every role.
     *            The unmasked value is not a field anyone can widen a query to
     *            reach — it comes from A-113's own reveal operation, which
     *            writes an audit row per call.
     */
    record ObClientDetail(long id, String name, LocalDate onboardingDate, String status,
                          String rag, String gateStatus, int journeyCount, int journeysComplete,
                          List<ObProductRef> products, UserRef salesPerson, ObContact primaryContact,
                          Instant liveAt, boolean hasPortalLogin,
                          String description, String address, String licenseType, String pan,
                          String statusReason, List<ObContact> contacts, List<ObApplication> applications,
                          List<String> requirements, List<ObJourneyStrip> journeys,
                          UserRef createdBy, Instant createdAt) {
    }

    record ObClientDetailResponse(ObClientDetail data) {
    }

    record ObClientListResponse(List<ObClientSummary> data, PageMeta meta) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** {@code ObContactWriteRequest}. Exactly one of a create's contacts carries {@code isPrimary}. */
    record ObContactWriteRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String designation,
            @NotBlank @Email @Size(max = 200) String email,
            @Size(max = 32) String phone,
            Boolean whatsappOptIn,
            @Size(max = 32) String whatsappOptInSource,
            @NotNull Boolean isPrimary) {

        boolean primary() {
            return Boolean.TRUE.equals(isPrimary);
        }

        /**
         * Consent defaults to withheld.
         *
         * <p>B-103's line: consent cannot be backfilled, and every SPOC boarded
         * without it must be re-approached before a single message can go out.
         * An absent field is therefore "not given" rather than "assume yes" —
         * the direction that is recoverable.
         *
         * <p>B-103 added {@code whatsappOptInSource} beside it and made the two
         * inseparable: a {@code true} with no basis recorded is refused with a
         * 400 rather than stored, because the basis is the half that cannot be
         * reconstructed afterwards. {@code ObClientWriteService} does the
         * checking, so the wizard names the offending contact row.
         */
        boolean optedIn() {
            return Boolean.TRUE.equals(whatsappOptIn);
        }
    }

    /**
     * {@code ObApplicationWriteRequest} — the wizard's product multi-select, and
     * since B-104 the body of both purchases-panel operations too.
     *
     * <p><b>One record for three uses, rather than an upsert twin.</b> B-103
     * needed {@code ObContactUpsertRequest} beside {@code ObContactWriteRequest}
     * because the panel's shape genuinely differs from the wizard's — it carries
     * {@code isActive}, which a create has no use for. Nothing differs here: a
     * purchase is the same five fields whether it is made at boarding or six
     * months later. A second record identical to this one would be a shape that
     * can drift from its twin for no benefit, and the contract reuses the schema
     * for the same reason.
     *
     * <p>It is <b>the whole representation, not a sparse patch</b> — an absent
     * licence end is a cleared one, on {@code ObContactUpsertRequest}'s call.
     * {@code productId} stays {@code @NotNull} on the {@code PATCH} as well, so
     * the panel echoes back the product it read; {@code ObApplicationService}
     * refuses an echo that names a <em>different</em> product rather than
     * ignoring it, because on this record the product is the identity and not a
     * field.
     */
    record ObApplicationWriteRequest(
            @NotNull Long productId,
            @Size(max = 64) String licenseType,
            @Min(1) Integer units,
            LocalDate licenseStart,
            LocalDate licenseEnd) {

        /**
         * A licence that ends before it starts — {@code
         * ck_ob_client_applications_licence_window} in Java, so the refusal is a
         * sentence rather than a constraint name.
         *
         * <p>Either date alone is fine and stays fine: an open-ended perpetual
         * licence has no end, and a start recorded before the end has been
         * negotiated is an ordinary state of a real purchase. Only the pair, and
         * only in the wrong order.
         */
        boolean hasInvertedWindow() {
            return licenseStart != null && licenseEnd != null && licenseEnd.isBefore(licenseStart);
        }
    }

    /**
     * {@code ObClientCreateRequest} — the OB-04 wizard, committing all four
     * steps in one request.
     *
     * <p>{@code pan}'s pattern is the contract's, applied to the value as typed
     * rather than to a normalised form, because Bean Validation runs before any
     * of our code does. {@code PanFormat.normalise} trims and upper-cases
     * afterwards, so a lower-case PAN is refused here rather than accepted and
     * silently corrected — which is the safer direction for the one field the
     * duplicate guard keys on.
     */
    record ObClientCreateRequest(
            @NotBlank @Size(max = 200) String name,
            String description,
            @NotNull LocalDate onboardingDate,
            @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$",
                    message = "must be five letters, four digits and a letter, upper case") String pan,
            String address,
            Long salesPersonId,
            @Size(max = 64) String licenseType,
            @NotEmpty @Valid List<ObContactWriteRequest> contacts,
            @NotEmpty @Valid List<ObApplicationWriteRequest> applications,
            List<@Size(max = 5000) String> requirements,
            Boolean createPortalLogin,
            Boolean acknowledgeSimilarNames) {

        boolean wantsPortalLogin() {
            return Boolean.TRUE.equals(createPortalLogin);
        }

        boolean acknowledgedSimilarNames() {
            return Boolean.TRUE.equals(acknowledgeSimilarNames);
        }

        List<String> requirementsOrEmpty() {
            return requirements == null ? List.of() : requirements;
        }
    }
}
