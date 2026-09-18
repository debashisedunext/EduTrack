package com.edunext.edutrack.api.feature.onboarding.instances;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-104 · RFC 9457 problem documents for {@link ObJourneyStepLifecycleController}
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on {@code
 * ObJourneyTemplateExceptionHandler}'s own precedent one module over: a
 * repository-wide handler is shared surface every stream would edit, and
 * this package is Stream C's alone.
 */
@RestControllerAdvice(assignableTypes = {
        ObJourneyStepLifecycleController.class,
        // C-111's item route was missing here, and `ObJourneyStepItemController`'s
        // own javadoc said this advice was "package-scoped and therefore already
        // covers this class". `assignableTypes` is a list of types, not a package:
        // every exception from PATCH /journey-step-items/{itemId} fell through to
        // Spring's default handler and answered **500**. Observed 16 Sep 2026 —
        // answering an item on a closed service returned 500 rather than 422
        // `ob-step-terminal`, so the screen could say nothing more useful than
        // "something went wrong", and a not-found item returned 500 rather than 404.
        // Adding a controller to this package means adding it here.
        ObJourneyStepItemController.class,
})
class ObJourneyStepLifecycleExceptionHandler {

    private static final URI STEP_OWNER_REQUIRED = URI.create("https://edutrack/errors/step-owner-required");
    private static final URI INVALID_STEP_TRANSITION = URI.create("https://edutrack/errors/invalid-step-transition");
    private static final URI JOURNEY_NOT_OPEN = URI.create("https://edutrack/errors/journey-not-open");
    private static final URI COMPLETION_GATE_NOT_SATISFIED =
            URI.create("https://edutrack/errors/completion-gate-not-satisfied");
    private static final URI STEP_MODERATOR_REQUIRED = URI.create("https://edutrack/errors/step-moderator-required");
    private static final URI STEP_TERMINAL = URI.create("https://edutrack/errors/ob-step-terminal");
    private static final URI STEP_DEPENDENCY_NOT_SATISFIED =
            URI.create("https://edutrack/errors/step-dependency-not-satisfied");
    private static final URI STEP_UNDER_REVIEW = URI.create("https://edutrack/errors/ob-step-under-review");
    private static final URI STEP_ITEM_VERIFIED = URI.create("https://edutrack/errors/ob-step-item-verified");
    private static final URI STEP_REJECT_REASON_REQUIRED =
            URI.create("https://edutrack/errors/ob-step-reject-reason-required");

