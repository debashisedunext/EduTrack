package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-029 · {@code /tickets/{ticketId}/comments}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>Two of the contract's four operations. {@code editComment} and
 * {@code deleteComment} are C-033's and are left unrouted rather than stubbed —
 * see {@link CommentService}'s note on why an unserved verb should 404 rather
 * than pretend.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally; see {@code PlannedCloseDateController}'s note and the 404s that cost.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/comments")
@Tag(name = "comments")
class CommentController {

    private final CommentService service;

    CommentController(CommentService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listComments", summary = "List comments")
    CommentDtos.CommentListResponse list(
            Authentication caller,
            @PathVariable long ticketId,
            @RequestParam(required = false) Integer cycle,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        CursorPage<CommentDtos.CommentDto> page = service.list(caller, ticketId, cycle, cursor, limit);
        return new CommentDtos.CommentListResponse(page.data(), page.meta());
    }

    /*
     * A-033 · ticket.update_progress, which all six roles hold — the same
     * capability that guards an upload and a quick update, chosen for the same
     * reason.
     *
     * Commenting is how a ticket gets worked. §4B.5 calls the thread "the
     * conversational record of the ticket", and a Developer explaining a root
     * cause, a QA engineer reporting a retest and a Support agent relaying what
     * the client said are all the daily substance of the job rather than
     * decisions about it. ticket.assign or ticket.close would be the wrong shape:
     * they name authority over a ticket, and this is work on one.
     *
     * *Which* tickets is a different question and is not asked here. The caller's
     * row scope is applied inside the service by ScopedTickets, so a ticket the
     * caller may not see answers 404 (A-035) whatever capability they hold.
     *
     * Note what is deliberately NOT gated: posting a client-visible comment takes
     * no extra capability. §4B.5 makes it a per-comment toggle rather than a
     * privilege, and C-031 draws it in a colour nobody can miss — the defence is
     * that the safe option is the default and the unsafe one is deliberate, not
     * that only some roles can reach it. A Support agent is frequently the person
     * who most needs to write to the client.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createComment", summary = "Post a comment",
            description = """
                    **`isClientVisible` defaults to false, always.** An accidental leak to a client \
                    costs far more than an extra click (§16).

                    The body is sanitised server-side against PLAN.md §3.9's allow-list before it is \
                    stored — a body that reduces to nothing is a 400, not a silently empty comment. \
                    Stamped with the ticket's cycle and stage at time of writing.

                    Not yet: `@mention` fan-out (C-030) and comment attachments, which are refused \
                    rather than ignored.""")
    CommentDtos.CommentResponse create(
            Authentication caller,
            @PathVariable long ticketId,
            @Valid @RequestBody CommentDtos.CommentWriteRequest request) {

        return new CommentDtos.CommentResponse(service.create(caller, ticketId, request));
    }
}
