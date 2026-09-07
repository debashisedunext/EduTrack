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

    /** {@code ObContact} — {@code ob_client_contacts}, the SPOCs. */
    record ObContact(long id, String name, String designation, String email, String phone,
                     boolean whatsappOptIn, boolean isPrimary) {
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
         */
        boolean optedIn() {
            return Boolean.TRUE.equals(whatsappOptIn);
        }
    }

    /** {@code ObApplicationWriteRequest} — the wizard's product multi-select. */
    record ObApplicationWriteRequest(
            @NotNull Long productId,
            @Size(max = 64) String licenseType,
            @Min(1) Integer units,
            LocalDate licenseStart,
            LocalDate licenseEnd) {
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
