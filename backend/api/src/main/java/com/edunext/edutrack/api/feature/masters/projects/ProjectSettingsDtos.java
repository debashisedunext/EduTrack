package com.edunext.edutrack.api.feature.masters.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * B-019 · S-10 Settings tab wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on {@link ProjectSettingsWrite} is the source of truth for
 * the field rules, per the note on {@link ProjectDtos}. The rules that need a
 * query — is this a real task type, is the same one listed twice — are in
 * {@link ProjectSettingsService}, because neither can be decided from one field
 * in isolation.
 */
final class ProjectSettingsDtos {

    private ProjectSettingsDtos() {
    }

    // ------------------------------------------------------------------
    // Vocabularies
    // ------------------------------------------------------------------

    /**
     * Who an unassigned new ticket goes to.
     *
     * <p>An enum where {@code level} is a {@code String} in
     * {@link SlaPolicyDtos.SlaPolicyWrite}, and the difference is which of them
     * an administrator can extend. S-12 lets one add a priority level without a
     * release, so a Java enum would make that a code change; the three
     * assignment strategies are three implementations, and a fourth is a
     * feature somebody writes rather than a row somebody adds.
     * {@code ck_projects_auto_assign_rule} says the same three.
     *
     * <p><b>{@link ProjectService} validates against this enum rather than
     * against a {@code Set<String>} of its own.</b> It held one until B-019, and
     * a second vocabulary for one column is how the General tab and the Settings
     * tab end up disagreeing about what they will accept — the drift B-018
     * spent a whole agreement test avoiding on the SLA ladder. One list, checked
     * against the database's {@code CHECK} by
     * {@code ProjectSettingsServiceTest}.
     */
    enum AutoAssignRule {
        ROUND_ROBIN,
        LEAST_LOADED,
        MANUAL,
    }

    /**
     * A ticket field a project may require.
     *
     * <p><b>Exactly the optional fields of {@code TicketCreateRequest}.</b>
     * {@code projectId}, {@code title}, {@code taskTypeId} and {@code level} are
     * required of every ticket already, so a checkbox for one of them could not
     * change any outcome — a control that cannot do what it appears to do is
     * worse than an absent one, because somebody ticks it and believes
     * something happened.
     *
     * <p><b>This enum is the vocabulary and the database is not.</b>
     * {@code ck_projects_mandatory_fields} constrains the column to a unique
     * array of uppercase codes and stops there, deliberately: the list tracks
     * Stream C's create form, and pinning the values in a {@code CHECK} would
     * mean C cannot add a form field without a migration in Stream B's
     * directory. The migration header sets out the trade against
     * {@code ck_projects_status}, which does pin its values because blueprint
     * S-10 fixes them.
     *
     * <p>{@code MODULE} overlaps a rule §7.5 already states — the field is
     * mandatory on the form for bug-type task types — and is here anyway: a
     * project that requires it for <i>every</i> task type is tightening that
     * rule rather than restating it.
     */
    enum TicketField {
        DESCRIPTION,
        MODULE,
        SCREEN_NAME,
        FEATURE,
        STEPS_TO_GENERATE,
        CLIENT,
        CLIENT_CONTACT,
        ASSIGNEE,
        ESTIMATED_HRS,
        PLANNED_CLOSE_DATE,
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One row of the allow-list, as the checkbox grid renders it.
     *
     * @param name     denormalised so the list can label itself. There is no
     *                 mounted {@code /masters/task-types} yet (B-020) and a
     *                 checkbox reading "task type 7" is not a screen — the same
     *                 call B-018 made for {@link SlaPolicyDtos.SlaCell} and
     *                 B-017 for {@code TeamMember.displayName}
     * @param isActive false only for a retired task type this project still
     *                 allows. Those rows are returned <b>because</b> the
     *                 {@code PUT} is assembled from this list: one that was
     *                 allowed and not rendered would be dropped by the next
     *                 save through a screen that never displayed it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SettingsTaskType(int taskTypeId, String code, String name,
                            boolean isAllowed, boolean isActive) {
    }

    /**
     * The tab as one document.
     *
     * @param restrictsTaskTypes whether this project has an allow-list at all.
     *                           <b>False means every active task type is
     *                           allowed, not that none are</b> — see
     *                           {@link ProjectSettingsService}. Derived from the
     *                           allow-list being empty, and on the wire because
     *                           a client cannot otherwise tell an unrestricted
     *                           project from one that ticked every box, and the
     *                           two differ the moment an Admin adds a twelfth
     *                           task type
     */
    record ProjectSettings(long projectId,
                           AutoAssignRule autoAssignRule,
                           List<TicketField> mandatoryFields,
                           boolean restrictsTaskTypes,
                           List<SettingsTaskType> taskTypes) {
    }

    /** CONVENTIONS.md §2 — every 2xx body wrapped in {@code data}. */
    record ProjectSettingsResponse(ProjectSettings data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * All three settings, always.
     *
     * <p><b>Every field is {@code @NotNull} even though the screen may have
     * changed only one.</b> This is a wholesale replace behind one
     * {@code If-Match}, and on a replace an omitted field is ambiguous between
     * "leave it alone" and "clear it" in exactly the way that loses somebody's
     * setting quietly. {@link ProjectDtos}' patch shape is the opposite and is
     * right for a {@code PATCH}; the two differ because the verbs do.
     *
     * <p>Both lists may be <b>empty</b>, and the two empties mean different
     * things, which is the one asymmetry on this screen worth knowing:
     *
     * <ul>
     *   <li>{@code mandatoryFields: []} — this project requires nothing beyond
     *       the fields every ticket requires. Literal, and the common case.</li>
     *   <li>{@code allowedTaskTypeIds: []} — this project restricts
     *       <b>nothing</b>, so every active task type may be raised. It is not
     *       "no task type is permitted": a project that allowed none could
     *       raise no ticket, so that state does not exist, and the request that
     *       would express it removes the restriction instead. The migration
     *       header explains why the absence has to mean this rather than the
     *       other.</li>
     * </ul>
     *
     * <p>{@code maxItems} on both is a bound rather than a rule anybody will
     * meet: 20 is twice the vocabulary and 200 is well past any plausible task
     * type master. They exist so an unbounded array cannot arrive from a client
     * that is not the screen.
     *
     * <p><b>The two vocabularies arrive as {@code String} and are parsed in
     * {@link ProjectSettingsService}, not typed as enums here.</b> Jackson
     * refuses an unknown enum constant with a
     * {@code HttpMessageNotReadableException}, which is a 400 whose body is a
     * parser message about a Java type and carries no {@code errors} map — so
     * the screen would have nothing to put on the control that caused it. B-016
     * made the same call one file over for the same field, and the cost is one
     * parse per value in exchange for {@code "autoAssignRule": ["must be one of
     * …"]}.
     */
    record ProjectSettingsWrite(
            @NotNull(message = "autoAssignRule is required")
            String autoAssignRule,

            @NotNull(message = "mandatoryFields is required — send [] for none")
            @Size(max = 20, message = "mandatoryFields is out of range")
            List<String> mandatoryFields,

            @NotNull(message = "allowedTaskTypeIds is required — send [] to allow every task type")
            @Size(max = 200, message = "allowedTaskTypeIds is out of range")
            List<Integer> allowedTaskTypeIds) {
    }
}
