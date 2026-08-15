package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-022 · S-15 — the Notification Template Master, per
 * {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Everything is Admin</b>, asserting {@code master.write} — reads
 *       included, which is where this screen differs from every other master
 *       this stream has shipped.</li>
 * </ul>
 *
 * <p><b>Why the reads are closed here and open on task types, levels, roles and
 * the calendar.</b> Those four are opened to all six roles by an argument from
 * §2 row 3: every role may raise a ticket, a ticket must carry a level, a type
 * and a project, so a role that could not read those masters could not raise a
 * ticket at all. Nothing on any screen a non-Admin sees is built from this one.
 * The wording of a mail is not a field on a form — it is read by the mail engine
 * and by nothing else.
 *
 * <p>And the content is not neutral. The seeded bodies include the mail sent to
 * a <b>client contact</b>, the escalation that names the Reporting Manager as a
 * recipient, and A-044's chain-verification alarm that goes to Admins. §2 gives
 * the audit log to Admin alone on this reasoning; a catalogue of who gets told
 * what, when something goes wrong, belongs on the same side of that line. There
 * is no §2 row that says so, so this is reasoned rather than read off — flagged
 * as such, the way B-018 and B-021 flagged theirs.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule and B-015's reason: a hard-coded role check would go on refusing a
 * seventh role the Role Master had just granted the capability to.
 *
 * <p><b>403 and not 404</b>, which looks like a breach of CLAUDE.md's
 * no-existence-leak rule and is not: master data is not row-scoped, and there is
 * no per-row visibility for a 404 to protect. Recorded in
 * {@code check-conventions.py}'s {@code ROWLESS_403} with that reason, beside
 * the role and priority masters.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class NotificationTemplateController {

    private final NotificationTemplateService service;

    NotificationTemplateController(NotificationTemplateService service) {
        this.service = service;
    }

    /**
     * Every template, switched-off ones included.
     *
     * <p>No {@code includeInactive} parameter, unlike B-021's list. A retired
     * level had to be kept out of a picker two Stream C screens build unfiltered;
     * nothing outside S-15 reads this route at all, and the renderer will look up
     * one (event, channel) pair rather than the list. A parameter whose only
     * caller always passes the same value is a parameter that will be wrong the
     * first time somebody else uses it.
     */
    @GetMapping(path = "/notification-templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "listNotificationTemplates",
            summary = "Notification templates (S-15)")
    NotificationTemplateDtos.TemplateListResponse templates() {
        return new NotificationTemplateDtos.TemplateListResponse(service.list());
    }

    /**
     * The events, channels, recipients and merge tags a template is composed
     * from.
     *
     * <p>Ahead of {@code /{templateId}} for readability; Spring ranks the literal
     * segment above the template variable regardless of declaration order, so
     * {@code vocabulary} cannot be swallowed as an id.
     *
     * <p>Its own operation rather than {@code meta} on the list, following
     * {@code GET /masters/permissions} — that route is the row axis of the Role
     * Master's matrix and this is the same thing for this screen: reference data
     * with no writes, because every value exists only because code resolves it. A
     * merge tag an Admin could add would substitute nothing.
     */
    @GetMapping(path = "/notification-templates/vocabulary",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "getNotificationTemplateVocabulary",
            summary = "Events, channels, recipients and merge tags (S-15)")
    NotificationTemplateDtos.VocabularyResponse vocabulary() {
        return new NotificationTemplateDtos.VocabularyResponse(service.vocabulary());
    }

    /**
     * Exists to emit the {@code ETag} the {@code PATCH} requires as
     * {@code If-Match} — CONVENTIONS.md §5. B-011 added
     * {@code GET /users/{userId}} for this reason, B-016
     * {@code GET /projects/{projectId}}, B-020 the task type read and B-021 the
     * level read; without it the write is uncallable and the contract describes
     * an operation nobody can reach.
     */
    @GetMapping(path = "/notification-templates/{templateId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "getNotificationTemplate", summary = "One template (S-15)")
    ResponseEntity<NotificationTemplateDtos.TemplateResponse> template(
            @PathVariable long templateId) {

        return ok(service.find(templateId).orElseThrow(NotificationTemplateController::notFound));
    }

    @PostMapping(path = "/notification-templates",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createNotificationTemplate",
            summary = "Add a template for an event and channel that has none (S-15)")
    ResponseEntity<NotificationTemplateDtos.TemplateResponse> create(
            @Valid @RequestBody NotificationTemplateDtos.TemplateWrite write) {

        NotificationTemplateDtos.TemplateView created = service.create(write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new NotificationTemplateDtos.TemplateResponse(created));
    }

    /**
     * There is no {@code DELETE} mapping, and its absence is the design rather
     * than an omission — {@code NotificationTemplateService}'s javadoc carries
     * the argument.
     *
     * <p>Deleting a template does not orphan a reference the way deleting a level
     * would; it removes the <em>wording</em> for an event that goes on firing. A
     * producer keeps raising {@code HANDOFF_RECEIVED} and there is nothing to
     * render it with, so the failure appears as a mail that never arrives rather
     * than as an error anybody sees. Switching it off is {@code isActive: false}
     * through this route — and on the mails §4B.6 marks never-optional, not even
     * that.
     */
    @PatchMapping(path = "/notification-templates/{templateId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateNotificationTemplate",
            summary = "Reword a template, re-target it, or switch it off (S-15)")
    ResponseEntity<NotificationTemplateDtos.TemplateResponse> update(
            @PathVariable long templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody NotificationTemplateDtos.TemplatePatch patch) {

        requirePrecondition(templateId, ifMatch);
        return ok(service.update(templateId, patch)
                .orElseThrow(NotificationTemplateController::notFound));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is refused with 428 rather than allowed through:
     * treating a missing precondition as "no conflict" would mean the guard
     * protects only the clients that already opted in, which is the set that
     * needed it least. Same status and same reasoning as B-015's role writes,
     * B-020's task types, B-021's levels and B-023's working week.
     *
     * <p>The 404 comes first. Answering 428 for a template that does not exist
     * would send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(long templateId, String ifMatch) {
        NotificationTemplateDtos.TemplateView current =
                service.find(templateId).orElseThrow(NotificationTemplateController::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the template first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This template changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<NotificationTemplateDtos.TemplateResponse> ok(
            NotificationTemplateDtos.TemplateView view) {

        return ResponseEntity.ok().eTag(etagOf(view))
                .body(new NotificationTemplateDtos.TemplateResponse(view));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp tag moves when a save rewrites identical values, failing an
     * edit that conflicts with nothing.
     *
     * <p><b>This is a 32-bit hash and two states of one row can collide</b> — the
     * weakness B-019 found honestly on {@code ProjectSettingsController} and
     * B-021 recorded again. Here the body is the dominant component and is long,
     * so a collision needs two different bodies hashing equal rather than two
     * booleans cancelling; recorded rather than fixed on this screen alone,
     * because a stronger tag across all five controllers is a change worth making
     * together.
     */
    private static String etagOf(NotificationTemplateDtos.TemplateView view) {
        return Integer.toHexString(view.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /** 404, never 403, for a row that is not there — CLAUDE.md's rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
