package com.edunext.edutrack.api.feature.masters.resources;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * B-011 · RFC 9457 problem documents for the Resource Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link ResourceController}</b>, for the reason
 * {@code CalendarExceptionHandler} and {@code AuthExceptionHandler} both give:
 * a repository-wide {@code @RestControllerAdvice} is shared surface four
 * streams would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. {@code title} and
 * {@code detail} are prose and may be reworded without notice.
 */
@RestControllerAdvice(assignableTypes = ResourceController.class)
class ResourceExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI OPEN_TICKETS = URI.create("https://edutrack/errors/open-tickets");
    private static final URI MANAGER_CYCLE = URI.create("https://edutrack/errors/manager-cycle");

    /** 404, and — once A-034 lands — also "out of scope". CONVENTIONS.md §7. */
    @ExceptionHandler(ResourceWriteService.ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ResourceWriteService.ResourceNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Resource not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * A field-level rule the caller can fix, in the {@code errors: {field:
     * [messages]}} shape the contract's {@code ValidationFailed} uses — so the
     * form puts the message against the input rather than in a banner.
     */
    @ExceptionHandler(ResourceWriteService.ResourceValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ResourceWriteService.ResourceValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 rather than 400: the request is well formed and would have been
     * accepted a moment earlier.
     *
     * <p>Field-keyed for the same reason as the 400 — a duplicate username and
     * a duplicate email are fixed at different inputs, and the form should not
     * have to parse prose to work out which.
     */
    @ExceptionHandler(ResourceWriteService.DuplicateResourceException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(ResourceWriteService.DuplicateResourceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(duplicate(e.fieldErrors()));
    }

    /**
     * B-012 · a reporting cycle at any depth, including self-reference.
     *
     * <p>{@code type} is {@code manager-cycle} and not {@code duplicate} — the
     * two are both 409 and are nothing alike, and a client that has to read the
     * prose to tell them apart is reading the part of the document that is
     * allowed to change without notice.
     *
     * <p>It still carries {@code errors}, keyed by the same field name the
     * request uses, so the form treats this exactly like the 400 it replaces:
     * the message lands on the manager picker and the picker takes focus.
     */
    @ExceptionHandler(ResourceWriteService.ManagerCycleException.class)
    ResponseEntity<ProblemDetail> handleManagerCycle(ResourceWriteService.ManagerCycleException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(MANAGER_CYCLE);
        problem.setTitle("That would create a reporting cycle");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(
                ResourceWriteService.ManagerCycleException.FIELD, new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The same race, caught at the index instead of the check.
     *
     * <p>{@link ResourceWriteService} asks which fields collide before writing,
     * and that answer is stale by the time the {@code INSERT} runs: two admins
     * submitting the same username in the same millisecond both pass. One of
     * them then gets {@code uq_users_username}, and without this handler it
     * would surface as a 500 for a request whose only fault is having lost a
     * race. The check is the good error message; the index is the truth.
     *
     * <p>The constraint name is recovered from the driver's message rather than
     * the caller's body, because at this point we know a unique index fired and
     * not which value did it — and re-querying to find out would be a third
     * lookup racing the same window.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> handleConstraint(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        if (message.contains("uq_users_username")) {
            fieldErrors.put("username", "that username is already taken");
        }
        if (message.contains("uq_users_email")) {
            fieldErrors.put("email", "that email address is already registered");
        }
        if (message.contains("uq_users_emp_code")) {
            fieldErrors.put("employeeCode", "that employee code is already in use");
        }
        if (fieldErrors.isEmpty()) {
            // A unique index fired that this screen does not own — a membership
            // collision, say. Still a 409 rather than a 500: nothing is broken,
            // the row simply already exists.
            fieldErrors.put("_", "that record already exists");
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(duplicate(fieldErrors));
    }

    /**
     * 409 carrying {@code openTicketCount} and {@code reassignUrl}, matching
     * {@code PATCH /users/{userId}/status} exactly — the contract's
     * {@code OpenTicketsProblem}. Same refusal for the same reason, so the
     * screen recognises one shape and not two.
     */
    @ExceptionHandler(ResourceWriteService.OpenTicketsException.class)
    ResponseEntity<ProblemDetail> handleOpenTickets(ResourceWriteService.OpenTicketsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(OPEN_TICKETS);
        problem.setTitle("Open tickets must be reassigned first");
        problem.setDetail(e.getMessage());
        problem.setProperty("openTicketCount", e.openTicketCount());
        problem.setProperty("reassignUrl", ResourceService.REASSIGN_URL);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static ProblemDetail duplicate(Map<String, String> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Already in use");
        problem.setDetail(String.join("; ", fieldErrors.values()));

        Map<String, String[]> errors = new LinkedHashMap<>();
        fieldErrors.forEach((field, message) -> errors.put(field, new String[]{message}));
        problem.setProperty("errors", errors);
        return problem;
    }
}
