package com.edunext.edutrack.api.feature.onboarding.clients;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B-102 · RFC 9457 problem documents for {@link ObClientController}, and since
 * B-103 for {@link ObContactController} too ({@code CONVENTIONS.md} §3).
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
        ObApplicationController.class})
class ObClientExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI FORBIDDEN = URI.create("https://edutrack/errors/forbidden");
    private static final URI VALIDATION_FAILED = URI.create("https://edutrack/errors/validation-failed");
    private static final URI PAN_DUPLICATE = URI.create("https://edutrack/errors/ob-client-pan-duplicate");
    private static final URI NAME_SIMILAR = URI.create("https://edutrack/errors/ob-client-name-similar");
    private static final URI NO_TEMPLATE = URI.create("https://edutrack/errors/ob-product-no-template");
    private static final URI LIVE_NOT_EARNED = URI.create("https://edutrack/errors/ob-client-live-not-earned");
    private static final URI PORTAL_LOGIN_UNAVAILABLE =
            URI.create("https://edutrack/errors/ob-client-portal-login-unavailable");
    private static final URI CONTACT_EMAIL_DUPLICATE =
            URI.create("https://edutrack/errors/ob-contact-email-duplicate");
    private static final URI CONTACT_PRIMARY_REQUIRED =
            URI.create("https://edutrack/errors/ob-contact-primary-required");
    private static final URI APPLICATION_DUPLICATE_PRODUCT =
            URI.create("https://edutrack/errors/ob-application-duplicate-product");
    private static final URI APPLICATION_PRODUCT_IMMUTABLE =
            URI.create("https://edutrack/errors/ob-application-product-immutable");

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
     * 409 — {@code createPortalLogin} arrived before B-126 exists.
     *
     * <p>A status the contract already declares for this operation, with a
     * {@code type} it does not yet name; the alternative was an undeclared 501.
     * <b>Deleted by B-126</b>, along with the exception and the branch that
     * throws it.
     */
    @ExceptionHandler(PortalLoginUnavailableException.class)
    ResponseEntity<ProblemDetail> handlePortalLogin(PortalLoginUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(PORTAL_LOGIN_UNAVAILABLE);
        problem.setTitle("Client portal logins are not available yet");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("errors",
                Map.of("createPortalLogin", new String[]{e.getMessage()}));
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
