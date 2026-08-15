package com.edunext.edutrack.api.feature.tickets.attachments;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * C-027 · {@code /attachments/limits} — §4B.4's caps, read by every upload
 * surface and written by an administrator.
 *
 * <h2>Why this is not under {@code /tickets/{ticketId}/attachments}</h2>
 *
 * <p>The limits are org-wide and identical for every ticket, so hanging them off
 * a ticket would invite exactly one question — "does this ticket have different
 * limits?" — whose answer is no, and would make the client fetch them again per
 * ticket to find that out. A top-level resource says what it is: one setting,
 * one request, cacheable by the browser for the whole session.
 *
 * <p>It also keeps {@link AttachmentController} to the two routes C-025 gave it
 * and C-026 deliberately did not add to.
 *
 * <h2>Who may read and who may write</h2>
 *
 * <p>The {@code GET} is open to any authenticated caller. It is not a leak: it
 * returns the same three numbers the upload form has always printed on screen
 * ("10 MB per file · 20 per ticket"), and every one of the six roles has an
 * upload surface, so a role that could not read them would be left validating
 * against a guess. §4B.4's whole point in publishing them is that the user
 * learns a 40 MB video will not be accepted before they wait for the upload.
 *
 * <p>The {@code PUT} asserts {@code master.write} — the permission whose seeded
 * description is "Create/edit task types, SLA, workflow, holidays and other
 * masters", granted to Admin alone. These are org-wide settings, so a
 * project-scoped grant like {@code project.manage} would be the wrong shape:
 * there is nothing here scoped to a project for it to bound.
 *
 * <p><b>There is no settings screen yet.</b> The blueprint's sidebar (§11) has a
 * Settings entry and the product has no S-number specifying it, so this task
 * ships the configurability §4B.4 asks for as an API and stops there rather than
 * inventing an admin screen nobody has specified. Recorded in
 * {@code STREAM-C-TICKETS.md}.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@link AttachmentController}'s note and the 404s that cost.
 */
@RestController
@RequestMapping("/api/v1/attachments/limits")
@Tag(name = "attachments")
class AttachmentSettingsController {

    private final AttachmentSettingsService settings;

    AttachmentSettingsController(AttachmentSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getAttachmentLimits", summary = "Attachment limits in force",
            description = """
                    Blueprint §4B.4's three caps, as configured. The client validates against these \
                    rather than against constants of its own, so a file it accepts is one the server \
                    will accept.""")
    ResponseEntity<LimitsResponse> get() {
        return ok(settings.effective());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "replaceAttachmentLimits", summary = "Set the attachment limits",
            description = """
                    All three at once. They are only meaningful together — a per-ticket total below \
                    the per-file cap makes the per-file cap unreachable — so there is no per-field \
                    write that could pass through that state.""")
    ResponseEntity<LimitsResponse> replace(
            Authentication caller,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody LimitsWrite body) {

        requirePrecondition(ifMatch);
        return ok(settings.replace(caller, body.maxFileBytes(), body.maxTicketBytes(), body.maxFiles()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional — {@code CONVENTIONS.md} §5,
     * and {@code ProjectSettingsController}'s reasoning verbatim: treating a
     * missing precondition as "no conflict" means the guard protects only the
     * clients that already opted in, which is the set that needed it least.
     *
     * <p>There is exactly one row and it is org-wide, which makes the lost
     * update <em>more</em> plausible here than on a per-project resource, not
     * less: two administrators editing attachment limits are editing the same
     * row by definition, and a {@code PUT} is a wholesale replace, so the loser
     * of the race does not merge — they are erased, having seen a success.
     */
    private void requirePrecondition(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the limits first and send back their ETag.");
        }
        if (!matches(ifMatch, etagOf(settings.effective()))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "The limits changed since you read them. Reload and reapply your edit.");
        }
    }

    private ResponseEntity<LimitsResponse> ok(AttachmentLimits limits) {
        return ResponseEntity.ok().eTag(etagOf(limits))
                .body(LimitsResponse.of(limits, settings.ceilingBytes()));
    }

    /**
     * Content-derived, like every other tag in this codebase
     * ({@code ProjectController}, {@code SlaPolicyController},
     * {@code ProjectSettingsController}) — a tag taken from
     * {@code updated_at} moves when a save rewrites identical values and then
     * fails an edit that conflicts with nothing.
     *
     * <p>Over the limits alone and <b>not</b> over {@code ceilingBytes}. The
     * ceiling is this server's own multipart configuration rather than part of
     * the resource, so a rolling deploy onto differently-configured nodes would
     * otherwise hand out tags that disagree between nodes and fail a
     * precondition nobody violated.
     *
     * <p>The 32-bit hash is weak enough that two states can share a tag, exactly
     * as {@code ProjectSettingsController} records at length. Kept the same way
     * on purpose: a stronger tag is a change worth making across all four
     * together rather than one resource at a time.
     */
    private static String etagOf(AttachmentLimits limits) {
        return Integer.toHexString(limits.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /**
     * @param ceilingBytes the most this server accepts in one upload whatever
     *                     {@code maxFileBytes} says — the servlet container's
     *                     multipart limit, which refuses a body before any of
     *                     this feature's code sees it. Read-only, and reported
     *                     so a settings form can show the bound instead of
     *                     discovering it through a 422
     */
    @Schema(name = "AttachmentLimits")
    record LimitsDto(long maxFileBytes, long maxTicketBytes, int maxFiles, long ceilingBytes) {
    }

    record LimitsResponse(LimitsDto data) {

        static LimitsResponse of(AttachmentLimits limits, long ceilingBytes) {
            return new LimitsResponse(new LimitsDto(
                    limits.maxFileBytes(), limits.maxTicketBytes(), limits.maxFiles(), ceilingBytes));
        }
    }

    /**
     * The write body.
     *
     * <p>Bean Validation carries only what is true of each field on its own —
     * present, positive. Everything relational (the ticket total covering the
     * per-file cap, the container ceiling, the upper bounds) is
     * {@link AttachmentLimits#of} and {@link AttachmentSettingsService}, because
     * those are rules about the trio and about the deployment rather than about
     * a field, and splitting them across two mechanisms is how one of them ends
     * up enforced in only one direction.
     *
     * <p>{@code ceilingBytes} is deliberately absent: it is the server's own
     * configuration reported back, and accepting it here would let a client
     * appear to raise a bound it does not control.
     */
    record LimitsWrite(
            @NotNull @Positive Long maxFileBytes,
            @NotNull @Positive Long maxTicketBytes,
            @NotNull @Positive Integer maxFiles) {
    }
}
