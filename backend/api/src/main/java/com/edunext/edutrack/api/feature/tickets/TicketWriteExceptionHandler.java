package com.edunext.edutrack.api.feature.tickets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C-067/B-028/C-071 · the write refusals, as RFC 9457.
 *
 * <p>All are <b>400 rather than 404</b>, deliberately, and the contract gives
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

    /**
     * C-071 · the project's allow-list refused the task type.
     *
     * <p>Carries {@code errors} as well as {@code field}, which the two above do
     * not. They predate C-020's finding that {@code CreateTicketPage} reads
     * {@code errors: {field: [messages]}} and ignores a bare {@code field} — so
     * their messages reach the toast and never the control. Both keys are set
     * here rather than only the working one: {@code field} is what the sibling
     * refusals on this same route already publish, and a client branching on it
     * should not have to know which of three 400s it received.
     */
    @ExceptionHandler(TaskTypeNotAllowedException.class)
    ProblemDetail taskTypeNotAllowed(TaskTypeNotAllowedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Task type not allowed on this project");
        problem.setType(URI.create("https://edutrack/errors/task-type-not-allowed"));
        problem.setProperty("field", "taskTypeId");
        problem.setProperty("errors", Map.of("taskTypeId", new String[]{exception.getMessage()}));
        return problem;
    }

    /**
     * C-071 · every field this project requires that the ticket left empty.
     *
     * <p>No single {@code field} property, deliberately, where the three
     * refusals above all have one: this failure is about a set, and naming one
     * of them would put the whole refusal on one control while the other empty
     * fields stayed unmarked. {@code errors} is the shape that can say all of
     * it, and it is the shape the form already reads.
     */
    @ExceptionHandler(MandatoryFieldsMissingException.class)
    ProblemDetail mandatoryFieldsMissing(MandatoryFieldsMissingException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Validation failed");
        problem.setType(URI.create("https://edutrack/errors/validation"));

        Map<String, String[]> errors = new LinkedHashMap<>();
        exception.missing().forEach((field, message) -> errors.put(field, new String[]{message}));
        problem.setProperty("errors", errors);
        return problem;
    }
}
