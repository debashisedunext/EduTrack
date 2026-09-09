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

    /**
     * {@code ObRequirement} — {@code ob_client_requirements}, one thing this
     * client needs before or during onboarding.
     *
     * <p><b>B-106 made these rows rather than strings.</b> Until this task the
     * detail carried {@code List<String>}: no id, so nothing could be edited,
     * removed or ticked off, and no markup, so a requirement with two clauses
     * and a link was one run-on line. Plan §9 renders OB-05's requirements as a
     * list somebody works through, and a string has nowhere to record having
     * been worked through.
     *
     * @param bodyHtml the <em>sanitised</em> markup, never what the caller sent
     * @param bodyText the projection of {@code bodyHtml}, derived on write —
     *                 {@code ticket_comments} keeps the same pair for the same
     *                 reason, so neither search nor a mail body derives it at
     *                 read time
     * @param metAt    stamped when {@code isMet} becomes true and cleared when
     *                 it becomes false. Never moved by an unrelated edit:
     *                 correcting the wording in November must not re-date a
     *                 requirement met in March
     * @param metBy    null-able even when {@code isMet} is true, and outside
     *                 {@code ck_ob_client_requirements_met} on purpose — after
     *                 B-126 a client confirms requirements through their own
     *                 portal login, and attributing that to a staff user would
     *                 be a false attribution on the field whose job is
     *                 attribution
     */
    record ObRequirement(long id, int sequence, String title, String bodyHtml, String bodyText,
                         boolean isMet, Instant metAt, UserRef metBy,
                         UserRef createdBy, Instant createdAt, Instant updatedAt) {
    }

    /** {@code ObStepDot} — one dot on OB-05's collapsed strip. */
    record ObStepDot(long id, int sequence, String name, String status, String rag, Long dependsOnStepId) {
    }

    /**
     * {@code ObJourneyStrip} — one accordion strip on OB-05.
     *
     * <p>{@code utilizedHours} (C-120) is the sum of {@code
     * ObJourneyStepRagService#hoursConsumed} across the journey's steps —
     * genuinely {@code 0.0} for a journey nothing has started, never null.
     * The type stays {@code Double} rather than {@code double} on the
     * contract's own optionality, not because this service still omits it.
     */
    record ObJourneyStrip(long id, ObProductRef product, String gateStatus, String rag,
                          int percentComplete, Long heldByJourneyId,
                          Integer totalTatDays, Double utilizedHours, List<ObStepDot> steps) {
    }

    /**
     * {@code ObClientCurrentStep} — where the client's first (primary) journey
     * stands: OB-03's caption "ERP step 4/8 · Data migration".
     *
     * @param product   the primary journey's product, so the caption can name
     *                  it; optional in the contract
     * @param name      the current service's name
     * @param stepIndex <b>1-based position</b> of this service within its
     *                  journey's step sequence — the {@code 4} of "step 4/8".
     *                  Deliberately not {@code ObStepDot.sequence}, which is the
     *                  template's ordering key and is neither promised
     *                  contiguous nor promised to start at 1
     * @param stepTotal how many services the journey has
     */
    record ObClientCurrentStep(ObProductRef product, String name, int stepIndex, int stepTotal) {
    }

    /**
     * {@code ObClient} — the OB-03 list row.
     *
     * <p>No PAN and no address, and that is the contract's own line: "identity
     * data belongs to the detail read, where the masking rule and its audit
     * apply, and a list is the wrong place to leak it a page at a time".
     *
     * @param currentStep where the primary journey stands, or null while that
     *                    journey is gate-locked, held behind a sibling, or
     *                    finished — OB-03 already has words for those states
     */
    record ObClientSummary(long id, String name, LocalDate onboardingDate, String status,
                           String rag, String gateStatus, int journeyCount, int journeysComplete,
                           ObClientCurrentStep currentStep,
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
     * @param csatScore B-119's go-live survey answer, 1–5, from the most
     *            recently answered {@code GO_LIVE} sign-off — OB-05's LIVE
     *            banner prints it as "CSAT 5/5". Null until a client answers
     *            one, which is the ordinary state: the survey is optional by
     *            construction ("a client who closes the tab has still gone
     *            live"). Detail only — OB-03's list has no banner and does not
     *            pay for the subquery.
     */
    record ObClientDetail(long id, String name, LocalDate onboardingDate, String status,
                          String rag, String gateStatus, int journeyCount, int journeysComplete,
                          ObClientCurrentStep currentStep,
                          List<ObProductRef> products, UserRef salesPerson, ObContact primaryContact,
                          Instant liveAt, boolean hasPortalLogin,
                          String description, String address, String licenseType, String pan,
                          String statusReason, List<ObContact> contacts, List<ObApplication> applications,
                          List<ObRequirement> requirements, List<ObJourneyStrip> journeys,
                          UserRef createdBy, Instant createdAt, Integer csatScore) {
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
     * {@code ObRequirementWriteRequest} — the body of {@code POST
     * .../requirements}, and of each entry in the wizard's requirements step.
     *
     * <p><b>One record for both, on {@code ObApplicationWriteRequest}'s
     * call:</b> a requirement is the same three fields whether it is raised at
     * boarding or in month three. The {@code PATCH} does <em>not</em> reuse it —
     * {@link ObRequirementUpdateRequest} explains why partial-by-field is a
     * different shape from a create, and it comes down to {@code isMet}.
     *
     * <p>{@code @Size(max = 20_000)} is §3.9's bound on what was <em>sent</em>.
     * {@code ObRequirementService} checks the sanitised result against it too,
     * because escaping makes strings longer and §3.9's sentence is about what
     * gets stored — {@code CommentSanitizer}'s class note found that the hard
     * way. There is no {@code metAt} field and there will not be one: a caller
     * who could supply it could backdate the evidence.
     */
    record ObRequirementWriteRequest(
            @Size(max = 200) String title,
            @NotBlank @Size(max = 20_000) String bodyHtml,
            Boolean isMet) {

        /**
         * Met defaults to false.
         *
         * <p>A requirement is normally raised before it is satisfied, so an
         * absent flag is "not yet" rather than "assume done" — the direction a
         * mistake is visible in. One recorded after the fact may say so.
         */
        boolean met() {
            return Boolean.TRUE.equals(isMet);
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
            @Valid List<ObRequirementWriteRequest> requirements,
            Boolean createPortalLogin,
            Boolean acknowledgeSimilarNames) {

        boolean wantsPortalLogin() {
            return Boolean.TRUE.equals(createPortalLogin);
        }

        boolean acknowledgedSimilarNames() {
            return Boolean.TRUE.equals(acknowledgeSimilarNames);
        }

        /**
         * B-106 · the wizard's requirements step is optional and may be empty.
         *
         * <p>Unlike {@code contacts} and {@code applications}, which are
         * {@code @NotEmpty} because a client without either cannot be onboarded
         * at all. A client with nothing recorded yet is an ordinary client.
         */
        List<ObRequirementWriteRequest> requirementsOrEmpty() {
            return requirements == null ? List.of() : requirements;
        }
    }
}
