package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStage;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportRowError;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportedDoc;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportedItem;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportedTask;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.TaskImportPreview;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.TaskImportResult;

/**
 * OB-07 · validates and applies a Tasks / Task List / Document Checklist
 * workbook against one draft Module Service.
 *
 * <h2>Why a whole-tree replace, not a per-row upsert</h2>
 *
 * <p>{@code feature/imports}' engine upserts on a natural key, so a corrected
 * re-upload touches only the rows that changed. A checklist row has no such
 * key — nobody assigns a code to "Signed order form received" — so re-running
 * this import instead <b>replaces the draft's entire task tree</b> with what
 * the file describes. That is only safe because the target is always a draft:
 * {@code publishedAt == null}, nothing has been instantiated from it yet, and
 * {@code ObJourneyTemplateService#requireEditable} is what stops this from
 * ever reaching a version a client is already on.
 *
 * <h2>Validate the whole file before writing any of it</h2>
 *
 * <p>{@link #preview} runs every check below and writes nothing. {@link #commit}
 * runs the identical checks again on the re-uploaded file — the stage groups
 * or the file itself could have changed between the two calls — and only
 * replaces the tree if every row still passes. A half-applied bulk import is
 * worse than a rejected one.
 */
@Service
class ObJourneyTaskImportService {

    private final ObJourneyTaskImportWorkbook workbook;
    private final ObJourneyTemplateRepository templates;
    private final ObJourneyTemplateStageRepository stageGroups;
    private final ObJourneyTemplateService templateService;

    ObJourneyTaskImportService(ObJourneyTaskImportWorkbook workbook, ObJourneyTemplateRepository templates,
                               ObJourneyTemplateStageRepository stageGroups,
                               ObJourneyTemplateService templateService) {
        this.workbook = workbook;
        this.templates = templates;
        this.stageGroups = stageGroups;
        this.templateService = templateService;
    }

    // ------------------------------------------------------------------ template

    List<String> stageNames(long templateId) {
        requireTemplate(templateId);
        return stageGroups.findByTemplateIdOrderBySequenceAscIdAsc(templateId).stream()
                .map(ObJourneyTemplateStage::getName)
                .toList();
    }

    void writeTemplate(long templateId, OutputStream out) throws IOException {
        workbook.writeTemplate(stageNames(templateId), out);
    }

    // ------------------------------------------------------------------ preview / commit

    TaskImportPreview preview(long templateId, InputStream file) throws IOException {
        return validate(templateId, file);
    }

    @Transactional
    TaskImportResult commit(long templateId, InputStream file) throws IOException {
        TaskImportPreview preview = validate(templateId, file);
        if (!preview.valid()) {
            throw new TaskImportValidationException(preview.errors());
        }
        int items = preview.tasks().stream().mapToInt(t -> t.items().size()).sum();
        int docs = preview.tasks().stream().mapToInt(t -> t.docs().size()).sum();
        templateService.replaceTasksFromImport(templateId, preview.tasks());
        return new TaskImportResult(preview.tasks().size(), items, docs);
    }

