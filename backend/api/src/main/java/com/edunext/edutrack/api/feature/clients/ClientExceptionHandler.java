package com.edunext.edutrack.api.feature.clients;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
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
}
