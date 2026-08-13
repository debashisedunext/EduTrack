package com.edunext.edutrack.api.feature.masters.roles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-015 · RFC 9457 problem documents for the Role Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link RoleController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on — the S-09 screen tells
 * "deactivate instead" apart from "reassign these people first" by URI, and both
 * are 409. {@code title} and {@code detail} are prose and may be reworded.
 */
@RestControllerAdvice(assignableTypes = RoleController.class)
class RoleExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/immutable-field");
    private static final URI SYSTEM_ROLE =
            URI.create("https://edutrack/errors/system-role-undeletable");
    private static final URI IN_USE = URI.create("https://edutrack/errors/role-in-use");
    private static final URI UNGRANTABLE =
            URI.create("https://edutrack/errors/ungrantable-permission");

    /**
     * 409 rather than 400: the request is well formed and would have been
     * accepted a moment earlier. Field-keyed all the same, so the S-09 create
     * form lands the message on the code input.
     */
    @ExceptionHandler(RoleService.DuplicateRoleCodeException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(RoleService.DuplicateRoleCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate role code");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("code", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(RoleService.ImmutableRoleCodeException.class)
    ResponseEntity<ProblemDetail> handleImmutable(RoleService.ImmutableRoleCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE);
        problem.setTitle("Role code cannot be changed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("code", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * {@code userCount} is 0 here, and present rather than omitted: the contract
     * gives both delete refusals one schema, and a field that appears only
     * sometimes is a field every client has to guard.
     */
    @ExceptionHandler(RoleService.SystemRoleUndeletableException.class)
    ResponseEntity<ProblemDetail> handleSystemRole(RoleService.SystemRoleUndeletableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(SYSTEM_ROLE);
        problem.setTitle("System role");
        problem.setDetail(e.getMessage());
        problem.setProperty("userCount", 0);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The count is on the problem, not only in the sentence: the screen offers
     * "show me the {@code n} resources" against the resource grid's
     * {@code ?role=} filter, and parsing a number back out of {@code detail}
     * would be matching on prose the conventions say may be reworded.
     */
    @ExceptionHandler(RoleService.RoleInUseException.class)
    ResponseEntity<ProblemDetail> handleInUse(RoleService.RoleInUseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IN_USE);
        problem.setTitle("Role still in use");
        problem.setDetail(e.getMessage());
        problem.setProperty("userCount", e.userCount());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 400: a code that does not exist is a malformed request the caller can fix.
     */
    @ExceptionHandler(RoleService.UnknownPermissionException.class)
    ResponseEntity<ProblemDetail> handleUnknown(RoleService.UnknownPermissionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("permissionCodes", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 422 and not 400: the code is real and correctly spelled, and the request
     * is refused by a rule rather than by its shape. Blueprint §2 — nobody may
     * edit ticket history or the ribbon — is the same append-only guarantee the
     * database enforces with triggers, and this is its one reachable UI edge.
     */
    @ExceptionHandler(RoleService.UngrantablePermissionException.class)
    ResponseEntity<ProblemDetail> handleUngrantable(RoleService.UngrantablePermissionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(UNGRANTABLE);
        problem.setTitle("Permission cannot be granted");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
