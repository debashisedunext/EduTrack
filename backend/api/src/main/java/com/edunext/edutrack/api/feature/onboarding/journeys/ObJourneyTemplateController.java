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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
                template.isActive(), template.getSequence(), template.getDependsOnTemplateId(),
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
                request.dependsOnTemplateId(), CallerIdentityAccess.requireUserId(caller));
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(created);
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
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(draft);
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
        return ObJourneyTemplateDtos.ObJourneyTemplateResponse.of(published);
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
