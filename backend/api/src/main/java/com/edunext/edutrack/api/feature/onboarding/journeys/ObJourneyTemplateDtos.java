package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStage;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * C-102 · the wire shapes for {@code /onboarding/journey-templates} and its
 * nested step/item/doc routes, per {@code contracts/openapi.yaml}'s
 * {@code onboarding-journeys} tag.
 *
 * <p>Record types throughout, on {@code AssignDtos}'s own convention —
 * package-private, since nothing outside this controller layer constructs
 * them.
 */
final class ObJourneyTemplateDtos {

    private ObJourneyTemplateDtos() {
    }

    // ── requests ──────────────────────────────────────────────────────

    /**
     * @param dependsOnTemplateIds the services this one waits behind — a set,
     *                             not one id, since {@code V20260911_1100}.
     *                             Omitted or {@code null} is the same as an
     *                             empty list: a service that runs unheld.
     */
    record CreateTemplateRequest(
            @NotNull Long productId,
            @NotBlank @Size(max = 160) String name,
            int sequence,
            List<@NotNull Long> dependsOnTemplateIds) {
    }

    /**
     * Add a task to a stage group — OB-07's third level.
     *
     * <p><b>The name is back, and its return is the point.</b> For six hours a
     * step <em>was</em> a stage and this record had no {@code name}: the
     * vocabulary was closed and the name was copied from the master. A task is
     * not a stage — it is the work somebody writes inside one — so it is named
     * by the person writing it. What stays closed is the level above: there is
     * still no way to create a stage, which is decided on OB-15 and nowhere
     * else.
     *
     * <p>The stage group is in the path rather than in this body, because the
     * route is {@code POST /onboarding/journey-template-stages/{stageId}/tasks}
     * — a task is created inside a stage, never floating with a stage id
     * attached.
     */
    record AddTaskRequest(
            @NotBlank @Size(max = 200) String name,
            String description,
            @Min(1) int tatDays,
            Long ownerUserId,
            boolean requiresSignoff,
            Long dependsOnStepId) {
    }

    /**
     * Edit a step of a draft - see {@code ObJourneyTemplateService#updateStep}
     * for why this route exists and what it refuses.
     *
     * <p>Every field is optional and {@code null} means "say nothing about
     * this", which is what makes a PATCH a PATCH. The exceptions are the two
     * id-valued fields: null there is indistinguishable from omitting them, so
     * {@code clearDependsOn} and {@code clearOwnerUserId} are how a caller
     * asks for a step that waits for nothing or has nobody named on it.
     *
     * <p>A step with nobody named on it is not an unassigned step: it falls
     * back to the project's own implementor when a journey is created from
     * this service. That fallback is why the owning role and the backup owner
     * are no longer here — one field answers "who does this", and leave
     * coverage is a fact about a live journey rather than about a plan.
     *
     * <p><b>The name can be sent; the stage cannot.</b> A task is named by
     * whoever writes it, so correcting a typo is an ordinary edit. Moving a
     * task to another stage is not this route — see
     * {@code ObJourneyTemplateService#updateStep} for why it would need to be
     * its own.
     */
    record UpdateStepRequest(
            @Size(max = 200) String name,
            String description,
            @Min(1) Integer tatDays,
            Long ownerUserId,
            Boolean requiresSignoff,
            Long dependsOnStepId,
            boolean clearDependsOn,
            boolean clearOwnerUserId) {
    }

    /**
     * @param taskIds every task id currently in the stage group named in the
     *                path, in the order the caller wants them to hold — not a
     *                delta, and not the whole template's tasks. Reordering
     *                inside Configuration cannot disturb Data Migration; see
     *                {@code ObJourneyTemplateService#reorderTasks}.
     */
    record ReorderTasksRequest(@NotEmpty List<@NotNull Long> taskIds) {
    }

    /**
     * C-123 · the OB-07 catalogue's ↑/↓ control, spanning every active
     * template rather than one template's steps.
     *
     * @param templateIds every currently-active template's id, in the
     *                    caller's desired order — not a delta
     */
    record ReorderCatalogueRequest(@NotEmpty List<@NotNull Long> templateIds) {
    }

    /**
     * C-123 · the catalogue's "Depends on" picker, which is multi-select.
     *
     * @param dependsOnTemplateIds the caller's whole desired set, not a delta
     *                             — an empty list clears every dependency and
     *                             the service runs unheld. Not
     *                             {@code @NotEmpty} for exactly that reason:
     *                             "nothing" is a legal answer here, where on
     *                             {@code ReorderCatalogueRequest} above it
     *                             would be a caller who forgot the body.
     */
    record UpdateDependsOnRequest(@NotNull List<@NotNull Long> dependsOnTemplateIds) {
    }

