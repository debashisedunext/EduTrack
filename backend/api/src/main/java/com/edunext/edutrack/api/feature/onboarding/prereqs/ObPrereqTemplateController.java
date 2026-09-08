package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-124 · {@code /onboarding/prereq-template} per
 * {@code contracts/openapi.yaml} — OB-14's screen, and the set B-125
 * snapshots at boarding.
 *
 * <h2>No id in these paths, and that is structural</h2>
 *
 * <p>{@code ObJourneyTemplateController} addresses a template by id because
 * journey templates are per-product. The prerequisites master is org-wide
 * and singular — plan §4 makes it the same set for every client regardless
 * of what they bought — so the resource <i>is</i> the path and a version is
 * a query parameter on it.
 *
 * <h2>Auth: {@code isAuthenticated()} here, and the module role in
 * {@code ObModuleRoleRules}</h2>
 *
 * <p>The annotation says only "authenticated" because the onboarding module
 * has its own role vocabulary — OB Admin, OB Manager, OB Viewer, OB Sales,
 * OB Step Owner, in {@code user_module_access} — which is not blueprint
 * §2's six and is not what {@code @PreAuthorize} can speak. Encoding a §2
 * platform-role restriction here would assert a rule nobody decided.
 *
 * <p><b>The module rule is applied, not merely declared.</b> A-122 moved
 * the rules into {@code ObModuleRoleRules} and {@code ObModuleRoleFilter}
 * applies them on every request: every write below is Admin's alone per
 * plan §3, and the read is every role's. Earlier onboarding controllers
 * carry a javadoc paragraph saying the guard is unwired and the rules live
 * in a test-only matrix — that was true when they were written and is not
 * true now.
 *
 * <h2>{@code Idempotency-Key} is accepted and not yet honoured</h2>
 *
 * <p>The header every onboarding route in this codebase carries with the
 * same note: the 24-hour replay store does not exist yet. It costs least
 * here of anywhere in the module — a replayed {@code revisions} call is
 * refused by the one-draft rule, and a replayed publish by there being no
 * draft left to publish.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/onboarding/prereq-template")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObPrereqTemplateController {

    private final ObPrereqTemplateService service;
    private final ObPrereqTemplateAssembler assembler;

    ObPrereqTemplateController(ObPrereqTemplateService service, ObPrereqTemplateAssembler assembler) {
        this.service = service;
        this.assembler = assembler;
    }

    /**
     * OB-14's read, defaulting to the active version.
     *
     * <p>{@code version} asks for an older one — the reason to want it is a
     * client boarded months ago whose instance pins a version nobody can
     * otherwise read.
     *
     * <p><b>Falls back to the draft when nothing is active yet, which the
     * contract's "defaults to the active version" does not quite say.</b>
     * The contract also says there is no way to ask for the draft by name,
     * because {@code beginObPrereqTemplateRevision} returns it directly —
     * true on the call itself, and not true one page reload later. Before
     * the first publish there is no active version at all, so a strict
     * reading would 404 the OB-14 editor on the very version somebody is
     * in the middle of authoring. With an active version present this is
     * unreachable, so the fallback costs nothing and only ever answers
     * where the strict reading answers nothing.
     *
     * <p><b>404 when nothing is published and no draft has been started.</b>
     * A fresh organisation has no master at all, and answering an empty
     * 200 would tell OB-14 that a master exists and is empty, which is a
     * different thing from one that has never been authored — the second
     * is what the "start a revision" affordance is for.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObPrereqTemplate",
            summary = "The prerequisites master, one version (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateResponse> get(
            @RequestParam(name = "version", required = false) Integer version,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {

        ObPrereqTemplateVersion found = (version == null
                ? service.activeVersion().or(service::draft)
                : service.versionNumbered(version))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        version == null
                                ? "no version of the prerequisites master has been authored yet"
                                : "version " + version + " of the prerequisites master does not exist"));

        ObPrereqTemplateDtos.ObPrereqTemplate body = assembler.template(found);
        String etag = etagOf(body);
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .body(new ObPrereqTemplateDtos.ObPrereqTemplateResponse(body));
    }

    /**
     * {@code beginObPrereqTemplateRevision} — clone the active version into
     * an editable draft, or open the very first one.
     */
    @PostMapping(path = "/revisions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "beginObPrereqTemplateRevision",
            summary = "Clone the active version into an editable draft (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateResponse> beginRevision(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication caller) {

        ObPrereqTemplateVersion draft = service.beginRevision(userId(caller));
        return created(draft);
    }

    /** {@code publishObPrereqTemplate} — the draft becomes the active version. */
    @PostMapping(path = "/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "publishObPrereqTemplate",
            summary = "The draft becomes the active version (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateResponse> publish(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication caller) {

        return ok(service.publish(userId(caller)));
    }

    /** {@code addObPrereqTemplateTask} — appended at the end of the draft. */
    @PostMapping(path = "/tasks",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObPrereqTemplateTask", summary = "Add a task to the draft (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateTaskResponse> addTask(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObPrereqTemplateDtos.ObPrereqTemplateTaskWriteRequest request) {

        var task = service.addTask(request.title(), request.description(), request.tatDays(),
                request.isMandatory(), request.activeOrDefault());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ObPrereqTemplateDtos.ObPrereqTemplateTaskResponse(assembler.task(task)));
    }

    /**
     * {@code reorderObPrereqTemplateTasks} — the whole set, in the order
     * wanted.
     *
     * <p>{@code If-Match} is required, not optional, on the same reasoning
     * {@code ObClientController} records: treating a missing precondition as
     * "no conflict" protects only the callers who already opted in, which is
     * the set that needed it least. The tag comes from
     * {@code getObPrereqTemplate} — a pairing {@code CONVENTIONS.md} §5 asks
     * be made by hand, because the tag is on the parent rather than on the
     * collection being written.
     */
    @PutMapping(path = "/tasks/order",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reorderObPrereqTemplateTasks",
            summary = "Re-sequence the draft's tasks (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateResponse> reorderTasks(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObPrereqTemplateDtos.ObPrereqTemplateTaskOrderRequest request) {

        ObPrereqTemplateVersion draft = service.draft().orElseThrow(PrereqNoDraftException::new);
        requirePrecondition(draft, ifMatch);

        service.reorderTasks(request.taskIds());
        return ok(draft);
    }

    // ── plumbing ──────────────────────────────────────────────────────

    private ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateResponse> ok(
            ObPrereqTemplateVersion version) {

        ObPrereqTemplateDtos.ObPrereqTemplate body = assembler.template(version);
        return ResponseEntity.ok()
                .eTag(etagOf(body))
                .body(new ObPrereqTemplateDtos.ObPrereqTemplateResponse(body));
    }

    private ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateResponse> created(
            ObPrereqTemplateVersion version) {

        ObPrereqTemplateDtos.ObPrereqTemplate body = assembler.template(version);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etagOf(body))
                .body(new ObPrereqTemplateDtos.ObPrereqTemplateResponse(body));
    }

    private void requirePrecondition(ObPrereqTemplateVersion draft, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the prerequisites master first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(assembler.template(draft)))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "The prerequisites master changed since you read it. Reload and reapply your edit.");
        }
    }

    /**
     * Content-derived, not from {@code updated_at} — a timestamp tag moves
     * when a save rewrites identical values, failing an edit that conflicts
     * with nothing.
     *
     * <p><b>The tasks are inside the tag</b>, which is the contract's stated
     * intent: "content-derived from the version and every task on it, so
     * adding, removing or editing a task moves it". That is what makes the
     * tag usable as the precondition for a reorder and for a task PATCH,
     * neither of which has a read of its own to take a tag from.
     *
     * <p>A 32-bit hash, and two states can collide — the same call
     * {@code ObClientController} and four master controllers already make,
     * recorded rather than fixed on one screen.
     */
    private static String etagOf(ObPrereqTemplateDtos.ObPrereqTemplate template) {
        return Integer.toHexString(template.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String header, String current) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String candidate = header.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /**
     * {@link IllegalStateException} rather than a silent fallback, on
     * {@code ObClientController}'s own reasoning: every route here sits
     * behind {@code authenticated()}, so an unreadable identity means the
     * chain accepted a token this class cannot read — a bug worth a loud
     * 500, not a version quietly published by nobody.
     */
    private static long userId(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-masters route reached with no resolvable "
                                + "caller identity"))
                .userId();
    }
}
