package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.text.RichTextSanitizer;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * C-071 · what a project's settings mean for a ticket being raised on it.
 *
 * <p>B-019 stores and serves the configuration; this is the half that makes it
 * true. Split out of {@link ProjectSettingsGate} so the decisions can be tested
 * without a database: the gate owns two {@code SELECT}s and this owns every
 * question about what the answers mean.
 *
 * <h2>The vocabulary is the contract's, not Stream B's enum</h2>
 *
 * <p>{@code ProjectSettingsDtos.TicketField} says the same ten codes and is
 * package-private in {@code feature/masters/projects} — Stream B's directory
 * (TEAM-PLAN.md §6). The shared source of truth is {@code TicketFieldCode} in
 * {@code contracts/openapi.yaml}, which is what both sides are written against;
 * widening one of the two Java copies to reach the other would be a cross-stream
 * coupling for a list that changes when the contract changes anyway. The codes
 * are matched as <b>strings</b> here for the same reason the repository returns
 * them as strings: an unrecognised one must not be able to throw.
 *
 * <h2>An unrecognised code is ignored, never fatal</h2>
 *
 * <p>{@code ck_projects_mandatory_fields} constrains the column to the
 * <i>shape</i> of a code and deliberately not to the list of them — B-019's
 * migration header sets out why — so a value this build has never heard of is
 * reachable after a rollback. {@code ProjectSettingsService.readFields} drops
 * one rather than failing the settings read; the same value must not fail
 * <em>ticket creation</em> either, which would be strictly worse: a code nobody
 * can satisfy would stop every ticket on the project, and the screen that could
 * repair it is one the code has already been dropped from.
 */
final class ProjectTicketRules {

    private ProjectTicketRules() {
    }

    /**
     * The contract's {@code TicketFieldCode}, paired with the request property
     * that answers it.
     *
     * <p><b>{@code requestField} is also the key the 400 is reported under, and
     * it has to match the form's own field name.</b> {@code CreateTicketPage}
     * maps {@code errors: {field: [messages]}} straight onto its controls by
     * that key, so {@code moduleId} puts the message on the Module picker and
     * {@code module} puts it nowhere at all — a validation failure that renders
     * as an empty banner over a form that looks fine.
     */
    enum RequiredField {
        DESCRIPTION("description", "a task description"),
        MODULE("moduleId", "a module"),
        SCREEN_NAME("screenName", "a screen name"),
        FEATURE("feature", "a feature"),
        STEPS_TO_GENERATE("stepsToGenerate", "steps to generate"),
        CLIENT("clientId", "a client"),
        CLIENT_CONTACT("clientContactId", "a client contact"),
        ASSIGNEE("assigneeId", "an assignee"),
        ESTIMATED_HRS("estimatedHrs", "an effort estimate"),
        PLANNED_CLOSE_DATE("plannedCloseDate", "a planned close date");

        private final String requestField;
        private final String label;

        RequiredField(String requestField, String label) {
            this.requestField = requestField;
            this.label = label;
        }

        String requestField() {
            return requestField;
        }

        String message() {
            return "This project requires " + label + " on every ticket.";
        }

