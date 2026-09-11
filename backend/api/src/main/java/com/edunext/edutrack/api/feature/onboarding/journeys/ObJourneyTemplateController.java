package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * C-102 · OB-07 journey template designer — {@code /onboarding/journey-templates},
 * per {@code contracts/openapi.yaml}'s {@code onboarding-journeys} tag.
 *
 * <p>Sibling routes on the three nested resources —
 * {@code journey-template-steps}, {@code journey-template-step-items},
 * {@code journey-template-step-docs} — live in
 * {@link ObJourneyTemplateStepController}, {@link ObJourneyTemplateStepItemController}
 * and {@link ObJourneyTemplateStepDocController}: one controller per resource
 * root rather than one controller for all eleven routes, on
 * {@code AssignController}'s own thin-controller convention, so each class
 * stays readable against the one resource it owns.
 *
 * <h2>Auth: {@code authenticated()} only, deliberately not more</h2>
 *
 * <p>Every {@code /onboarding/**} path in the contract is drawn behind
 * {@code ModuleAccessGuard} (A-111, plan §2.1) answering {@code 404} to a
 * caller without {@code ONBOARDING} in their {@code modules} claim, and every
 * OB Admin route behind a role check on top of that. A-111's guard class
 * exists now, but its own javadoc says plainly that nothing calls it yet —
 * "there are no {@code /api/v1/onboarding/**} handlers to guard until B and C
 * build them, so a gate wired into the chain today would be a filter with
 * nothing behind it" — and wiring it into {@code SecurityConfig} is a
 * separate, later task. {@code SecurityConfig}'s own javadoc calls the
 * interim state out as the right default anyway: "a half-built authorisation
 * rule is worse than an absent one, because it reads as covered." So these
 * routes fall to {@code SecurityConfig}'s blanket {@code
 * .requestMatchers("/api/**").authenticated()} — reachable by any
 * authenticated user of either module until that wiring lands, same as
 * {@code /onboarding/products} has been since it was declared with nothing
 * behind it. Not a gap this task introduces; a gap this task declines to
 * paper over with a bespoke filter, per CLAUDE.md's "do not write your own
 * filtering as a workaround." {@code @PreAuthorize("isAuthenticated()")} says so explicitly
 * rather than leaving it implicit — {@code RouteAuthorizationTest} requires
 * every route to declare a decision, and an undeclared one reads, in the
 * source, exactly like a route nobody thought about.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyTemplateController {

    private final ObJourneyTemplateService service;

    ObJourneyTemplateController(ObJourneyTemplateService service) {
        this.service = service;
    }

    /**
     * The OB-07 catalogue's rows — a card per <em>service</em>, not per product.
     *
     * <p>The page could previously see only {@code ObProduct.activeTemplateId},
     * so it drew one card per product and a product's second service was
     * invisible even once the database held it. The design's own catalogue puts
     * "Standard SaaS Onboarding" and "Enterprise (with data migration audit)"
     * side by side under one product, with a "Show services for" filter — which
     * is what {@code productId} here is.
     *
     * <p>No {@code ETag}: this is a list, and {@code CONVENTIONS.md} §5 puts
     * tags on detail reads. The per-template tag the reorder writes need still
     * comes from {@code getObJourneyTemplate}.
     */
    @GetMapping(value = "/journey-templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObJourneyTemplates",
            summary = "Every Module Service, or one product's (OB-07)",
            description = """
                    Every version of every service, newest first within each — the \
                    catalogue draws the head of each chain and an admin reviewing \
                    history wants the retired rows too, so this filters neither.""")
    ObJourneyTemplateDtos.ObJourneyTemplateListResponse list(
            @RequestParam(name = "productId", required = false) Long productId) {

        List<ObJourneyTemplate> templates = service.listTemplates(productId);
        /*
          C-124 · one grouped count for the whole page rather than one per card.
          Chain-wide, so the head row a card is drawn from reports its service's
          total and not its own version's — see journeyCountsByTemplate.

          Note this is measured over the rows this call returns, which the
          productId filter may have narrowed. That is the right scope: the
          filter narrows by product, and a service never spans two products, so
          a chain is either wholly in the result or wholly out of it.
        */
        Map<Long, Long> journeyCounts = service.journeyCountsByTemplate(templates);
        /*
          The dependency sets for the whole page in one statement, on
          journeyCounts' own reasoning one line up: a set per row read lazily
          would be a query per card, and the catalogue draws every version of
          every service.
        */
        Map<Long, List<Long>> dependsOn = service.dependsOnByTemplate(
                templates.stream().map(ObJourneyTemplate::getId).toList());

        List<ObJourneyTemplateDtos.TemplateSummary> rows = templates.stream()
                .map(t -> new ObJourneyTemplateDtos.TemplateSummary(
                        t.getId(), t.getProductId(), t.getName(), t.getVersion(), t.isActive(),
                        t.getSequence(), dependsOn.getOrDefault(t.getId(), List.of()),
                        t.getPublishedAt(),
                        service.stepCount(t.getId()), service.totalTatDays(t.getId()),
                        journeyCounts.getOrDefault(t.getId(), 0L)))
                .toList();
        return new ObJourneyTemplateDtos.ObJourneyTemplateListResponse(rows);
    }

    @GetMapping(value = "/journey-templates/{templateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObJourneyTemplate",
            summary = "One template version, full detail (OB-07)",
            description = """
                    Steps, each with its items and docs nested, plus the computed \
                    `parallelGroups` layering — layer 0 first, each entry a list of step \
                    ids that could all be in progress at once. Nothing here is stored \
                    beyond `dependsOnStepId`; the layering is recomputed on every read.

                    Also the only source of the `ETag` `PUT .../steps/order` requires as \
                    `If-Match` — CONVENTIONS.md §5's rule that a precondition needs a read \
                    to draw its tag from, or the write it guards is uncallable.""")
    ResponseEntity<ObJourneyTemplateDtos.TemplateDetailResponse> getDetail(@PathVariable long templateId) {
        ObJourneyTemplateDtos.TemplateDetail detail = assembleDetail(templateId);
        return ResponseEntity.ok().eTag(etagOf(detail)).body(new ObJourneyTemplateDtos.TemplateDetailResponse(detail));
    }

    private ObJourneyTemplateDtos.TemplateDetail assembleDetail(long templateId) {
        ObJourneyTemplate template = service.getTemplate(templateId);
        List<ObJourneyTemplateStep> steps = service.getSteps(templateId);

        List<ObJourneyTemplateDtos.StepDetail> stepDetails = steps.stream()
                .map(step -> ObJourneyTemplateDtos.StepDetail.of(step,
                        service.getStepItems(step.getId()).stream()
                                .map(ObJourneyTemplateDtos.StepItem::of).toList(),
                        service.getStepDocs(step.getId()).stream()
                                .map(ObJourneyTemplateDtos.StepDoc::of).toList()))
                .toList();

        List<List<Long>> parallelGroups = service.parallelGroups(templateId).stream()
                .map(group -> group.stream().map(ObJourneyTemplateStep::getId).toList())
                .toList();

        return new ObJourneyTemplateDtos.TemplateDetail(
                template.getId(), template.getProductId(), template.getName(), template.getVersion(),
                template.isActive(), template.getSequence(), service.dependsOnTemplateIds(templateId),
                template.getPublishedBy(), template.getPublishedAt(), stepDetails, parallelGroups);
    }

    @PostMapping(value = "/journey-templates",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createObJourneyTemplate",
            summary = "A product's first draft (OB-07) — \"+ Create journey template\"",
            description = """
                    Refused with `409` once the product already has a template row, draft \
                    or published — from that point on, editing goes through `POST \
                    /onboarding/journey-templates/{templateId}/revisions`.""")
    ObJourneyTemplateDtos.ObJourneyTemplateResponse create(
            Authentication caller,
            @Valid @RequestBody ObJourneyTemplateDtos.CreateTemplateRequest request) {
        ObJourneyTemplate created = service.createTemplate(
                request.productId(), request.name(), request.sequence(),
                request.dependsOnTemplateIds(), CallerIdentityAccess.requireUserId(caller));
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(
                created, service.dependsOnTemplateIds(created.getId()));
    }

    @PostMapping(value = "/journey-templates/{templateId}/revisions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "beginObJourneyTemplateRevision",
            summary = "Clone the active version into a new editable draft (OB-07)",
            description = """
                    Steps, items and docs are cloned, `dependsOnStepId` re-pointed at the \
                    clones. The source version is never written — every journey pinned to \
                    it keeps rendering exactly what it always has. `409` if `templateId` is \
                    not the product's currently active version.""")
    ObJourneyTemplateDtos.ObJourneyTemplateResponse beginRevision(Authentication caller, @PathVariable long templateId) {
        ObJourneyTemplate draft = service.beginRevision(templateId, CallerIdentityAccess.requireUserId(caller));
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(
                draft, service.dependsOnTemplateIds(draft.getId()));
    }

    @PostMapping(value = "/journey-templates/{templateId}/publish",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "publishObJourneyTemplate",
            summary = "The draft becomes the product's active version (OB-07)",
            description = """
                    The version this one supersedes, if any, is retired in the same \
                    transaction. `422` if the draft has no steps — a published template \
                    with none could never activate a journey. `409` if this version has \
                    already been published once.""")
    ObJourneyTemplateDtos.ObJourneyTemplateResponse publish(Authentication caller, @PathVariable long templateId) {
        ObJourneyTemplate published = service.publish(templateId, CallerIdentityAccess.requireUserId(caller));
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(
                published, service.dependsOnTemplateIds(published.getId()));
    }

    @PostMapping(value = "/journey-templates/{templateId}/steps",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObJourneyTemplateStep",
            summary = "Add a service to a draft template (OB-07)",
            description = """
                    `dependsOnStepId` null means the step runs in parallel from journey \
                    start. The database only enforces that a dependency stays inside the \
                    same template; that it names an *earlier* step is C-119's job. `409` \
                    if the template has ever been published — only a draft accepts new \
                    steps.""")
    ObJourneyTemplateDtos.StepResponse addStep(
            @PathVariable long templateId,
            @Valid @RequestBody ObJourneyTemplateDtos.AddStepRequest request) {
        ObJourneyTemplateStep step = service.addStep(templateId, request.name(), request.description(),
                request.tatDays(), request.ownerUserId(), request.ownerRole(), request.backupOwnerUserId(),
                request.requiresSignoff(), request.dependsOnStepId());
        return new ObJourneyTemplateDtos.StepResponse(ObJourneyTemplateDtos.StepDetail.of(step, List.of(), List.of()));
    }

    @PutMapping(value = "/journey-templates/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "reorderObJourneyTemplateCatalogue",
            summary = "The Module Service catalogue's ↑/↓ control (C-123)",
            description = """
                    `templateIds` is every currently-active template, in the caller's desired \
                    order — not a delta, and not the same list as `/steps/order`, which reorders \
                    one template's own steps. Persisted as `sequence` 0..N-1, the order every \
                    client's journeys from here on instantiate and display in (plan §5 item 5). \
                    `400` if the list is not exactly the catalogue's current active templates.

                    No `If-Match`: this spans every active template at once rather than one row, \
                    so two admins reordering seconds apart is a whoever-saved-last-wins replace, \
                    not a lost update over a single resource a precondition would protect.""")
    void reorderCatalogue(@Valid @RequestBody ObJourneyTemplateDtos.ReorderCatalogueRequest request) {
        service.reorderCatalogue(request.templateIds());
    }

    @PutMapping(value = "/journey-templates/{templateId}/depends-on", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObJourneyTemplateDependsOn",
            summary = "The catalogue's \"Service depends on\" picker (C-123)",
            description = """
                    `dependsOnTemplateIds` is the caller's whole desired set, not a delta: \
                    every service this one waits behind, each named once. An empty list \
                    clears every dependency and the service runs unheld from journey \
                    start. Cross-product is allowed; a cycle is not — `409` naming the \
                    offending template if any chosen dependency already depends, directly \
                    or transitively, on this one, and `404` if one of them does not exist. \
                    Works on a draft or the active version alike: unlike a step's fields, \
                    this is catalogue metadata, not journey content an in-flight \
                    instantiation has pinned.

                    `If-Match` is required, not optional — `428` without one, `412` if it \
                    does not match the template's current tag. Read the tag from \
                    `GET /onboarding/journey-templates/{templateId}`.""")
    ObJourneyTemplateDtos.ObJourneyTemplateResponse updateDependsOn(
            @PathVariable long templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObJourneyTemplateDtos.UpdateDependsOnRequest request) {
        requirePrecondition(templateId, ifMatch);
        ObJourneyTemplate updated = service.updateDependsOn(templateId, request.dependsOnTemplateIds());
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(
                updated, service.dependsOnTemplateIds(templateId));
    }

    @PutMapping(value = "/journey-templates/{templateId}/steps/order",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "reorderObJourneyTemplateSteps",
            summary = "The OB-07 ↑/↓ control, applied in one call (draft only)",
            description = """
                    `stepIds` is the caller's full desired ordering, not a delta — every id \
                    the template currently has, each named exactly once. `400` if the list \
                    does not match the template's current step set exactly: an id missing, \
                    an id repeated, or an id belonging to a different template.

                    `If-Match` is required, not optional — `428` without one, `412` if it \
                    does not match the template's current tag. Read the tag from \
                    `GET /onboarding/journey-templates/{templateId}`.""")
    void reorder(@PathVariable long templateId,
                 @RequestHeader(name = "If-Match", required = false) String ifMatch,
                 @Valid @RequestBody ObJourneyTemplateDtos.ReorderStepsRequest request) {
        requirePrecondition(templateId, ifMatch);
        service.reorderSteps(templateId, request.stepIds());
    }

    /**
     * C-124 · "Edit details" on an OB-07 catalogue card — rename a Module
     * Service, or move it to another product.
     *
     * <p><b>The path names one version; the write moves the whole chain.</b> A
     * service is {@code (product_id, name)}, so renaming the single row an
     * admin happened to click would split one service into two rather than
     * rename it — {@code ObJourneyTemplateService#updateModuleService} has the
     * full argument, and the journeys boarded on the chain are re-stamped with
     * it so nothing is left resolving the old name.
     *
     * <p>{@code PATCH} rather than {@code PUT}: {@code productId} is optional
     * and omitting it means "leave it where it is", which is a merge of the
     * fields given, not a replacement of the resource.
     */
    @PatchMapping(value = "/journey-templates/{templateId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObJourneyModuleService",
            summary = "Rename a Module Service, or move it to another product (OB-07)",
            description = """
                    Applies to **every version of the service**, not the version named in \
                    the path — a service is identified by `(productId, name)`, so renaming \
                    one row would split the chain rather than rename it.

                    **A rename is always allowed**, however many clients are on the \
                    service. `ob_journeys.service_name` is denormalised at instantiation \
                    and a service-level dependency resolves by `(product, service name)`, \
                    so the rename re-stamps every journey of the chain in the same \
                    transaction and both lookups keep matching. Correcting the name of a \
                    service clients are already on is the case this route exists for.

                    **`409` on a product move once a client is on it.** A journey's \
                    `productId` is the key to that client's purchase \
                    (`fk_ob_journeys_application`), not a copy of where the catalogue files \
                    the service, so it cannot follow. The two fields are judged separately: \
                    a request that renames *and* moves is refused for the move alone. \
                    `serviceJourneyCount` on the catalogue row is how a page knows this \
                    before the admin clicks. `409` also if the target product already has a \
                    service by that name.

                    Note this is *not* gated on `publishedAt`. A name and a product are \
                    catalogue metadata rather than journey content — the same distinction \
                    `PUT .../depends-on` draws.

                    `If-Match` is required, not optional — `428` without one, `412` if it \
                    does not match. Read the tag from \
                    `GET /onboarding/journey-templates/{templateId}`.""")
    ObJourneyTemplateDtos.ObJourneyTemplateResponse updateModuleService(
            @PathVariable long templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObJourneyTemplateDtos.UpdateModuleServiceRequest request) {
        requirePrecondition(templateId, ifMatch);
        ObJourneyTemplate updated = service.updateModuleService(templateId, request.name(), request.productId());
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(
                updated, service.dependsOnTemplateIds(templateId));
    }

    /**
     * C-124 · Delete on an OB-07 catalogue card — the Module Service and every
     * version of it, with its steps, items and docs.
     *
     * <p>Chain-wide for {@code updateModuleService}'s reason: deleting the head
     * alone would leave the catalogue drawing a card for a service whose only
     * remaining rows are retired versions.
     *
     * <p>No {@code If-Match}. The precondition on the routes above protects a
     * <em>lost update</em> — two admins editing the same row, the second
     * silently overwriting the first. There is no update to lose here, and the
     * one race worth refusing is a client boarding between the read and the
     * delete, which the tag cannot see: {@code serviceJourneyCount} is
     * deliberately not part of the detail, so it does not move the tag, so a
     * required {@code If-Match} would answer {@code 412} for edits that are
     * irrelevant while still missing the one that matters. The usage check runs
     * inside the delete's own transaction, which is where that race is actually
     * settled.
     */
    @DeleteMapping(value = "/journey-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteObJourneyModuleService",
            summary = "Delete a Module Service and all its versions (OB-07)",
            description = """
                    Removes **every version** of the service named by `templateId`, with \
                    each version's steps, step items and step docs. Chain-wide for the same \
                    reason as `PATCH`: a service is `(productId, name)`, and deleting the \
                    head alone would leave a card drawing retired versions of a service \
                    nobody can reach.

                    Refused with `409` if either is true:

                    - **a client is on it** — any journey instantiated from any version, \
                      archived or not. A journey renders its steps from these rows; \
                      deleting them empties a running client's ribbon. Retire the service \
                      by publishing over it instead.
                    - **another service depends on it** — the problem document names them \
                      in `dependentServiceNames`. Clear their "Service depends on" first; \
                      `fk_ob_journey_templates_depends_on` is RESTRICT precisely so a \
                      service cannot be deleted out from under one that waits on it.

                    No `If-Match`: there is no update to lose, and the race worth refusing \
                    is a client boarding mid-delete, which the usage check inside the \
                    transaction settles and an `ETag` could not see.""")
    void deleteModuleService(@PathVariable long templateId) {
        service.deleteModuleService(templateId);
    }

    // ------------------------------------------------------------------
    // ETag / If-Match — CONVENTIONS.md §5, ClientController's own pattern
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional, on {@code steps/order}: a
     * write accepted without one would protect only the callers that
     * already thought to send it — {@code ClientController#requirePrecondition}'s
     * own reasoning, unchanged here.
     */
    private void requirePrecondition(long templateId, String ifMatch) {
        ObJourneyTemplateDtos.TemplateDetail current = assembleDetail(templateId);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the template first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This template changed since you read it. Reload and reapply the reorder.");
        }
    }

    /**
     * Content-derived, not timestamp-derived — {@code ClientController}'s own
     * reasoning: a tag that moves on every save fails an edit that conflicts
     * with nothing, and one derived from a record's own {@code hashCode()}
     * only moves when the content it protects actually changes.
     */
    private static String etagOf(ObJourneyTemplateDtos.TemplateDetail detail) {
        return Integer.toHexString(detail.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
