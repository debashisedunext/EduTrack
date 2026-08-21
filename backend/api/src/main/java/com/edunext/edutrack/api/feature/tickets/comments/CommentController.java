package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * <p>All four of the contract's operations, since C-033 added the edit and the
 * delete. Both had been declared since D-001 with nothing serving them, which is
 * the state C-028 found for {@code deleteAttachment} and named as a defect: the
 * generated client has always exported them, so a button wired to one worked
 * against the mock and did nothing in production.
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
            @PathVariable String ticketId,
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
            @PathVariable String ticketId,
            @Valid @RequestBody CommentDtos.CommentWriteRequest request) {

        return new CommentDtos.CommentResponse(service.create(caller, ticketId, request));
    }

    /*
     * C-033 · the same capability as posting, and deliberately not a narrower one.
     *
     * Editing your own comment inside five minutes is not a privilege over the
     * ticket; it is finishing the sentence you were already entitled to write.
     * Anything scarcer — ticket.assign, an author-specific authority — would be a
     * second place the author rule is decided, and the annotation cannot express
     * it in any case: whether the caller wrote *this* comment is a fact about a
     * row, which is why CommentService owns it and answers 422.
     *
     * No If-Match. CONVENTIONS.md asks for one on a PATCH, and the concurrent
     * edit it protects against cannot happen here: the only person who may edit
     * is the author, inside five minutes, and two of them racing is one person
     * with two tabs open. The contract declares no ETag on this resource for the
     * same reason, and inventing one would mean minting a tag on every comment in
     * every thread read to guard a collision that has no second party.
     */
    @PatchMapping(path = "/{commentId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @Operation(operationId = "editComment", summary = "Edit within the five-minute window",
            description = """
                    Author only, within five minutes of posting. After that the comment is locked \
                    and this returns `422`.

                    An edited comment keeps `originalBody` and renders an "edited" marker. \
                    **No role, including Admin, can silently rewrite a comment** — that is what \
                    keeps the thread admissible as evidence, and it is why PM and Admin are \
                    refused here while `deleteComment` allows them.

                    The body is re-sanitised against §3.9 and re-parsed for `@mentions`. Only \
                    somebody the previous wording did not already name is notified.""")
    CommentDtos.CommentResponse edit(
            Authentication caller,
            @PathVariable String ticketId,
            @PathVariable long commentId,
            @Valid @RequestBody CommentDtos.EditCommentRequest request) {

        return new CommentDtos.CommentResponse(service.edit(caller, ticketId, commentId, request));
    }

    /*
     * C-033 · also ticket.update_progress, and the widening to PM and Admin is
     * inside the service rather than here.
     *
     * It has to be: "the author, a PM or an Admin" is a rule about a row — who
     * wrote this particular comment — and @PreAuthorize can only see the caller.
     * Expressing half of it here (hasAnyAuthority for the supervisory case) and
     * half in the service would put the rule in two places, which is how the two
     * eventually disagree. The annotation asks the question it can answer: may
     * this caller work on tickets at all.
     */
    @DeleteMapping(path = "/{commentId}")
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteComment", summary = "Delete a comment, leaving a tombstone",
            description = """
                    The row survives with `isDeleted`; the body is cleared, `originalBody` with it. \
                    Nothing vanishes — the thread keeps "removed by X on date" in place.

                    The author, a PM or an Admin. **Every** removal leaves a tombstone, including \
                    the author's own and including one made seconds after posting: §4B.5 attaches \
                    its five minutes to editing and says of deletion only that it leaves a mark. \
                    Idempotent — deleting a tombstone answers 204.""")
    void delete(
            Authentication caller,
            @PathVariable String ticketId,
            @PathVariable long commentId) {

        service.delete(caller, ticketId, commentId);
    }
}