        static Optional<RequiredField> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.empty();
            }
            String normalised = code.trim().toUpperCase(Locale.ROOT);
            for (RequiredField field : values()) {
                if (field.name().equals(normalised)) {
                    return Optional.of(field);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Whether this project's allow-list refuses the task type.
     *
     * <p><b>An empty allow-list allows everything.</b> This is the decision
     * B-019 turns on and it is restated here rather than inferred, because
     * getting it backwards is not a subtle bug: every project in the system has
     * an empty allow-list until somebody configures one, so reading the absence
     * as "nothing may be raised" would stop ticket creation organisation-wide
     * the first time this gate ran.
     *
     * <p>Membership is checked against activity nowhere: a project that still
     * allows a since-retired task type may still raise one. {@link ModuleGuard}
     * makes the opposite call about a deactivated module, and the two are not in
     * conflict — a deactivated master row is an administrator withdrawing it
     * from everybody, while a {@code project_task_types} row is this project's
     * administrator explicitly naming it. Where the two disagree, the more
     * specific statement is the later one.
     */
    static boolean taskTypeRefused(Set<Integer> allowedTaskTypeIds, Integer taskTypeId) {
        return !allowedTaskTypeIds.isEmpty() && taskTypeId != null && !allowedTaskTypeIds.contains(taskTypeId);
    }

    /**
     * Every configured field this request leaves empty, keyed by request property.
     *
     * <p>All of them, not the first — the form marks each control it names, and
     * a caller told about one missing field at a time on a form with ten of them
     * is a caller making ten round trips. Insertion-ordered, so the detail line
     * reads in the order the settings tab stored them.
     *
     * @param plannedCloseDate the <b>resolved</b> date — supplied by the caller
     *                         or computed from the SLA ladder — never
     *                         {@code request.plannedCloseDate()}. See
     *                         {@link #plannedCloseDateAnswered}
     */
    static Map<String, String> missingFields(Collection<String> codes,
                                             TicketCreateDtos.CreateRequest request,
                                             Instant plannedCloseDate,
                                             RichTextSanitizer sanitizer) {
        Map<String, String> missing = new LinkedHashMap<>();
        for (String code : codes) {
            RequiredField field = RequiredField.of(code).orElse(null);
            if (field != null && !isAnswered(field, request, plannedCloseDate, sanitizer)) {
                missing.put(field.requestField(), field.message());
            }
        }
        return missing;
    }

    private static boolean isAnswered(RequiredField field,
                                      TicketCreateDtos.CreateRequest request,
                                      Instant plannedCloseDate,
                                      RichTextSanitizer sanitizer) {
        return switch (field) {
            case DESCRIPTION -> hasRichText(request.description(), sanitizer);
            case STEPS_TO_GENERATE -> hasRichText(request.stepsToGenerate(), sanitizer);
            case MODULE -> request.moduleId() != null;
            case SCREEN_NAME -> hasText(request.screenName());
            case FEATURE -> hasText(request.feature());
            case CLIENT -> request.clientId() != null;
            case CLIENT_CONTACT -> request.clientContactId() != null;
            case ASSIGNEE -> request.assigneeId() != null;
            // Present, not positive. `0` is a genuine zero-hour estimate on a
            // ticket somebody has actually estimated — `toCreateRequest`'s note
            // draws the same distinction against omitting the field — and
            // whether zero is a sensible answer is the form's question rather
            // than this setting's. What "mandatory" asks is that somebody
            // answered it.
            case ESTIMATED_HRS -> request.estimatedHrs() != null;
            case PLANNED_CLOSE_DATE -> plannedCloseDateAnswered(plannedCloseDate);
        };
    }

    /**
     * <b>Measured against the resolved date, not against what the caller sent.</b>
     *
     * <p>The alternative reading — require the caller to send one — would make
     * this the one setting a Support agent cannot satisfy: overriding the planned
     * close date is PM and Admin only, so S-19 renders the control read-only for
     * the other four roles, and a project ticking this box would refuse every
     * ticket four of six roles tried to raise. What a PM means by ticking it is
     * "no ticket here goes out without a target date", and the date the SLA
     * ladder computes is a target date.
     *
     * <p>So it fails exactly when the ticket really would have none: no override,
     * and no rung of the ladder answered. {@link SlaResolution} documents that
     * outcome as legitimate — a project with no SLA policy anywhere above it
     * stores {@code plannedCloseDate IS NULL} — and this setting is a project
     * saying it is not legitimate <em>here</em>.
     */
    private static boolean plannedCloseDateAnswered(Instant plannedCloseDate) {
        return plannedCloseDate != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * A rich-text field is answered when it survives the sanitiser as something
     * a reader would see.
     *
     * <p>{@code isBlank()} on the raw value is the tempting check and it passes
     * on an empty editor: a contentEditable the user focused and left holds
     * {@code <p><br></p>}, which is thirteen characters of nothing.
     * {@code isRichTextEmpty} in {@code rich-text.ts} is the same rule on the
     * form, and this is the copy that cannot be skipped (PLAN.md §3.9).
     *
     * <p>An image on its own counts. {@code toPlainText} projects markup to prose
     * and an {@code img} has none, so a screenshot pasted as the entire answer
     * would read as empty — and on {@code stepsToGenerate} a pasted screenshot
     * <em>is</em> the answer often enough that refusing it would be the rule
     * misfiring on its best case.
     */
    private static boolean hasRichText(String html, RichTextSanitizer sanitizer) {
        if (html == null || html.isBlank()) {
            return false;
        }
        String clean = sanitizer.sanitize(html);
        return !sanitizer.toPlainText(clean).isBlank() || clean.contains("<img");
    }
}
