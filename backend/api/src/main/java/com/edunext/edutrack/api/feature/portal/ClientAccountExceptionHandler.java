package com.edunext.edutrack.api.feature.portal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-126 · RFC 9457 problem documents for {@link ClientAccountAdminController}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, on the precedent every handler in this
 * repository follows: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 *
 * <p>The {@code type} URIs are the API. CONVENTIONS.md §3 is explicit that
 * clients branch on {@code type} and never on prose, so these strings are
 * stable and the sentences beside them are not.
 */
@RestControllerAdvice(assignableTypes = ClientAccountAdminController.class)
class ClientAccountExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI ACCOUNT_EXISTS =
            URI.create("https://edutrack/errors/ob-client-account-exists");
    private static final URI NO_PRIMARY_CONTACT =
            URI.create("https://edutrack/errors/ob-client-no-primary-contact");

    @ExceptionHandler(ClientAccountNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ClientAccountNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("No portal login");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(ClientAccountAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleExists(ClientAccountAlreadyExistsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(ACCOUNT_EXISTS);
        problem.setTitle("This client already has a portal login");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * <p>{@code errors} names the field the operator has to go and fix, even
     * though it is not a field on this request — there is no body to blame. It
     * points at the contacts panel, which is where the fix is.
     */
    @ExceptionHandler(NoPrimaryContactException.class)
    ResponseEntity<ProblemDetail> handleNoPrimary(NoPrimaryContactException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NO_PRIMARY_CONTACT);
        problem.setTitle("This client has no primary SPOC");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        problem.setProperty("errors", Map.of("contacts", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
