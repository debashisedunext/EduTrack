package com.edunext.edutrack.api.feature.clients;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B-025 · RFC 9457 problem documents for the Client Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link ClientController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and {@code RoleExceptionHandler} and
 * {@code TaskTypeExceptionHandler} repeat: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 */
@RestControllerAdvice(assignableTypes = ClientController.class)
class ClientExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");

    /** B-026 · the same {@code type} the resource and project forms use for a duplicate. */
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");

    private static final URI VALIDATION_FAILED =
            URI.create("https://edutrack/errors/validation-failed");

    /**
     * 404, and it names <b>every</b> missing id rather than the first.
     *
     * <p>A caller who selected fifty clients across two pages and hit one stale
     * id should not discover the second stale id on the retry. The ids go in a
     * {@code clientIds} property rather than only in the sentence so the grid can
     * highlight the rows it should drop from the selection.
     *
     * <p>404 rather than 400 even though the ids arrive in a body: what failed is
     * that a named row does not exist, which is the same failure
     * {@code PATCH /clients/{clientId}/status} answers 404 for. Making it a 400
     * because of where the id was carried would give one condition two statuses.
     */
    @ExceptionHandler(ClientService.UnknownClientException.class)
    ResponseEntity<ProblemDetail> handleUnknown(ClientService.UnknownClientException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Client not found");
        problem.setDetail(e.getMessage());
        problem.setProperty("clientIds", e.clientIds());
        problem.setProperty("errors", Map.of("clientIds", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * B-026 · S-33's write failures, field-keyed so each message lands on its own
     * input — which is also what tells a four-tab form which tab to open.
     *
     * <p><b>409 when a duplicate client code is the only thing wrong, 400
     * otherwise.</b> Not a cosmetic split. CONVENTIONS.md §3 says clients branch
     * on the status and the {@code type}, so a 409 that also carried a bad
     * timezone would be handled as a uniqueness conflict and the other message
     * would never be shown. {@code isDuplicateCodeOnly} is the whole rule, stated
     * on the exception rather than re-derived here.
     *
     * <p>The {@code errors} map is string-keyed to a <b>string array</b>, which
     * is what {@code ValidationProblem} declares and what
     * {@code ApiError.fieldErrors} on the frontend reads. A bare string would
     * deserialise into a shape the form's {@code messages[0]} silently indexes
     * character by character.
     */
    @ExceptionHandler(ClientWriteService.ClientValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            ClientWriteService.ClientValidationException e) {

        boolean duplicate = e.isDuplicateCodeOnly();
        HttpStatus status = duplicate ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(duplicate ? DUPLICATE : VALIDATION_FAILED);
        problem.setTitle(duplicate ? "Client code already in use" : "The client was not saved");
        problem.setDetail(e.getMessage());

        Map<String, String[]> errors = new LinkedHashMap<>();
        e.errors().forEach((field, message) -> errors.put(field, new String[]{message}));
        problem.setProperty("errors", errors);

        return ResponseEntity.status(status).body(problem);
    }

    /**
     * B-027 · a contact write's failures, field-keyed the same way, so the row
     * editor marks the input rather than showing a banner over a grid.
     *
     * <p><b>409 whenever a duplicate email is involved, where the client form
     * next door is 409 only when a duplicate code is the <em>only</em>
     * failure.</b> Not an inconsistency between two screens: that rule exists
     * because {@code ClientWriteRequest} spans four tabs and a mixed failure
     * carrying both a duplicate code and a bad timezone would be handled by a
     * status-branching client (CONVENTIONS.md §3) as a uniqueness conflict, so
     * the second message would never be shown. A contact has one queried rule,
     * so there is no mixture to describe with the wrong status.
     *
     * <p>The {@code type} is the same {@code duplicate} URI the resource, project
     * and client forms use — {@code ClientExceptionHandlerTest} pins that the two
     * 409s this advice can emit stay distinguishable by their {@code errors}
     * keys, not by their prose.
     */
    @ExceptionHandler(ClientContactService.ContactValidationException.class)
    ResponseEntity<ProblemDetail> handleContact(
            ClientContactService.ContactValidationException e) {

        HttpStatus status = e.isDuplicate() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(e.isDuplicate() ? DUPLICATE : VALIDATION_FAILED);
        problem.setTitle(e.isDuplicate()
                ? "That email is already used at this client"
                : "The contact was not saved");
        problem.setDetail(e.getMessage());

        Map<String, String[]> errors = new LinkedHashMap<>();
        e.errors().forEach((field, message) -> errors.put(field, new String[]{message}));
        problem.setProperty("errors", errors);

        return ResponseEntity.status(status).body(problem);
    }
}
