package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;

/**
 * B-022 · S-15 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request types is the single source of truth for the
 * field rules, the call {@code PriorityDtos} and {@code TaskTypeDtos} both make:
 * the contract's {@code maxLength} and these annotations describe the same
 * constraint and only one of them actually runs.
 *
 * <p><b>{@code recipients} is a list on the wire and a delimited string in the
 * column.</b> The column shape is the migration's argument (a set nothing queries
 * across rows); the wire shape is a list because a JSON array is what a
 * multi-select posts and because {@code "ASSIGNEE,PROJECT_MANAGER"} on the wire
 * would make every client re-implement the split. {@code NotificationRecipient}
 * owns the conversion in both directions.
 */
final class NotificationTemplateDtos {

    private NotificationTemplateDtos() {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One template as the S-15 grid renders it.
     *
     * <p><b>{@code isMandatory} is derived, never stored.</b> It is
     * {@code channel == EMAIL && NotificationEvent.isMandatoryMail()} — D-036's
     * rule, which is stated over the event's <em>category</em> so that a new
     * escalation event is mandatory the moment it is declared. A column would be
     * a second copy of that rule to keep in step, and the copy would be the one
     * an Admin's screen believed.
     *
     * <p>There is no send count. {@code email_log.template_id} has been nullable
     * and null since {@code V20260805_1530} — nothing has ever rendered from a
     * template, because until this task there were none — so a count would read
     * zero on every row and read as "unused" rather than as "not wired yet".
     * D-010 is the task that makes it mean something; the column is already
     * there for it.
     */
    record TemplateView(
            long id,
            String eventCode,
            String category,
            String channel,
            List<String> recipients,
            String subjectTemplate,
            String bodyTemplate,
            boolean isActive,
            boolean isMandatory) {
    }

    record TemplateListResponse(List<TemplateView> data) {
    }

    record TemplateResponse(TemplateView data) {
    }

    // ------------------------------------------------------------------
    // The vocabulary catalogue
    // ------------------------------------------------------------------

    /**
     * What an event is, and whether its mail can be switched off.
     *
     * @param mandatoryMail true when the {@code EMAIL} template for this event
     *                      is permanent — the screen renders the toggle as a
     *                      locked statement rather than letting a click earn a
     *                      409
     */
    record EventOption(String code, String category, boolean mandatoryMail) {
    }

    /**
     * The whole vocabulary S-15 composes a template from, in one read.
     *
     * <p>Reference data on the {@code /masters/permissions} pattern: no create,
     * no edit, no delete, because every value exists only because code resolves
     * it. A merge tag an Admin could add would substitute nothing and a
     * recipient they could delete would silently stop a mail reaching somebody.
     */
    record VocabularyView(
            List<EventOption> events,
            List<String> channels,
            List<String> recipients,
            List<String> mergeTags) {
    }

    record VocabularyResponse(VocabularyView data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * {@code subjectTemplate} is optional here and required by the service for
     * {@code EMAIL}.
     *
     * <p>The rule cannot be a {@code @NotBlank}, because whether a subject is
     * required depends on another field in the same body — a mail without a
     * subject line is unsendable, and an in-app entry has a title rather than a
     * subject, which is why the column is nullable at all.
     *
     * <p><b>A subject on a non-email channel is permitted rather than refused</b>,
     * which reads like a loose end and is a deliberate one. A browser push
     * genuinely has a title as well as a body (D-045), so refusing the field
     * outright would make this master unable to express something the channel
     * has. It is simply not required, and the seeded in-app rows all leave it
     * null.
     */
    record TemplateWrite(
            @NotBlank(message = "eventCode is required")
            @Size(max = 60, message = "eventCode must be at most 60 characters")
            String eventCode,

            @NotBlank(message = "channel is required")
            @Size(max = 20, message = "channel must be at most 20 characters")
            String channel,

            @NotEmpty(message = "Name at least one recipient — a template with none would look configured and send nothing")
            List<String> recipients,

            @Size(max = 255, message = "subjectTemplate must be at most 255 characters")
            String subjectTemplate,

            @NotBlank(message = "bodyTemplate is required")
            String bodyTemplate,

            Boolean isActive) {
    }

    /**
     * Every field optional; an omitted one keeps its stored value.
     *
     * <p><b>A POJO rather than a record</b>, for the reason {@code PriorityPatch}
     * and B-017 both give: one field is genuinely clearable.
     * {@code subjectTemplate} is a nullable column whose null means "this channel
     * does not carry a subject", so "absent" and "explicitly null" have to mean
     * different things — and Jackson fills an absent {@code Optional} creator
     * property on a record with {@code Optional.empty()}, the same value an
     * explicit null produces. Both would collapse into "clear it", and a
     * {@code PATCH {"isActive": false}} would silently strip the subject line off
     * a mail while echoing back a response that looked correct.
     *
     * <p>{@code eventCode} and {@code channel} are here only so that sending a
     * different one can be refused — together they are the row's identity, and
     * moving a template to another event is creating a different template.
     * Leaving them off the type would mean Jackson discards them and a caller who
     * believed they had re-pointed a template is told the save succeeded.
     */
    static final class TemplatePatch {

        private String eventCode;

        private String channel;

        private List<String> recipients;

        private Optional<@Size(max = 255,
                message = "subjectTemplate must be at most 255 characters") String> subjectTemplate;

        private String bodyTemplate;

        private Boolean isActive;

        TemplatePatch() {
        }

        /** For the tests, which have no reason to go through Jackson. */
        TemplatePatch(String eventCode, String channel, List<String> recipients,
                      Optional<String> subjectTemplate, String bodyTemplate, Boolean isActive) {
            this.eventCode = eventCode;
            this.channel = channel;
            this.recipients = recipients;
            this.subjectTemplate = subjectTemplate;
            this.bodyTemplate = bodyTemplate;
            this.isActive = isActive;
        }

        public String getEventCode() {
            return eventCode;
        }

        public void setEventCode(String eventCode) {
            this.eventCode = eventCode;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }

        public Optional<String> getSubjectTemplate() {
            return subjectTemplate;
        }

        public void setSubjectTemplate(Optional<String> subjectTemplate) {
            this.subjectTemplate = subjectTemplate;
        }

        public String getBodyTemplate() {
            return bodyTemplate;
        }

        public void setBodyTemplate(String bodyTemplate) {
            this.bodyTemplate = bodyTemplate;
        }

        public Boolean getIsActive() {
            return isActive;
        }

        public void setIsActive(Boolean isActive) {
            this.isActive = isActive;
        }

        // Accessors named the way the rest of the feature's DTOs read, so the
        // service does not have to know this one is a POJO.

        String eventCode() {
            return eventCode;
        }

        String channel() {
            return channel;
        }

        List<String> recipients() {
            return recipients;
        }

        Optional<String> subjectTemplate() {
            return subjectTemplate;
        }

        String bodyTemplate() {
            return bodyTemplate;
        }

        Boolean isActive() {
            return isActive;
        }
    }
}