    /**
     * C-124 · the catalogue card's "Edit details" — the whole Module Service,
     * every version of it, not the one row named in the path.
     *
     * @param name      the service's new name; the same name it already has is
     *                  accepted and writes nothing
     * @param productId the product it belongs to. Optional: omitted or
     *                  {@code null} leaves it where it is, so a caller renaming
     *                  a service does not have to know its product id to avoid
     *                  moving it.
     */
    record UpdateModuleServiceRequest(
            @NotBlank @Size(max = 160) String name,
            Long productId) {
    }

    record AddStepItemRequest(
            @NotBlank @Size(max = 300) String label,
            boolean mandatory) {
    }

    record AddStepDocRequest(
            @NotBlank @Size(max = 300) String label,
            boolean required) {
    }

    // ── responses ─────────────────────────────────────────────────────

    /**
     * @param dependsOnTemplateIds the services this one waits behind,
     *                             ascending. Passed in rather than read off
     *                             {@link ObJourneyTemplate}: the dependency
     *                             set lives in its own table since
     *                             {@code V20260911_1100}, so the entity alone
     *                             cannot answer for it and a {@code static of}
     *                             taking only the entity would silently
     *                             report every service as unheld.
     */
    record Template(
            Long id, Long productId, String name, int version, boolean isActive, int sequence,
            List<Long> dependsOnTemplateIds, Long publishedBy, Instant publishedAt) {

        static Template of(ObJourneyTemplate t, List<Long> dependsOnTemplateIds) {
            return new Template(t.getId(), t.getProductId(), t.getName(), t.getVersion(), t.isActive(),
                    t.getSequence(), dependsOnTemplateIds, t.getPublishedBy(), t.getPublishedAt());
        }
    }

    // Named ObJourneyTemplateResponse rather than the shorter TemplateResponse
    // deliberately: springdoc names an OpenAPI schema component from a Java
    // class's simple name, and NotificationTemplateDtos already has its own
    // TemplateResponse. Two DTOs sharing a simple name collide in the served
    // component registry — one silently overwrites the other — which is
    // invisible in either feature's own code and only shows up as the wrong
    // fields on someone else's endpoint. ContractConformanceTest caught this
    // exact collision.
    record ObJourneyTemplateResponse(Template data) {
        static ObJourneyTemplateResponse of(ObJourneyTemplate t, List<Long> dependsOnTemplateIds) {
            return new ObJourneyTemplateResponse(Template.of(t, dependsOnTemplateIds));
        }
    }

    /**
     * Every service a product sells, not only the active one.
     *
     * <p>The OB-07 catalogue draws a card per <em>service</em> — the design's
     * own has "Standard SaaS Onboarding" and "Enterprise (with data migration
     * audit)" side by side under one product. Until this list existed the page
     * could only see `ObProduct.activeTemplateId`, so it drew one card per
     * product and a second service was invisible even once the database held
     * it.
     *
     * <p>{@code stepCount} and {@code totalTatDays} are on the row because the
     * card shows both and neither is derivable from the summary — the
     * alternative is the page fetching the full detail of every service on the
     * catalogue to render a chip.
     *
     * <p>C-124 · {@code serviceJourneyCount} is the same argument for Delete
     * and for the product picker inside Edit: both are refused server-side once
     * a client has been boarded on the service, and without the figure on the
     * row the page could only find that out by letting an admin click and
     * answering {@code 409}. <b>The name is not one of them</b> — a rename
     * carries its journeys with it and is always allowed — so a page that
     * disables the whole Edit form on this number is disabling too much; it is
     * the product field, not the form, that the count speaks for.
     *
     * <p>It is deliberately <b>chain-wide</b> rather than this version's own —
     * every row of one service carries the same total — because the card the
     * buttons sit on is the head of a chain, and a service whose v1 carries
     * three clients is in use however empty its v3 is.
     */
    /*
      `stages` was added for two screens at once: OB-07's "Category" column and
      the New Project form's service picker, both of which draw every service
      of a product and print the stages each one covers. It is the same
      StageDetail the full read nests, not a thinner twin — a summary-only
      stage shape would be a second thing to keep in step for the sake of
      omitting one nullable id, and the picker wants that id to group by.
    */
    record TemplateSummary(
            Long id, Long productId, String name, int version, boolean isActive, int sequence,
            List<Long> dependsOnTemplateIds, Instant publishedAt, int stepCount, int totalTatDays,
            long serviceJourneyCount, List<StageDetail> stages) {
    }