    private TaskImportPreview validate(long templateId, InputStream file) throws IOException {
        ObJourneyTemplate template = requireTemplate(templateId);
        List<ImportRowError> errors = new ArrayList<>();

        if (template.getPublishedAt() != null) {
            errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, 0,
                    "This Module Service version has already been published. Begin a revision"
                            + " before importing."));
            return TaskImportPreview.invalid(errors);
        }

        Set<String> validStages = new HashSet<>();
        for (String name : stageNames(templateId)) {
            validStages.add(normalise(name));
        }

        ObJourneyTaskImportWorkbook.ParsedWorkbook parsed = workbook.parse(file);

        if (parsed.tasks().isEmpty()) {
            errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, 0,
                    "No rows found. Check the '" + ObJourneyTaskImportWorkbook.TASKS_SHEET
                            + "' sheet exists and has at least one task."));
            return TaskImportPreview.invalid(errors);
        }

        Map<String, TaskBuilder> byName = new LinkedHashMap<>();
        List<TaskBuilder> order = new ArrayList<>();

        for (ObJourneyTaskImportWorkbook.RawRow row : parsed.tasks()) {
            String stage = row.cell(0).trim();
            String name = row.cell(1).trim();
            String description = row.cell(2).trim();
            String tatRaw = row.cell(3).trim();
            String signoffRaw = row.cell(4).trim();
            String dependsOn = row.cell(5).trim();

            if (name.isEmpty()) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, row.rowNumber(),
                        "Task Name is required."));
                continue;
            }
            if (byName.containsKey(normalise(name))) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, row.rowNumber(),
                        "Duplicate task name '" + name + "' — task names must be unique in the file."));
                continue;
            }
            if (!stage.isEmpty() && !validStages.contains(normalise(stage))) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, row.rowNumber(),
                        "'" + stage + "' is not one of this Module Service's stages — see the"
                                + " Instructions sheet for the current list, or leave blank."));
                continue;
            }
            Integer tatDays = parsePositiveInt(tatRaw);
            if (tatDays == null) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, row.rowNumber(),
                        "TAT Days must be a whole number greater than zero."));
                continue;
            }
            Boolean requiresSignoff = parseYesNo(signoffRaw, false);
            if (requiresSignoff == null) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, row.rowNumber(),
                        "Requires Sign-off must be Y, N or blank."));
                continue;
            }
            if (!dependsOn.isEmpty() && !byName.containsKey(normalise(dependsOn))) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.TASKS_SHEET, row.rowNumber(),
                        "'Depends On Task' must name a task that appears earlier in this sheet;"
                                + " '" + dependsOn + "' has not been seen yet."));
                continue;
            }

            TaskBuilder task = new TaskBuilder(stage.isEmpty() ? null : stage, name,
                    description.isEmpty() ? null : description, tatDays, requiresSignoff,
                    dependsOn.isEmpty() ? null : dependsOn);
            byName.put(normalise(name), task);
            order.add(task);
        }

        for (ObJourneyTaskImportWorkbook.RawRow row : parsed.items()) {
            String taskName = row.cell(0).trim();
            String label = row.cell(1).trim();
            String mandatoryRaw = row.cell(2).trim();
            TaskBuilder task = byName.get(normalise(taskName));
            if (task == null) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.ITEMS_SHEET, row.rowNumber(),
                        "Task Name '" + taskName + "' does not match any task in the '"
                                + ObJourneyTaskImportWorkbook.TASKS_SHEET + "' sheet."));
                continue;
            }
            if (label.isEmpty()) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.ITEMS_SHEET, row.rowNumber(),
                        "Item Label is required."));
                continue;
            }
            Boolean mandatory = parseYesNo(mandatoryRaw, true);
            if (mandatory == null) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.ITEMS_SHEET, row.rowNumber(),
                        "Mandatory must be Y, N or blank."));
                continue;
            }
            task.items.add(new ImportedItem(label, mandatory));
        }

        for (ObJourneyTaskImportWorkbook.RawRow row : parsed.docs()) {
            String taskName = row.cell(0).trim();
            String label = row.cell(1).trim();
            String requiredRaw = row.cell(2).trim();
            TaskBuilder task = byName.get(normalise(taskName));
            if (task == null) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.DOCS_SHEET, row.rowNumber(),
                        "Task Name '" + taskName + "' does not match any task in the '"
                                + ObJourneyTaskImportWorkbook.TASKS_SHEET + "' sheet."));
                continue;
            }
            if (label.isEmpty()) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.DOCS_SHEET, row.rowNumber(),
                        "Document Label is required."));
                continue;
            }
            Boolean required = parseYesNo(requiredRaw, true);
            if (required == null) {
                errors.add(new ImportRowError(ObJourneyTaskImportWorkbook.DOCS_SHEET, row.rowNumber(),
                        "Required must be Y, N or blank."));
                continue;
            }
            task.docs.add(new ImportedDoc(label, required));
        }

        if (!errors.isEmpty()) {
            return TaskImportPreview.invalid(errors);
        }
        return TaskImportPreview.valid(order.stream().map(TaskBuilder::build).toList());
    }

    private ObJourneyTemplate requireTemplate(long templateId) {
        return templates.findById(templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    private static String normalise(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** @return the parsed flag, {@code defaultValue} for a blank cell, or null for anything else. */
    private static Boolean parseYesNo(String value, boolean defaultValue) {
        if (value.isEmpty()) {
            return defaultValue;
        }
        if ("Y".equalsIgnoreCase(value)) {
            return true;
        }
        if ("N".equalsIgnoreCase(value)) {
            return false;
        }
        return null;
    }

    /** Accumulates one task's checklist rows while the Tasks sheet is still being read top to bottom. */
    private static final class TaskBuilder {
        final String stageGroupName;
        final String name;
        final String description;
        final int tatDays;
        final boolean requiresSignoff;
        final String dependsOnTaskName;
        final List<ImportedItem> items = new ArrayList<>();
        final List<ImportedDoc> docs = new ArrayList<>();

        TaskBuilder(String stageGroupName, String name, String description, int tatDays,
                    boolean requiresSignoff, String dependsOnTaskName) {
            this.stageGroupName = stageGroupName;
            this.name = name;
            this.description = description;
            this.tatDays = tatDays;
            this.requiresSignoff = requiresSignoff;
            this.dependsOnTaskName = dependsOnTaskName;
        }

        ImportedTask build() {
            return new ImportedTask(stageGroupName, name, description, tatDays, requiresSignoff,
                    dependsOnTaskName, List.copyOf(items), List.copyOf(docs));
        }
    }
}
