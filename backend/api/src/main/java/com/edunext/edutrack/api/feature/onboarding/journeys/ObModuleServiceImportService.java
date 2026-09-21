package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObImplementationStage;
import com.edunext.edutrack.domain.onboarding.ObImplementationStageRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportRowError;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportedService;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportedStep;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportedTask;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ModuleImportPreview;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ModuleImportResult;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ServiceAction;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.TaskToWrite;

/**
 * OB-07 · validates and applies a four-column Module Service workbook against
 * one product.
 *
 * <h2>What the file may and may not create</h2>
 *
 * <p>It creates <b>Module Services</b> (as drafts), <b>Tasks</b> and
 * <b>Checklist</b> entries freely. It creates no <b>Step</b>: a Step is an
 * Implementation Stage, those are decided on the OB-15 master, and a
 * spreadsheet that could invent one would be a second, unreviewed way to
 * edit an org-wide vocabulary every other Module Service also reads. A Step
 * name the master does not carry is a row error naming the valid list.
 *
 * <p>What the file <em>can</em> do is bind a stage the target service does not
 * hold yet — {@link ObJourneyTemplateService#ensureStageGroup}. Seeding runs
 * once, at a service's birth, so a service created before a stage was added
 * to the master would otherwise reject an import whose only fault is being
 * newer than the service.
 *
 * <h2>No dependencies, by design</h2>
 *
 * <p>Every imported task is created parallel — no {@code dependsOnStepId},
 * and no column that could set one. Ordering a task behind another is a
 * designer edit. This is what keeps the file to four columns and the
 * validation to three rules, and it is the difference between this and the
 * three-sheet workbook it replaced, where forward-reference and cycle rules
 * were most of what an author had to get right.
 *
 * <h2>Validate the whole file before writing any of it</h2>
 *
 * <p>{@link #preview} runs every check and writes nothing. {@link #commit}
 * runs the identical checks again on the re-uploaded file — the stage master,
 * the product's services, or the file itself could have changed between the
 * two calls — and only writes if every row still passes. One transaction
 * covers every service in the file: a half-applied bulk import is worse than
 * a rejected one.
 */
@Service
class ObModuleServiceImportService {

    private final ObModuleServiceImportWorkbook workbook;
    private final ObJourneyTemplateRepository templates;
    private final ObProductRepository products;
    private final ObImplementationStageRepository implementationStages;
    private final ObJourneyTemplateService templateService;

    ObModuleServiceImportService(ObModuleServiceImportWorkbook workbook,
                                 ObJourneyTemplateRepository templates,
                                 ObProductRepository products,
                                 ObImplementationStageRepository implementationStages,
                                 ObJourneyTemplateService templateService) {
        this.workbook = workbook;
        this.templates = templates;
        this.products = products;
        this.implementationStages = implementationStages;
        this.templateService = templateService;
    }

    // ------------------------------------------------------------------ template

    /** The active Implementation Stages, in display order — the Step dropdown's contents. */
    List<String> stepNames() {
        return implementationStages.findAllByIsActiveOrderBySequenceAscIdAsc(true).stream()
                .map(ObImplementationStage::getName)
                .toList();
    }

    void writeTemplate(OutputStream out) throws IOException {
        workbook.writeTemplate(stepNames(), out);
    }

    // ------------------------------------------------------------------ preview / commit

    ModuleImportPreview preview(long productId, InputStream file) throws IOException {
        Validation validation = validate(productId, file);
        return validation.errors.isEmpty()
                ? ModuleImportPreview.valid(validation.toPreviewServices())
                : ModuleImportPreview.invalid(validation.errors);
    }

    @Transactional
    ModuleImportResult commit(long productId, long callerUserId, InputStream file) throws IOException {
        Validation validation = validate(productId, file);
        if (!validation.errors.isEmpty()) {
            throw new ModuleImportValidationException(validation.errors);
        }

        int created = 0;
        int replaced = 0;
        int stepCount = 0;
        int taskCount = 0;
        int checklistCount = 0;
        /*
          Offset rather than a fresh read per service: `createTemplate` makes an
          inactive row, and the catalogue's next-sequence read only sees active
          ones, so every service in one file would otherwise be handed the same
          number. Ties are legal — `sequence` is not unique and readers break
          ties by id — but handing out N copies of one number makes the
          catalogue's initial order arbitrary for no reason.
        */
        int sequenceOffset = 0;

        for (ServiceBuilder service : validation.services.values()) {
            long templateId;
            if (service.targetTemplateId == null) {
                templateId = templateService.createTemplate(productId, service.name,
                        nextCatalogueSequence() + sequenceOffset, List.of(), callerUserId).getId();
                sequenceOffset++;
                created++;
            } else {
                templateId = service.targetTemplateId;
                replaced++;
            }

            List<TaskToWrite> tasks = new ArrayList<>();
            for (StepBuilder step : service.steps.values()) {
                long stageGroupId = templateService
                        .ensureStageGroup(templateId, step.implementationStageId).getId();
                stepCount++;
                for (TaskBuilder task : step.tasks.values()) {
                    List<String> checklist = task.checklistOrDefault();
                    tasks.add(new TaskToWrite(stageGroupId, task.name, checklist));
                    taskCount++;
                    checklistCount += checklist.size();
                }
            }
            templateService.replaceTasksFromImport(templateId, tasks);
        }

        return new ModuleImportResult(created, replaced, stepCount, taskCount, checklistCount);
    }

