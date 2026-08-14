package com.edunext.edutrack.api.feature.masters.projects;

import com.edunext.edutrack.api.feature.masters.resources.ResourceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-017 · RFC 9457 problem documents for the Team tab ({@code CONVENTIONS.md}
 * §3).
 *
 * <p><b>Scoped to {@link ProjectMemberController}</b>, and separate from
 * {@link ProjectExceptionHandler} for the same reason that one is separate from
 * the resources' and the calendar's: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 *
 * <p>Three refusals, three distinct {@code type} URIs. {@code type} is the
 * stable part clients branch on; {@code title} and {@code detail} are prose.
 */
@RestControllerAdvice(assignableTypes = ProjectMemberController.class)
class ProjectMemberExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI ALREADY_ON_TEAM = URI.create("https://edutrack/errors/already-on-team");
    private static final URI OPEN_TICKETS = URI.create("https://edutrack/errors/open-tickets");

    /** 404 for the project id in the path — never 403, and never a leak. */
    @ExceptionHandler(ProjectMemberService.NoSuchProjectException.class)
    ResponseEntity<ProblemDetail> handleNoSuchProject(ProjectMemberService.NoSuchProjectException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("No such project.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * A field-level rule the caller can fix, in the {@code errors: {field:
     * [messages]}} shape the contract's {@code ValidationFailed} uses — so the
     * add dialog puts the message on the resource picker rather than in a
     * banner.
     */
    @ExceptionHandler(ProjectMemberService.MemberValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ProjectMemberService.MemberValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 — the request is well formed and has nothing to do.
     *
     * <p>Its own {@code type}, distinct from {@link #handleOpenTickets}, which
     * is also a 409 and is nothing like it: this one is resolved by closing the
     * dialog and that one by reassigning live work. A client that had to read
     * {@code detail} to tell them apart would be branching on the part of the
     * document that may be reworded without notice — B-012's reasoning for
     * splitting {@code manager-cycle} from {@code duplicate}.
     */
    @ExceptionHandler(ProjectMemberService.AlreadyOnTeamException.class)
    ResponseEntity<ProblemDetail> handleAlreadyOnTeam(ProjectMemberService.AlreadyOnTeamException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(ALREADY_ON_TEAM);
        problem.setTitle("Already on this team");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(
                ProjectMemberService.AlreadyOnTeamException.FIELD, new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The contract's {@code OpenTicketsProblem}, and the same shape B-014
     * emits from the resource routes.
     *
     * <p>{@code reassignUrl} is deliberately the <b>API</b> path and not a
     * browser route, which is B-014's call and worth restating because this is
     * its second emitter: a server that returned
     * {@code /tickets/bulk-reassign?fromUserId=…} would be shipping one web
     * app's routing table to every consumer of the API, including the ones with
     * no router. It is read from {@link ResourceService} rather than restated —
     * two copies of a URL that must agree is the drift {@code ProjectRoles}
     * exists to prevent one package over.
     */
    @ExceptionHandler(ProjectMemberService.OpenTicketsException.class)
    ResponseEntity<ProblemDetail> handleOpenTickets(ProjectMemberService.OpenTicketsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(OPEN_TICKETS);
        problem.setTitle("They still hold open tickets here");
        problem.setDetail(e.getMessage());
        problem.setProperty("openTicketCount", e.openTicketCount());
        problem.setProperty("reassignUrl", ResourceService.REASSIGN_URL);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The same race, caught at the index instead of the check.
     *
     * <p>{@link ProjectMemberService#add} reads the membership state before
     * writing, and that answer is stale by the time the statement runs. The
     * upsert absorbs the ordinary case — two adds for one pair, and the second
     * updates rather than colliding — so the reachable collision is narrower
     * than the project form's: a membership inserted between the state read and
     * the write. Handled anyway, because the alternative is a 500 for a request
     * whose only fault was losing a race, and {@code ProjectExceptionHandler}'s
     * equivalent is scoped to the other controller and would never see it.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> handleConstraint(DuplicateKeyException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(ALREADY_ON_TEAM);
        problem.setTitle("Already on this team");
        problem.setDetail("that resource is already on this project's team");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
