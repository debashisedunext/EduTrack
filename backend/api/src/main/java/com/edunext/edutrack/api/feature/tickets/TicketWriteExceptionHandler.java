package com.edunext.edutrack.api.feature.tickets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-067/B-028 · the two write refusals, as RFC 9457.
 *
 * <p>Both are <b>400 rather than 404</b>, deliberately, and the contract gives
 * the reason: the row-scope 404 elsewhere exists so an out-of-scope id leaks
 * nothing, but a client the caller can see and a module every role may read are
 * not secrets. What is refused is the combination. A caller who cannot tell
 * "does not exist" from "not usable on a new ticket" cannot act on either.
 *
 * <p>Scoped to this controller rather than global — {@code @RestControllerAdvice}
 * with an explicit {@code assignableTypes} — on the same reasoning
 * {@code ReopenExceptionHandler} and {@code CloseExceptionHandler} carry: an
 * advice that catches an exception type from anywhere will one day answer for a
 * route that meant something else by it.
 */
@RestControllerAdvice(assignableTypes = TicketWriteController.class)
class TicketWriteExceptionHandler {

    @ExceptionHandler(ClientNotSelectableException.class)
    ProblemDetail clientNotSelectable(ClientNotSelectableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Client not selectable");
        problem.setType(URI.create("https://edutrack/errors/client-not-selectable"));
        problem.setProperty("field", "clientId");
        return problem;
    }

    @ExceptionHandler(UnknownModuleException.class)
    ProblemDetail unknownModule(UnknownModuleException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Unknown product module");
        problem.setType(URI.create("https://edutrack/errors/unknown-module"));
        problem.setProperty("field", "moduleId");
        return problem;
    }
}