    // ------------------------------------------------------------------ validation

    /**
     * Everything the file says, checked, with the targets it resolves to.
     *
     * <p>Carries the mutable builders rather than the finished preview shape
     * because {@link #commit} needs the resolved ids the preview deliberately
     * does not expose — and re-deriving them after a second validation pass
     * would be a second place for the resolution rules to live.
     */
    private record Validation(List<ImportRowError> errors, Map<String, ServiceBuilder> services) {

        List<ImportedService> toPreviewServices() {
            return services.values().stream().map(ServiceBuilder::build).toList();
        }
    }

    private Validation validate(long productId, InputStream file) throws IOException {
        List<ImportRowError> errors = new ArrayList<>();
        Map<String, ServiceBuilder> services = new LinkedHashMap<>();

        if (!products.findById(productId).isPresent()) {
            errors.add(sheetError("No such product. Pick one from the list and try again."));
            return new Validation(errors, services);
        }

        Map<String, ObImplementationStage> stageByName = new LinkedHashMap<>();
        for (ObImplementationStage stage : implementationStages.findAllByIsActiveOrderBySequenceAscIdAsc(true)) {
            stageByName.put(normalise(stage.getName()), stage);
        }

        List<ObModuleServiceImportWorkbook.RawRow> rows = workbook.parse(file);
        if (rows.isEmpty()) {
            errors.add(sheetError("No rows found. Check the '" + ObModuleServiceImportWorkbook.IMPORT_SHEET
                    + "' sheet has at least one row under the headings."));
            return new Validation(errors, services);
        }

        for (ObModuleServiceImportWorkbook.RawRow row : rows) {
            String serviceName = row.cell(0).trim();
            String stepName = row.cell(1).trim();
            String taskName = row.cell(2).trim();
            String checklistLabel = row.cell(3).trim();

            if (serviceName.isEmpty()) {
                errors.add(rowError(row, "Module Service is required on every row."));
                continue;
            }
            if (stepName.isEmpty()) {
                errors.add(rowError(row, "Step is required on every row."));
                continue;
            }
            if (taskName.isEmpty()) {
                errors.add(rowError(row, "Task is required on every row."));
                continue;
            }
            ObImplementationStage stage = stageByName.get(normalise(stepName));
            if (stage == null) {
                errors.add(rowError(row, "'" + stepName + "' is not one of the Steps this organisation"
                        + " uses — see the Instructions sheet for the current list. Steps are added on"
                        + " the Implementation Stage master, not in this file."));
                continue;
            }

            ServiceBuilder service = services.computeIfAbsent(normalise(serviceName),
                    key -> new ServiceBuilder(serviceName, row.rowNumber()));
            StepBuilder step = service.steps.computeIfAbsent(normalise(stepName),
                    key -> new StepBuilder(stage.getName(), stage.getId()));
            TaskBuilder task = step.tasks.computeIfAbsent(normalise(taskName),
                    key -> new TaskBuilder(taskName));
            if (!checklistLabel.isEmpty()) {
                task.checklist.add(checklistLabel);
            }
        }

        resolveTargets(productId, services, errors);
        return new Validation(errors, services);
    }