    /** No {@code ob_journey_steps} row for the given id. */
    @ExceptionHandler(JourneyStepNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(JourneyStepNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** C-111 · no {@code ob_journey_step_items} row for the given id. */
    @ExceptionHandler(JourneyStepItemNotFoundException.class)
    ResponseEntity<ProblemDetail> handleItemNotFound(JourneyStepItemNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 422 — {@link ObStepOwnership#mayAct} refused. Not field-keyed, on
     * {@code HandoffExceptionHandler}'s reasoning for its own 422: nothing
     * the caller sent is wrong, they are simply not this step's owner or
     * backup owner.
     */
    @ExceptionHandler(NotStepOwnerException.class)
    ResponseEntity<ProblemDetail> handleNotStepOwner(NotStepOwnerException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_OWNER_REQUIRED);
        problem.setTitle("Only the step's owner or backup owner may update it");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — the step's current status does not admit the requested action. */
    @ExceptionHandler(InvalidStepTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidTransition(InvalidStepTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(INVALID_STEP_TRANSITION);
        problem.setTitle("This step cannot make that move from its current status");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — the journey is held behind another of the client's journeys. A
     *  locked prerequisite gate no longer reaches here; it is advisory. */
    @ExceptionHandler(JourneyNotOpenException.class)
    ResponseEntity<ProblemDetail> handleJourneyNotOpen(JourneyNotOpenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(JOURNEY_NOT_OPEN);
        problem.setTitle("This journey is not open for step activity yet");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * 422 — C-106's completion gate. All three failure kinds are reported
     * on the same problem body; see the exception's own javadoc.
     */
    @ExceptionHandler(CompletionGateException.class)
    ResponseEntity<ProblemDetail> handleCompletionGateNotSatisfied(CompletionGateException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(COMPLETION_GATE_NOT_SATISFIED);
        problem.setTitle("This step is not ready to complete");
        problem.setDetail(e.getMessage());
        problem.setProperty("unansweredMandatoryItems", e.unansweredMandatoryItems());
        problem.setProperty("missingRequiredDocs", e.missingRequiredDocs());
        problem.setProperty("signoffMissing", e.signoffMissing());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /*
     * `ob-step-item-remark-required` was handled here — 422 on a task list
     * entry answered False with no reason. The rule is gone (PLAN.md §4, D-17;
     * V20260916_1520 drops the CHECK that held it), so the problem type is gone
     * with it rather than left registered for an exception nothing throws.
     * Callers still switching on it see a stored answer instead of a 422, which
     * is the intended behaviour.
     */

    /**
     * C-107 · 403 — a plain capability check, not a row-scope one. See {@link
     * NotAnOnboardingModeratorException}'s own javadoc for why this is 403
     * where {@link #handleNotStepOwner} is 422.
     */
    @ExceptionHandler(NotAnOnboardingModeratorException.class)
    ResponseEntity<ProblemDetail> handleNotModerator(NotAnOnboardingModeratorException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(STEP_MODERATOR_REQUIRED);
        problem.setTitle("Only an onboarding Manager or Admin may do this");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    /** C-107 · 422 {@code ob-step-terminal} — already `DONE` or already `SKIPPED`. */
    @ExceptionHandler(StepAlreadyTerminalException.class)
    ResponseEntity<ProblemDetail> handleTerminal(StepAlreadyTerminalException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_TERMINAL);
        problem.setTitle("This service is already closed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * 422 {@code ob-step-under-review} — a write to a task sitting with an OB
     * Manager.
     *
     * <p>Its own type rather than {@link #handleTerminal}'s, because the two
     * say opposite things to the person reading them: a terminal task is
     * finished, this one is open and waiting. See {@link
     * StepUnderReviewException}'s own javadoc.
     */
    @ExceptionHandler(StepUnderReviewException.class)
    ResponseEntity<ProblemDetail> handleUnderReview(StepUnderReviewException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_UNDER_REVIEW);
        problem.setTitle("This task is with an OB Manager for review");
        problem.setDetail(e.getMessage());
        problem.setProperty("stepId", e.stepId());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * 422 {@code ob-step-item-verified} — a write to a row an OB Manager has
     * already verified, from either person.
     *
     * <p>{@code itemId} rides as a property so a screen can mark the offending
     * row rather than showing a banner about a list.
     */
    @ExceptionHandler(StepItemAlreadyVerifiedException.class)
    ResponseEntity<ProblemDetail> handleItemVerified(StepItemAlreadyVerifiedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_ITEM_VERIFIED);
        problem.setTitle("This check-list row has been verified and is closed");
        problem.setDetail(e.getMessage());
        problem.setProperty("itemId", e.itemId());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * 422 {@code ob-step-reject-reason-required} — a rejected row left with no
     * reason, by the manager rejecting it or the implementor blanking it.
     *
     * <p>This is the message {@code ck_ob_journey_step_items_reject_reason}
     * would otherwise deliver as a 500. Not to be confused with the dropped
     * {@code ob-step-item-remark-required} above: that one was about the
     * implementor's answer and is gone for good (D-17).
     */
    @ExceptionHandler(RejectReasonRequiredException.class)
    ResponseEntity<ProblemDetail> handleRejectReasonRequired(RejectReasonRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_REJECT_REASON_REQUIRED);
        problem.setTitle("A rejected row must say why");
        problem.setDetail(e.getMessage());
        problem.setProperty("itemId", e.itemId());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * C-119 · 422 — {@code start} refused because {@code dependsOnStepId}
     * has not finished. {@code blockingStepId}/{@code blockingStepName} are
     * carried as properties, not only in the message, so a client can point
     * the caller at the blocker without parsing prose.
     */
    @ExceptionHandler(StepDependencyNotSatisfiedException.class)
    ResponseEntity<ProblemDetail> handleDependencyNotSatisfied(StepDependencyNotSatisfiedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_DEPENDENCY_NOT_SATISFIED);
        problem.setTitle("This step's dependency has not finished yet");
        problem.setDetail(e.getMessage());
        problem.setProperty("blockingStepId", e.blockingStepId());
        problem.setProperty("blockingStepName", e.blockingStepName());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