    /*
      Named ObJourneyTemplateListResponse for the reason spelled out on
      ObJourneyTemplateResponse forty lines above, which turns out to have been
      the live problem and not a hypothetical one: springdoc names an OpenAPI
      schema component from a Java class's simple name, and `TemplateListResponse`
      was the simple name of *three* records — this one,
      NotificationTemplateDtos.TemplateListResponse (B-022) and
      ObTemplateDtos.TemplateListResponse (B-113). One silently overwrote the
      others in the served component registry, so `GET /onboarding/journey-templates`
      advertised the notification-template shape: ContractConformanceTest read it
      as serving `bodyTemplate`, `channel`, `recipients` and friends while serving
      none of its own declared fields.

      The contract has always called this schema ObJourneyTemplateListResponse.
      Only the Java name was ambiguous.
    */
    record ObJourneyTemplateListResponse(List<TemplateSummary> data) {
    }

    record StepItem(Long id, int sequence, String label, boolean mandatory) {
        static StepItem of(ObJourneyTemplateStepItem i) {
            return new StepItem(i.getId(), i.getSequence(), i.getLabel(), i.isMandatory());
        }
    }

    record StepDoc(Long id, int sequence, String label, boolean required) {
        static StepDoc of(ObJourneyTemplateStepDoc d) {
            return new StepDoc(d.getId(), d.getSequence(), d.getLabel(), d.isRequired());
        }
    }

    /**
     * One stage group — the second of OB-07's four levels.
     *
     * @param implementationStageId which OB-15 stage this group is. Null on
     *        the "Ungrouped" group, which holds tasks written before
     *        {@code V20260911_1600} and belonging to no stage.
     */
    record StageDetail(Long id, Long implementationStageId, String name, int sequence) {
        static StageDetail of(ObJourneyTemplateStage g) {
            return new StageDetail(g.getId(), g.getImplementationStageId(), g.getName(), g.getSequence());
        }
    }

    /**
     * One task — the third level, and the row that carries the work.
     *
     * @param templateStageId the stage group this task sits in. Never null;
     *        the designer groups by it.
     */
    record StepDetail(
            Long id, int sequence, String name, Long templateStageId, String description, int tatDays,
            Long ownerUserId, boolean requiresSignoff,
            Long dependsOnStepId, List<StepItem> items, List<StepDoc> docs) {

        static StepDetail of(ObJourneyTemplateStep s, List<StepItem> items, List<StepDoc> docs) {
            return new StepDetail(s.getId(), s.getSequence(), s.getName(), s.getTemplateStageId(),
                    s.getDescription(), s.getTatDays(),
                    s.getOwnerUserId(), s.isRequiresSignoff(),
                    s.getDependsOnStepId(), items, docs);
        }
    }

    record StepResponse(StepDetail data) {
    }

    /**
     * B-131 · {@code backfilledJourneyCount} is how many running journeys the
     * new item landed on as well as the catalogue — {@code 0} on a draft, and
     * on a live service nobody is currently onboarding with.
     *
     * <p>Returned rather than left for the caller to work out, because an
     * admin adding an item to a service in use has no other way to tell
     * whether the edit reached the clients it was added for. The designer
     * screen says it back to them.
     */
    record StepItemResponse(StepItem data, int backfilledJourneyCount) {
    }

    record StepDocResponse(StepDoc data) {
    }

    /**
     * @param steps          every step, items and docs nested, sequence order
     * @param parallelGroups {@code ObJourneyTemplateService#parallelGroups}'
     *                       layering, as step ids — layer 0 first. Ids only,
     *                       not full step objects: everything about a step is
     *                       already in {@code steps} above, and repeating it
     *                       here would be two shapes disagreeing the moment
     *                       one is edited without the other.
     */
    record TemplateDetail(
            Long id, Long productId, String name, int version, boolean isActive, int sequence,
            List<Long> dependsOnTemplateIds, Long publishedBy, Instant publishedAt,
            List<StageDetail> stages, List<StepDetail> steps, List<List<Long>> parallelGroups) {
    }

    record TemplateDetailResponse(TemplateDetail data) {
    }
}
