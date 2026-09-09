package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * B-113 · OB-12's wire shapes — the module's notification wording.
 */
final class ObTemplateDtos {

    private ObTemplateDtos() {
    }

    /**
     * <p>What kind of thing happened. Deliberately <b>not</b> {@code ObCategory},
     * which is OB-13's tab vocabulary ({@code ASSIGNMENT · ESCALATION ·
     * REMINDER · UPDATE}) and answers a different question: that one is what the
     * reader is expected to do about it. An event has both, and
     * {@code SIGNOFF_OBJECTED} being {@code ObCategory.ESCALATION} and
     * {@code Category.SIGNOFF} is not a contradiction.
     */
    @Schema(name = "ObNotificationCategory")
    enum Category {
        PREREQUISITE, SERVICE, ESCALATION, SIGNOFF, ACCOUNT
    }

    /**
     * <p>{@code isMandatory} and {@code isDeliverable} are both derived and
     * neither is stored — see {@link ObTemplateService} for each rule and why a
     * column would be a second copy of it.
     */
    @Schema(name = "ObNotificationTemplate")
    record Template(
            long id,
            String eventCode,
            Category category,
            ObChannel channel,
            List<String> recipients,
            String subjectTemplate,
            String bodyTemplate,
            boolean isActive,
            boolean isMandatory,
            boolean isDeliverable
    ) {
    }

    record TemplateResponse(Template data) {
    }

    record TemplateListResponse(List<Template> data) {
    }

    /**
     * <p>{@code eventCode} and {@code channel} are absent: together they are the
     * row's identity, {@code uq_ob_notification_templates} is over them, and the
     * renderer resolves by them. Changing either is a different template.
     *
     * <p>Every field is nullable because this is a {@code PATCH} — a null means
     * "leave it", not "clear it". The one field where that is ambiguous is
     * {@code subjectTemplate}, which is genuinely nullable on an
     * {@code IN_APP} row; it is not clearable through this route, and it does
     * not need to be, because the channel it would be cleared for is immutable.
     */
    @Schema(name = "ObNotificationTemplateUpdateRequest")
    record UpdateRequest(
            @Size(max = 255)
            String subjectTemplate,

            @Size(min = 1, max = 20000)
            String bodyTemplate,

            /**
             * Never empty when present. A template with no recipients is a row
             * that looks configured and sends nothing; switching it off says so
             * instead.
             */
            @NotEmpty
            List<String> recipients,

            Boolean isActive
    ) {
    }

    /**
     * What OB-12's editor may offer, served rather than hardcoded.
     *
     * <p>{@code getNotificationTemplateVocabulary}'s argument, and it matters
     * more here: plan §7's list is still growing while OB3 is built, and a
     * client holding its own copy would silently fail to offer a new event.
     */
    @Schema(name = "ObNotificationVocabulary")
    record Vocabulary(
            List<EventOption> events,
            List<ObChannel> channels,
            List<String> recipients,
            List<String> mergeTags
    ) {
    }

    record VocabularyResponse(Vocabulary data) {
    }

    /**
     * @param mandatoryMail lets OB-12 lock the toggle before the click rather
     *                      than after the 409.
     */
    @Schema(name = "ObNotificationEventOption")
    record EventOption(String code, Category category, boolean mandatoryMail) {
    }
}
