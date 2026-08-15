package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B-019 · the S-10 Settings tab — "allowed task types, mandatory fields,
 * auto-assign rule (round-robin / least-loaded / manual)".
 *
 * <h2>An empty allow-list means unrestricted</h2>
 *
 * <p>This is the decision the feature turns on, and it is the one thing to
 * carry away from this class:
 *
 * <p><b>No {@code project_task_types} rows means every active task type may be
 * raised. It does not mean none may.</b>
 *
 * <p>Every project in the system is in that state, because the table did not
 * exist until this task's migration ran. Had the absence meant "nothing is
 * permitted", applying the migration would have stopped ticket creation
 * everywhere at once. Nor could a backfill have avoided it: writing a row per
 * task type per project to preserve today's behaviour would turn "never
 * configured" into "configured, on 14 Aug 2026, to allow exactly these eleven",
 * and the twelfth task type an Admin adds next month would then be silently
 * barred on every project in the organisation.
 *
 * <p>The consequence an administrator meets is that clearing every checkbox
 * removes the restriction rather than forbidding everything, and the screen has
 * to say that in words rather than leaving eleven empty boxes to be read as
 * "nothing may be raised here". There is deliberately no separate "remove the
 * restriction" control — a project permitting no task type could raise no
 * ticket, so the state does not exist, and two controls for one outcome is how
 * they end up disagreeing ({@link SlaMatrixService} makes the same call about a
 * cleared cell).
 *
 * <h2>The read is resolved, the write is literal</h2>
 *
 * <p>{@link #settings} returns every active task type with an {@code isAllowed}
 * flag — an unrestricted project answers {@code true} for all of them, because
 * that is what it means, and a checkbox list needs the same shape either way.
 * {@code restrictsTaskTypes} is what tells the two apart, and it is on the wire
 * rather than derived by each client because the difference only becomes
 * visible when a twelfth task type appears.
 *
 * <p>It also returns any <b>inactive</b> task type this project still allows.
 * That is not tidiness: {@link #replace} is a wholesale replace assembled from
 * the rows the screen was given, so an allowed-but-unrendered row would be
 * deleted by the next save through a screen that never displayed it — the
 * deletion-by-omission {@code SlaMatrixRepository.DEACTIVATE_OVERRIDES} guards
 * against for project-level SLA defaults.
 *
 * <h2>What is deliberately not a rule</h2>
 *
 * <p><b>Nothing here checks that the allow-list is non-empty, or that a project
 * with tickets keeps allowing the task types those tickets used.</b> The first
 * is the paragraph above. The second would be a rule that makes a task type
 * un-retireable the moment one ticket uses it, and historical tickets keep the
 * task type they were raised with regardless — the allow-list governs what may
 * be raised next, not what was.
 *
 * <p><b>Nothing enforces these settings on ticket creation, and that is not
 * this class's to do.</b> {@code api.feature.tickets} and {@code CreateTicketPage}
 * are Stream C's. B-019 stores and serves the configuration; C consumes it.
 * Stated here rather than left to be discovered, because a settings screen whose
 * settings do nothing is a screen that looks finished.
 */
@Service
class ProjectSettingsService {

    private final ProjectSettingsRepository repository;

    ProjectSettingsService(ProjectSettingsRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * This project's settings, resolved.
     *
     * @throws NoSuchProjectException 404, never 403 — CLAUDE.md's rule
     */
    @Transactional(readOnly = true)
    ProjectSettingsDtos.ProjectSettings settings(long projectId) {
        requireProject(projectId);

        ProjectSettingsRepository.SettingsRow row = repository.settings(projectId);
        List<ProjectSettingsRepository.TaskTypeRow> taskTypes = repository.taskTypesFor(projectId);
        boolean restricts = taskTypes.stream().anyMatch(ProjectSettingsRepository.TaskTypeRow::isAllowed);

        List<ProjectSettingsDtos.SettingsTaskType> rows = new ArrayList<>(taskTypes.size());
        for (ProjectSettingsRepository.TaskTypeRow taskType : taskTypes) {
            rows.add(new ProjectSettingsDtos.SettingsTaskType(
                    taskType.id(),
                    taskType.code(),
                    taskType.name(),
                    // An unrestricted project allows every row it returned, and
                    // every row it returned is active — the LEFT JOIN only adds
                    // an inactive one where a membership row exists, which is
                    // precisely the case `restricts` is true.
                    !restricts || taskType.isAllowed(),
                    taskType.isActive()));
        }

        return new ProjectSettingsDtos.ProjectSettings(
                projectId,
                parseRule(row.autoAssignRule()),
                readFields(row.mandatoryFields()),
                restricts,
                rows);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Replace all three settings in one transaction.
     *
     * <p>Delete-then-insert on the allow-list rather than a diff. The diff needs
     * two set differences and two statements to apply them; this is two
     * statements plus one per allowed id, inside a transaction, and it cannot
     * leave a row behind. {@link SlaMatrixService#replace} reaches the same
     * shape by a different route for a different reason — there the rows carry
     * ids other tables reference, so they are deactivated instead of removed.
     *
     * @return the settings as they now read, so the caller renders what it saved
     *         rather than what it sent — the two differ whenever the allow-list
     *         was cleared and every task type is now allowed again
     */
    @Transactional
    ProjectSettingsDtos.ProjectSettings replace(long projectId,
                                                ProjectSettingsDtos.ProjectSettingsWrite write) {
        requireProject(projectId);

        String rule = parseRule(write.autoAssignRule()).name();
        List<String> fields = validFields(write.mandatoryFields());
        List<Integer> allowed = validTaskTypeIds(write.allowedTaskTypeIds());

        repository.updateSettings(projectId, rule, fields);
        repository.clearAllowedTaskTypes(projectId);
        for (Integer taskTypeId : allowed) {
            repository.allowTaskType(projectId, taskTypeId);
        }
        return settings(projectId);
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    /**
     * All of them are checked before anything is written.
     *
     * <p>The body is one transaction, so a value refused halfway through would
     * roll back the rows before it, and the caller would be told about the
     * fourth task type of a save that also silently did not apply the first
     * three. Same ordering discipline as {@link SlaMatrixService#validate}.
     */
    private List<Integer> validTaskTypeIds(List<Integer> requested) {
        Set<Integer> seen = new LinkedHashSet<>();

        for (Integer taskTypeId : requested) {
            if (taskTypeId == null) {
                throw new SettingsValidationException("allowedTaskTypeIds",
                        "allowedTaskTypeIds must not contain a null");
            }
            // The same id twice. The insert would fail on the composite primary
            // key with a message naming a MySQL index, which is a 500 the
            // caller cannot act on rather than a rule they can fix.
            if (!seen.add(taskTypeId)) {
                throw new SettingsValidationException("allowedTaskTypeIds",
                        "task type " + taskTypeId + " is listed twice");
            }
            // Existence, not activity — a project may keep allowing a type an
            // Admin has retired, and refusing the id would make that row
            // unremovable through the only screen that can see it.
            if (!repository.taskTypeExists(taskTypeId)) {
                throw new SettingsValidationException("allowedTaskTypeIds",
                        "no such task type: " + taskTypeId);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * <p>A duplicate is refused rather than collapsed, though the column's
     * {@code uniqueItems} would accept the de-duplicated result either way. A
     * body the server quietly rewrites is one whose author believes something
     * about it that is not true — and here the belief would be that they had
     * sent a list the screen cannot produce.
     */
    private List<String> validFields(List<String> requested) {
        Set<String> seen = new LinkedHashSet<>();

        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                throw new SettingsValidationException("mandatoryFields",
                        "mandatoryFields must not contain a blank");
            }
            String code = raw.trim().toUpperCase(Locale.ROOT);
            if (!FIELD_NAMES.contains(code)) {
                throw new SettingsValidationException("mandatoryFields",
                        "no such ticket field: " + raw + ". One of " + FIELD_NAMES);
            }
            if (!seen.add(code)) {
                throw new SettingsValidationException("mandatoryFields",
                        code + " is listed twice");
            }
        }
        return List.copyOf(seen);
    }

    /**
     * The stored codes, as the vocabulary this version knows.
     *
     * <p><b>An unrecognised code is dropped, not thrown on.</b> The column is
     * constrained to the shape of a code and not to the list of them — see the
     * migration header for why — so a value this build has never heard of is
     * possible after a rollback, and a settings read that threw would leave the
     * only screen that could repair it behind the failure.
     */
    private static List<ProjectSettingsDtos.TicketField> readFields(List<String> stored) {
        List<ProjectSettingsDtos.TicketField> fields = new ArrayList<>(stored.size());
        for (String code : stored) {
            for (ProjectSettingsDtos.TicketField field : ProjectSettingsDtos.TicketField.values()) {
                if (field.name().equals(code)) {
                    fields.add(field);
                    break;
                }
            }
        }
        return List.copyOf(fields);
    }

    /**
     * <p>A stored value is parsed through the same method as a submitted one.
     * {@code ck_projects_auto_assign_rule} makes an unknown one unreachable
     * through the database, and parsing it here anyway means the enum and the
     * constraint are checked against each other on every read rather than
     * assumed to agree.
     */
    private static ProjectSettingsDtos.AutoAssignRule parseRule(String requested) {
        if (requested == null || requested.isBlank()) {
            // The column is NOT NULL DEFAULT 'MANUAL', so this is a submitted
            // null rather than a stored one — and MANUAL is the right answer to
            // it for the reason B-016's migration gives: round-robin and
            // least-loaded both assign live work to somebody without a human
            // deciding, which is not a behaviour a project should acquire by
            // omission.
            return ProjectSettingsDtos.AutoAssignRule.MANUAL;
        }
        String code = requested.trim().toUpperCase(Locale.ROOT);
        for (ProjectSettingsDtos.AutoAssignRule rule : ProjectSettingsDtos.AutoAssignRule.values()) {
            if (rule.name().equals(code)) {
                return rule;
            }
        }
        throw new SettingsValidationException("autoAssignRule",
                "autoAssignRule must be one of " + RULE_NAMES);
    }

    /** 404 for an unknown project, checked first on both operations. */
    private void requireProject(long projectId) {
        if (!repository.projectExists(projectId)) {
            throw new NoSuchProjectException();
        }
    }

    // ------------------------------------------------------------------
    // Vocabularies, once
    // ------------------------------------------------------------------

    static final List<String> RULE_NAMES = names(ProjectSettingsDtos.AutoAssignRule.values());

    static final List<String> FIELD_NAMES = names(ProjectSettingsDtos.TicketField.values());

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableList());
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /** 404 — the project in the path. */
    static class NoSuchProjectException extends RuntimeException {
    }

    /**
     * 400, keyed on the field, so the message lands on the control that caused
     * it rather than in a banner. Same shape B-017's
     * {@code MemberValidationException} and B-018's {@code SlaValidationException}
     * use, and the contract's {@code ValidationFailed} describes.
     */
    static class SettingsValidationException extends RuntimeException {

        private final String field;

        SettingsValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return this.field;
        }
    }
}
