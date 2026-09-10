package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
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

    record CreateTemplateRequest(
            @NotNull Long productId,
            @NotBlank @Size(max = 160) String name,
            int sequence,
            Long dependsOnTemplateId) {
    }

    record AddStepRequest(
            @NotBlank @Size(max = 200) String name,
            String description,
            @Min(1) int tatDays,
            Long ownerUserId,
            @Size(max = 40) String ownerRole,
            Long backupOwnerUserId,
            boolean requiresSignoff,
            Long dependsOnStepId) {
    }

    /**
     * @param stepIds every step id currently on the template, in the order
     *                the caller wants them to hold — not a delta. See
     *                {@code ObJourneyTemplateService#reorderSteps}.
     */
    record ReorderStepsRequest(@NotEmpty List<@NotNull Long> stepIds) {
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

    /** C-123 · the catalogue's "Service depends on" picker. {@code null} clears the dependency. */
    record UpdateDependsOnRequest(Long dependsOnTemplateId) {
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

    record Template(
            Long id, Long productId, String name, int version, boolean isActive, int sequence,
            Long dependsOnTemplateId, Long publishedBy, Instant publishedAt) {

        static Template of(ObJourneyTemplate t) {
            return new Template(t.getId(), t.getProductId(), t.getName(), t.getVersion(), t.isActive(),
                    t.getSequence(), t.getDependsOnTemplateId(), t.getPublishedBy(), t.getPublishedAt());
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
        static ObJourneyTemplateResponse of(ObJourneyTemplate t) {
            return new ObJourneyTemplateResponse(Template.of(t));
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
     * <p>C-124 · {@code serviceJourneyCount} is the same argument for the Edit
     * and Delete controls: both are refused server-side once a client has been
     * boarded on the service, and without the figure on the row the page could
     * only find that out by letting an admin click and answering {@code 409}.
     * It is deliberately <b>chain-wide</b> rather than this version's own —
     * every row of one service carries the same total — because the card the
     * buttons sit on is the head of a chain, and a service whose v1 carries
     * three clients is in use however empty its v3 is.
     */
    record TemplateSummary(
            Long id, Long productId, String name, int version, boolean isActive, int sequence,
            Long dependsOnTemplateId, Instant publishedAt, int stepCount, int totalTatDays,
            long serviceJourneyCount) {
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

    record StepDetail(
            Long id, int sequence, String name, String description, int tatDays,
            Long ownerUserId, String ownerRole, Long backupOwnerUserId, boolean requiresSignoff,
            Long dependsOnStepId, List<StepItem> items, List<StepDoc> docs) {

        static StepDetail of(ObJourneyTemplateStep s, List<StepItem> items, List<StepDoc> docs) {
            return new StepDetail(s.getId(), s.getSequence(), s.getName(), s.getDescription(), s.getTatDays(),
                    s.getOwnerUserId(), s.getOwnerRole(), s.getBackupOwnerUserId(), s.isRequiresSignoff(),
                    s.getDependsOnStepId(), items, docs);
        }
    }

    record StepResponse(StepDetail data) {
    }

    record StepItemResponse(StepItem data) {
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
            Long dependsOnTemplateId, Long publishedBy, Instant publishedAt,
            List<StepDetail> steps, List<List<Long>> parallelGroups) {
    }

    record TemplateDetailResponse(TemplateDetail data) {
    }
}
