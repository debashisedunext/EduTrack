package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * B-124 · the wire shapes for {@code /onboarding/prereq-template} and its
 * task and document routes, per {@code contracts/openapi.yaml}'s OB-14
 * operations.
 *
 * <p>Record types throughout, package-private, on
 * {@code ObJourneyTemplateDtos}' own convention.
 *
 * <p><b>Every response type name carries the {@code ObPrereqTemplate}
 * prefix rather than a shorter local one</b>, for the reason
 * {@code ObJourneyTemplateResponse} records in its own comment: springdoc
 * names an OpenAPI schema component from a Java class's simple name, so two
 * DTOs sharing one collide in the served registry and silently overwrite
 * each other. The names here match the contract's component names exactly,
 * which is also what {@code ContractConformanceTest} compares.
 */
final class ObPrereqTemplateDtos {

    private ObPrereqTemplateDtos() {
    }

    // ── requests ──────────────────────────────────────────────────────

    /**
     * The whole representation, on both the add and the edit — the contract
     * uses one schema for both. {@code isActive} defaults to true there, so
     * it is boxed here: a missing field must mean "true", where an
     * unboxed {@code boolean} would silently mean false.
     */
    record ObPrereqTemplateTaskWriteRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull @Min(1) @Max(365) Integer tatDays,
            @NotNull Boolean isMandatory,
            Boolean isActive) {

        boolean activeOrDefault() {
            return isActive == null || isActive;
        }
    }

    /**
     * @param taskIds every task id currently on the draft, in the order the
     *                caller wants them to hold — not a delta. A list missing
     *                any of them is 400: positions only mean something
     *                against the complete set.
     */
    record ObPrereqTemplateTaskOrderRequest(@NotEmpty List<@NotNull Long> taskIds) {
    }

    record ObPrereqTemplateTaskDocWriteRequest(
            @NotBlank @Size(max = 200) String label,
            @NotNull Long attachmentId) {
    }

    // ── responses ─────────────────────────────────────────────────────

    /** Duplicated per package, on {@code ObClientDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /**
     * @param fileName  from the attachment, not from this row — see
     *                  {@code label}, which is the admin's caption and is
     *                  deliberately not the file name.
     * @param sizeBytes likewise. Both are null when the attachment row has
     *                  gone, which the contract permits by not requiring
     *                  them.
     */
    record ObPrereqTemplateTaskDoc(
            Long id, Long templateTaskId, String label, Long attachmentId,
            String fileName, Long sizeBytes) {

        static ObPrereqTemplateTaskDoc of(
                com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc d,
                String fileName, Long sizeBytes) {

            return new ObPrereqTemplateTaskDoc(d.getId(), d.getTemplateTaskId(), d.getLabel(),
                    d.getAttachmentId(), fileName, sizeBytes);
        }
    }

    record ObPrereqTemplateTaskDocResponse(ObPrereqTemplateTaskDoc data) {
    }

    record ObPrereqTemplateTask(
            Long id, int sequence, String title, String description, int tatDays,
            boolean isMandatory, boolean isActive, List<ObPrereqTemplateTaskDoc> docs) {

        static ObPrereqTemplateTask of(
                com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask t,
                List<ObPrereqTemplateTaskDoc> docs) {

            return new ObPrereqTemplateTask(t.getId(), t.getSequence(), t.getTitle(),
                    t.getDescription(), t.getTatDays(), t.isMandatory(), t.isActive(), docs);
        }
    }

    record ObPrereqTemplateTaskResponse(ObPrereqTemplateTask data) {
    }

    /**
     * @param mandatoryCount how many of {@code tasks} are mandatory.
     *                       Derived rather than stored, and on the response
     *                       because OB-14 states it above the list: an Admin
     *                       adding a fifteenth mandatory task should see the
     *                       number they are making worse while they do it,
     *                       given plan §14 names a long mandatory list as
     *                       the way the gate stalls every journey.
     */
    record ObPrereqTemplate(
            int version, boolean isDraft, boolean isActive,
            Instant publishedAt, UserRef publishedBy,
            int mandatoryCount, List<ObPrereqTemplateTask> tasks) {

        static ObPrereqTemplate of(ObPrereqTemplateVersion v, UserRef publishedBy,
                                   List<ObPrereqTemplateTask> tasks) {

            int mandatory = (int) tasks.stream().filter(ObPrereqTemplateTask::isMandatory).count();
            return new ObPrereqTemplate(v.getVersion(), v.isDraft(), v.isActive(),
                    v.getPublishedAt(), publishedBy, mandatory, tasks);
        }
    }

    record ObPrereqTemplateResponse(ObPrereqTemplate data) {
    }
}
