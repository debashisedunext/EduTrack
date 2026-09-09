package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentTooLargeException;
import com.edunext.edutrack.api.upload.UnsupportedUploadTypeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B-102 · RFC 9457 problem documents for {@link ObClientController}, and since
 * B-103, B-104, B-106 and B-107 for {@link ObContactController},
 * {@link ObApplicationController}, {@link ObRequirementController} and
 * {@link ObClientAttachmentController} too ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, on the precedent every handler in this
 * repository follows: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 *
 * <p>The {@code type} URIs are the contract's own error names. CONVENTIONS.md
 * §3 is explicit that clients branch on {@code type} and never on prose, so
 * these three strings are the API and the sentences beside them are not.
 */
@RestControllerAdvice(assignableTypes = {ObClientController.class, ObContactController.class,
        ObApplicationController.class, ObRequirementController.class,
        ObClientAttachmentController.class})
class ObClientExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI FORBIDDEN = URI.create("https://edutrack/errors/forbidden");
    private static final URI VALIDATION_FAILED = URI.create("https://edutrack/errors/validation-failed");
    private static final URI PAN_DUPLICATE = URI.create("https://edutrack/errors/ob-client-pan-duplicate");
    private static final URI NAME_SIMILAR = URI.create("https://edutrack/errors/ob-client-name-similar");
    private static final URI NO_TEMPLATE = URI.create("https://edutrack/errors/ob-product-no-template");
    private static final URI LIVE_NOT_EARNED = URI.create("https://edutrack/errors/ob-client-live-not-earned");
    private static final URI CONTACT_EMAIL_DUPLICATE =
            URI.create("https://edutrack/errors/ob-contact-email-duplicate");
    private static final URI CONTACT_PRIMARY_REQUIRED =
            URI.create("https://edutrack/errors/ob-contact-primary-required");
    private static final URI APPLICATION_DUPLICATE_PRODUCT =
            URI.create("https://edutrack/errors/ob-application-duplicate-product");
    private static final URI APPLICATION_PRODUCT_IMMUTABLE =
            URI.create("https://edutrack/errors/ob-application-product-immutable");
    /**
     * B-107 · the same two {@code type} URIs C-025 already publishes for a
     * refused upload, deliberately reused rather than given {@code ob-} twins.
     * CONVENTIONS.md §3 says clients branch on {@code type}, and "this file is
     * too big" and "this file type is not allowed" are one decision made by one
     * shared bean — {@code AttachmentTypePolicy} — whichever surface it reaches.
     * A second spelling would make an upload component that already handles the
     * ticket case handle the identical refusal twice.
     */
    private static final URI ATTACHMENT_TOO_LARGE =
            URI.create("https://edutrack/errors/attachment-too-large");
    private static final URI UNSUPPORTED_ATTACHMENT_TYPE =
            URI.create("https://edutrack/errors/unsupported-attachment-type");
    private static final URI ATTACHMENT_REMOVAL_REFUSED =
            URI.create("https://edutrack/errors/ob-attachment-removal-refused");

    /** 404 — no such client, or one out of the caller's A-112 scope. Indistinguishable, by design. */
    @ExceptionHandler(ObClientNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ObClientNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** 404 — no standing to board a client. See the exception for why this is not 403. */
    @ExceptionHandler(NotAnOnboardingClientWriterException.class)
    ResponseEntity<ProblemDetail> handleNotAWriter(NotAnOnboardingClientWriterException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** 403 — a client this caller can see and may not change. See the exception for why not 404. */
    @ExceptionHandler(ObClientReadOnlyException.class)
    ResponseEntity<ProblemDetail> handleReadOnly(ObClientReadOnlyException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(FORBIDDEN);
        problem.setTitle("Read-only in onboarding");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /**
     * 400, field-keyed so each message lands on its own input — which is also
     * what tells the four-step wizard which step to reopen.
     *
     * <p>{@code errors} maps to <b>string arrays</b>, which is what
     * {@code ValidationProblem} declares and what {@code ApiError.fieldErrors}
     * on the frontend reads. A bare string deserialises into a shape the form's
     * {@code messages[0]} silently indexes character by character.
     */
    @ExceptionHandler(ObClientValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ObClientValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_FAILED);
        problem.setTitle("The client was not saved");
        problem.setDetail(e.getMessage());

        Map<String, String[]> errors = new LinkedHashMap<>();
        e.errors().forEach((field, message) -> errors.put(field, new String[]{message}));
        problem.setProperty("errors", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 {@code ob-client-pan-duplicate} — final, and the response says so.
     *
     * <p>{@code forceable: false} is carried as a property rather than left for
     * the client to infer from the {@code type}, because the wizard shows two
     * 409s on the same screen and one of them <em>is</em> forceable. A form
     * that has to hardcode which is which will eventually offer "create anyway"
     * beside the one that cannot be.
     */
    @ExceptionHandler(DuplicateClientPanException.class)
    ResponseEntity<ProblemDetail> handleDuplicatePan(DuplicateClientPanException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(PAN_DUPLICATE);
        problem.setTitle("That PAN is already on file");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("errors", Map.of("pan", new String[]{e.getMessage()}));
        if (e.existingName() != null) {
            problem.setProperty("existingClientName", e.existingName());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 409 {@code ob-client-name-similar} — forceable with
     * {@code acknowledgeSimilarNames}.
     *
     * <p>The candidates go in {@code detail} as the contract asks, as a
     * structured property rather than only inside the sentence: the wizard
     * links each one so the boarder can look at it, which is the entire point
     * of stopping them.
     */
    @ExceptionHandler(SimilarClientNameException.class)
    ResponseEntity<ProblemDetail> handleSimilarName(SimilarClientNameException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(NAME_SIMILAR);
        problem.setTitle("A client with a similar name already exists");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", true);
        problem.setProperty("acknowledgeWith", "acknowledgeSimilarNames");
        problem.setProperty("candidates", e.candidates().stream()
                .map(candidate -> Map.of("id", candidate.id(), "name", candidate.name()))
                .toList());
        problem.setProperty("hiddenCandidateCount", e.hidden());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** 409 {@code ob-product-no-template} — a purchased product has nothing to instantiate from. */
    @ExceptionHandler(ProductWithoutTemplateException.class)
    ResponseEntity<ProblemDetail> handleNoTemplate(ProductWithoutTemplateException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(NO_TEMPLATE);
        problem.setTitle("A selected product has no published journey template");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("productIds", e.productIds());
        problem.setProperty("errors", Map.of("applications", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 404 — no such SPOC under this client, or one belonging to somebody
     * else's. Indistinguishable, by design.
     */
    @ExceptionHandler(ObContactNotFoundException.class)
    ResponseEntity<ProblemDetail> handleContactNotFound(ObContactNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 409 {@code ob-contact-email-duplicate} — final.
     *
     * <p>{@code errors} is keyed on {@code email} so the SPOC row editor lands
     * the message on the field that caused it, which is also how the panel knows
     * which of its rows to reopen.
     */
    @ExceptionHandler(DuplicateContactEmailException.class)
    ResponseEntity<ProblemDetail> handleDuplicateEmail(DuplicateContactEmailException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONTACT_EMAIL_DUPLICATE);
        problem.setTitle("That email is already a contact on this client");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("errors", Map.of("email", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 409 {@code ob-contact-primary-required} — the client would be left with no
     * primary SPOC.
     *
     * <p>{@code forceable: false} carried explicitly, on
     * {@code handleDuplicatePan}'s reasoning: the SPOC panel can show this 409
     * and a duplicate-email 409, and neither is overridable, but the wizard one
     * screen away shows a 409 that <em>is</em>. A client left to infer which is
     * which from the {@code type} will eventually offer "do it anyway" beside
     * one that cannot be.
     */
    @ExceptionHandler(LastPrimaryContactException.class)
    ResponseEntity<ProblemDetail> handleLastPrimary(LastPrimaryContactException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONTACT_PRIMARY_REQUIRED);
        problem.setTitle("A client needs one primary SPOC");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("errors", Map.of("isPrimary", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 404 — no such purchase under this client, or one belonging to somebody
     * else's. Indistinguishable, by design.
     */
    @ExceptionHandler(ObApplicationNotFoundException.class)
    ResponseEntity<ProblemDetail> handleApplicationNotFound(ObApplicationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 409 {@code ob-application-duplicate-product} — final, but not a dead end.
     *
     * <p>{@code existingApplicationId} is carried as its own property because the
     * thing the caller actually wants is one URL away: more seats or a renewed
     * licence is a {@code PATCH} of the purchase already on file. A 409 that says
     * "already bought" without saying which of five rows to open leaves the panel
     * with nothing to do but refuse.
     *
     * <p>{@code errors} is keyed on {@code productId} so the message lands on the
     * product picker, which is the field that caused it.
     */
    @ExceptionHandler(DuplicateApplicationProductException.class)
    ResponseEntity<ProblemDetail> handleDuplicateProduct(DuplicateApplicationProductException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(APPLICATION_DUPLICATE_PRODUCT);
        problem.setTitle("This client has already bought that product");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("existingApplicationId", e.existingApplicationId());
        problem.setProperty("errors", Map.of("productId", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 409 {@code ob-application-product-immutable} — a purchase cannot be
     * repointed at a different product.
     *
     * <p>{@code forceable: false} carried explicitly, on {@code
     * handleDuplicatePan}'s reasoning: this panel shows two 409s and neither is
     * overridable, while the wizard one screen away shows one that is. A client
     * left to infer which is which from the {@code type} will eventually offer
     * "do it anyway" beside one that cannot be.
     */
    @ExceptionHandler(ApplicationProductImmutableException.class)
    ResponseEntity<ProblemDetail> handleProductImmutable(ApplicationProductImmutableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(APPLICATION_PRODUCT_IMMUTABLE);
        problem.setTitle("A purchase cannot change product");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("errors", Map.of("productId", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 404 — no such requirement under this client, or one belonging to somebody
     * else's. Indistinguishable, by design.
     */
    @ExceptionHandler(ObRequirementNotFoundException.class)
    ResponseEntity<ProblemDetail> handleRequirementNotFound(ObRequirementNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 404 — no such attachment under this client, or one belonging to somebody
     * else's. Indistinguishable, by design.
     */
    @ExceptionHandler(ObClientAttachmentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleAttachmentNotFound(ObClientAttachmentNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 403 {@code ob-attachment-removal-refused} — the verb was refused, not the
     * row. See the exception for why this concedes existence where the 404 above
     * does not.
     */
    @ExceptionHandler(ObClientAttachmentRemovalNotPermittedException.class)
    ResponseEntity<ProblemDetail> handleRemovalRefused(
            ObClientAttachmentRemovalNotPermittedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(ATTACHMENT_REMOVAL_REFUSED);
        problem.setTitle("That document cannot be removed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /** 413 — over {@code edutrack.attachments.max-file-bytes}. */
    @ExceptionHandler(ObAttachmentTooLargeException.class)
    ResponseEntity<ProblemDetail> handleAttachmentTooLarge(ObAttachmentTooLargeException e) {
        return payloadTooLarge(e.getMessage());
    }

    /**
     * The same 413 for the cap the servlet container enforces before we ever see
     * the body.
     *
     * <p>{@code spring.servlet.multipart.max-file-size} refuses an oversized
     * upload during parsing, so nothing in this package runs and the default
     * rendering is a bare 500 with Spring's own message. Two different responses
     * for one rule — 413 just under the cap and 500 just over it — would send
     * whoever debugs it looking for a server fault instead of a large file.
     * {@code AttachmentExceptionHandler} carries the identical handler for the
     * ticket routes; advice is scoped by {@code assignableTypes}, so it does not
     * reach these.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleContainerLimit(MaxUploadSizeExceededException e) {
        return payloadTooLarge("That file is larger than this server accepts for one document.");
    }

    /**
     * 415 — the extension or the sniffed type is not on C-025's allow-list.
     *
     * <p>The decision is C-025's and is made by the shared allow-list behind
     * {@code UploadPipeline.vet}, which is the whole point: there is one answer to
     * "may this file be stored" and this handler only decides how it is rendered
     * on these routes. The exception is the port's neutral form of it, carrying
     * the original message — A-115 keeps the ticketing type out of this module.
     */
    @ExceptionHandler(UnsupportedUploadTypeException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedType(UnsupportedUploadTypeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setType(UNSUPPORTED_ATTACHMENT_TYPE);
        problem.setTitle("File type not allowed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
    }

    private ResponseEntity<ProblemDetail> payloadTooLarge(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setType(ATTACHMENT_TOO_LARGE);
        problem.setTitle("Document too large");
        problem.setDetail(detail);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /** 422 {@code ob-client-live-not-earned} — LIVE is derived from the journeys, never set. */
    @ExceptionHandler(LiveStatusNotEarnedException.class)
    ResponseEntity<ProblemDetail> handleLive(LiveStatusNotEarnedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(LIVE_NOT_EARNED);
        problem.setTitle("LIVE is earned, not set");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("status", new String[]{e.getMessage()}));
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