    /**
     * Decides, per named service, whether committing creates a draft or
     * replaces one — and refuses a service whose latest version is published.
     *
     * <p>Keyed on {@code (product, name)} because that is how
     * {@code uq_ob_journey_templates_version} identifies a service, so "the
     * latest version of this service" is
     * {@link ObJourneyTemplateRepository#findTopByProductIdAndNameOrderByVersionDesc}
     * and not the product's newest row.
     *
     * <p>A published head is a row error rather than an automatic
     * {@code beginRevision}. Publishing is what every running journey pins
     * itself to, and silently opening a new version of a live service because
     * a spreadsheet named it is not a decision a file upload should make on an
     * admin's behalf.
     */
    private void resolveTargets(long productId, Map<String, ServiceBuilder> services,
                                List<ImportRowError> errors) {
        for (ServiceBuilder service : services.values()) {
            Optional<ObJourneyTemplate> head =
                    templates.findTopByProductIdAndNameOrderByVersionDesc(productId, service.name);
            if (head.isEmpty()) {
                service.action = ServiceAction.CREATE;
            } else if (head.get().getPublishedAt() == null) {
                service.action = ServiceAction.REPLACE;
                service.targetTemplateId = head.get().getId();
            } else {
                errors.add(new ImportRowError(ObModuleServiceImportWorkbook.IMPORT_SHEET,
                        service.firstRowNumber,
                        "Module Service '" + service.name + "' is already published (v"
                                + head.get().getVersion() + "). Begin a revision on it before"
                                + " importing, so the clients already on it keep the version they"
                                + " were boarded on."));
            }
        }
    }

    /**
     * Where a newly created service sits in the catalogue order.
     *
     * <p>After every service that currently holds a place in it. A draft has
     * no place — the catalogue draws "Not in order" against one — so this only
     * decides where the service lands once somebody publishes it, and is not
     * worth a picker in the import dialog.
     */
    private int nextCatalogueSequence() {
        List<ObJourneyTemplate> active = templates.findByIsActiveTrueOrderBySequenceAsc();
        return active.isEmpty() ? 1 : active.get(active.size() - 1).getSequence() + 1;
    }

    private static ImportRowError rowError(ObModuleServiceImportWorkbook.RawRow row, String message) {
        return new ImportRowError(ObModuleServiceImportWorkbook.IMPORT_SHEET, row.rowNumber(), message);
    }

    /** Row 0 — a complaint about the file as a whole rather than about a line in it. */
    private static ImportRowError sheetError(String message) {
        return new ImportRowError(ObModuleServiceImportWorkbook.IMPORT_SHEET, 0, message);
    }

    private static String normalise(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ builders

    /**
     * The flat rows regrouped into the tree, keyed case-insensitively at each
     * level so "Data Migration" and "DATA MIGRATION" on two rows are one Step.
     *
     * <p>{@code LinkedHashMap} throughout: the tree's order is the order the
     * names first appeared in the file, which is the order the preview draws
     * and the order the tasks are written in. A {@code HashMap} would make the
     * imported journey's shape depend on string hashes.
     */
    private static final class ServiceBuilder {
        final String name;
        /** Where to hang a service-level complaint — the first row that named it. */
        final int firstRowNumber;
        final Map<String, StepBuilder> steps = new LinkedHashMap<>();
        ServiceAction action = ServiceAction.CREATE;
        Long targetTemplateId;

        ServiceBuilder(String name, int firstRowNumber) {
            this.name = name;
            this.firstRowNumber = firstRowNumber;
        }

        ImportedService build() {
            return new ImportedService(name, action,
                    steps.values().stream().map(StepBuilder::build).toList());
        }
    }

    private static final class StepBuilder {
        /** The master's spelling, not the file's — the name the group is created with. */
        final String name;
        final long implementationStageId;
        final Map<String, TaskBuilder> tasks = new LinkedHashMap<>();

        StepBuilder(String name, long implementationStageId) {
            this.name = name;
            this.implementationStageId = implementationStageId;
        }

        ImportedStep build() {
            return new ImportedStep(name, tasks.values().stream().map(TaskBuilder::build).toList());
        }
    }

    private static final class TaskBuilder {
        final String name;
        final List<String> checklist = new ArrayList<>();

        TaskBuilder(String name) {
            this.name = name;
        }

        /**
         * The task's checklist, or — when the file gave it none — a single
         * entry named after the task itself.
         *
         * <p>The Checklist column is optional, and in practice most of a real
         * file leaves it blank: the sheets these are transcribed from carry a
         * Pointers column and nothing below it. A task that imported with an
         * empty checklist is a task whose row on the client journey has
         * nothing to tick, so nobody can record it as done without the admin
         * going back into the designer to add the one line the file already
         * implied. Defaulting to the task's own name makes an imported service
         * usable the moment it lands, and an author who wants finer steps
         * still gets exactly what they typed.
         *
         * <p>Read by both {@link #preview} and {@link #commit} — via
         * {@link #build()} and directly, respectively — so the preview cannot
         * promise a checklist the commit then declines to write.
         */
        List<String> checklistOrDefault() {
            return checklist.isEmpty() ? List.of(name) : List.copyOf(checklist);
        }

        ImportedTask build() {
            return new ImportedTask(name, checklistOrDefault());
        }
    }
}
