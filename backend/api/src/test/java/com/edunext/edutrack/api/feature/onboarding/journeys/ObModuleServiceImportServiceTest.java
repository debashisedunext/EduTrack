package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObImplementationStage;
import com.edunext.edutrack.domain.onboarding.ObImplementationStageRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStage;
import com.edunext.edutrack.domain.onboarding.ObProduct;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ModuleImportPreview;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ModuleImportResult;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ServiceAction;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.TaskToWrite;

/**
 * OB-07 · {@link ObModuleServiceImportService} against real workbooks — the
 * parse-then-validate round trip {@code ObJourneyTemplateServiceTest}'s own
 * {@code replaceTasksFromImport} tests deliberately do not cover, since those
 * start from an already-resolved {@link TaskToWrite} list.
 *
 * <p>{@link ObJourneyTemplateService} is mocked throughout: what is under test
 * here is how flat rows become a tree and which files are refused, not the
 * write, which has its own tests one class over.
 */
class ObModuleServiceImportServiceTest {

    private static final long PRODUCT_ID = 7L;
    private static final long CALLER = 99L;

    private final ObModuleServiceImportWorkbook workbook = new ObModuleServiceImportWorkbook();
    private final ObJourneyTemplateRepository templates = mock(ObJourneyTemplateRepository.class);
    private final ObProductRepository products = mock(ObProductRepository.class);
    private final ObImplementationStageRepository implementationStages =
            mock(ObImplementationStageRepository.class);
    private final ObJourneyTemplateService templateService = mock(ObJourneyTemplateService.class);

    private final ObModuleServiceImportService service = new ObModuleServiceImportService(
            workbook, templates, products, implementationStages, templateService);

    private final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void wireMasters() {
        when(products.findById(PRODUCT_ID))
                .thenReturn(Optional.of(new ObProduct("EDUNEXT-ERP", "EduNext ERP", true, null)));
        when(implementationStages.findAllByIsActiveOrderBySequenceAscIdAsc(true))
                .thenReturn(List.of(stage("Configuration", 1), stage("Data Migration", 2)));
        // No service of any name exists yet, so every file CREATEs unless a
        // test says otherwise.
        when(templates.findTopByProductIdAndNameOrderByVersionDesc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(templates.findByIsActiveTrueOrderBySequenceAsc()).thenReturn(List.of());
    }

    private ObImplementationStage stage(String name, int sequence) {
        ObImplementationStage stage = new ObImplementationStage(name, sequence, true, null);
        setId(stage, ids.incrementAndGet());
        return stage;
    }

    /** The entity's id is database-generated; the fakes set it the way every other test here does. */
    private static void setId(Object entity, long id) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(entity.getClass() + ".id is no longer a field called id", e);
        }
    }

    // ------------------------------------------------------------------ workbook builder

    /** One row is {@code {Module Service, Step, Task, Checklist}}. */
    private static InputStream fileOf(String[]... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(ObModuleServiceImportWorkbook.IMPORT_SHEET);
            writeRow(sheet, 0, "Module Service", "Step", "Task", "Checklist");
            for (int r = 0; r < rows.length; r++) {
                writeRow(sheet, r + 1, rows[r]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private static void writeRow(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < values.length; c++) {
            row.createCell(c).setCellValue(values[c]);
        }
    }

    private static String[] row(String service, String step, String task, String checklist) {
        return new String[] { service, step, task, checklist };
    }

    private List<String> messages(ModuleImportPreview preview) {
        return preview.errors().stream().map(ObModuleServiceImportShapes.ImportRowError::message).toList();
    }

    // ------------------------------------------------------------------ grouping

    @Nested
    @DisplayName("flat rows become a tree")
    class Grouping {

        @Test
        @DisplayName("rows repeating one service, step and task are ONE task with two checklist items")
        void repeatedRowsCollapseIntoOneTask() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Student Data Port", "Validate source file"),
                    row("Admission Management", "Data Migration", "Student Data Port", "Reconcile record counts")));

            assertThat(preview.valid()).isTrue();
            assertThat(preview.services()).hasSize(1);
            assertThat(preview.services().get(0).steps()).hasSize(1);
            assertThat(preview.services().get(0).steps().get(0).tasks())
                    .singleElement()
                    .satisfies(task -> {
                        assertThat(task.name()).isEqualTo("Student Data Port");
                        assertThat(task.checklist())
                                .containsExactly("Validate source file", "Reconcile record counts");
                    });
        }

        /*
          Most of a real file leaves Checklist blank, and a task that imports
          with nothing to tick cannot be completed on the client journey
          without an admin going back into the designer. So a blank column
          defaults to one item named after the task - see
          TaskBuilder#checklistOrDefault.
        */
        @Test
        @DisplayName("a blank Checklist gives the task one item named after itself, not an empty one")
        void blankChecklistDefaultsToTheTaskName() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            assertThat(preview.valid()).isTrue();
            assertThat(preview.services().get(0).steps().get(0).tasks())
                    .singleElement()
                    .satisfies(task -> assertThat(task.checklist()).containsExactly("Enquiry Data Port"));
        }

        @Test
        @DisplayName("several services, steps and tasks keep the order they first appear in")
        void orderIsFileOrder() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", ""),
                    row("Admission Management", "Configuration", "Admission Form Setup", ""),
                    row("Fee Management", "Configuration", "Fee Head Setup", "")));

            assertThat(preview.services()).extracting(s -> s.name())
                    .containsExactly("Admission Management", "Fee Management");
            assertThat(preview.services().get(0).steps()).extracting(s -> s.name())
                    .containsExactly("Data Migration", "Configuration");
        }

        /*
          The file is typed by hand, so the same step arrives spelled three
          ways. Matching case-insensitively is what stops one Step becoming
          three stage groups - which the database would then refuse on
          uq_ob_template_stages, as a constraint violation rather than as
          anything an author could act on.
        */
        @Test
        @DisplayName("names match case-insensitively, so one step spelled two ways is one step")
        void namesAreCaseInsensitive() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", ""),
                    row("ADMISSION MANAGEMENT", "DATA MIGRATION", "enquiry data port", "A late check")));

            assertThat(preview.valid()).isTrue();
            assertThat(preview.services()).hasSize(1);
            assertThat(preview.services().get(0).steps()).hasSize(1);
            assertThat(preview.services().get(0).steps().get(0).tasks())
                    .singleElement()
                    .satisfies(task -> assertThat(task.checklist()).containsExactly("A late check"));
        }

        @Test
        @DisplayName("the step takes the master's spelling, not the file's")
        void stepTakesTheMastersSpelling() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "dATA mIGRATION", "Enquiry Data Port", "")));

            assertThat(preview.services().get(0).steps().get(0).name()).isEqualTo("Data Migration");
        }
    }

    // ------------------------------------------------------------------ validation

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a blank Module Service, Step or Task is a row error naming the column")
        void blankRequiredCells() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("", "Data Migration", "Enquiry Data Port", ""),
                    row("Admission Management", "", "Enquiry Data Port", ""),
                    row("Admission Management", "Data Migration", "", "")));

            assertThat(preview.valid()).isFalse();
            assertThat(messages(preview)).containsExactly(
                    "Module Service is required on every row.",
                    "Step is required on every row.",
                    "Task is required on every row.");
            assertThat(preview.errors()).extracting(e -> e.rowNumber()).containsExactly(2, 3, 4);
        }

        @Test
        @DisplayName("a Step the Implementation Stage master does not carry is refused, not created")
        void unknownStepIsRefused() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Invented Stage", "Enquiry Data Port", "")));

            assertThat(preview.valid()).isFalse();
            assertThat(messages(preview)).singleElement().asString()
                    .contains("'Invented Stage' is not one of the Steps")
                    .contains("Implementation Stage master");
        }

        /*
          Seeding reads active stages only, so a retired one must not arrive by
          the back door - see ObJourneyTemplateService#ensureStageGroup. The
          master mock returns active stages alone, which is exactly how the
          service sees a retired one: absent.
        */
        @Test
        @DisplayName("a retired Step is refused, because seeding would never have produced it")
        void retiredStepIsRefused() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Third Party Integration", "Enquiry Data Port", "")));

            assertThat(preview.valid()).isFalse();
            assertThat(messages(preview)).singleElement().asString()
                    .contains("'Third Party Integration' is not one of the Steps");
        }

        @Test
        @DisplayName("a file with no rows names the sheet rather than failing silently")
        void emptyFileIsRefused() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf());

            assertThat(preview.valid()).isFalse();
            assertThat(messages(preview)).singleElement().asString().contains("No rows found");
        }

        @Test
        @DisplayName("an unknown product is refused before the file is even read")
        void unknownProductIsRefused() throws IOException {
            when(products.findById(404L)).thenReturn(Optional.empty());

            ModuleImportPreview preview = service.preview(404L, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            assertThat(preview.valid()).isFalse();
            assertThat(messages(preview)).singleElement().asString().contains("No such product");
        }

        @Test
        @DisplayName("a published Module Service is refused, and says to begin a revision")
        void publishedServiceIsRefused() throws IOException {
            when(templates.findTopByProductIdAndNameOrderByVersionDesc(PRODUCT_ID, "Admission Management"))
                    .thenReturn(Optional.of(published(3)));

            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            assertThat(preview.valid()).isFalse();
            assertThat(messages(preview)).singleElement().asString()
                    .contains("already published (v3)")
                    .contains("Begin a revision");
        }

        @Test
        @DisplayName("the service-level refusal points at the first row that named it")
        void serviceErrorPointsAtItsFirstRow() throws IOException {
            when(templates.findTopByProductIdAndNameOrderByVersionDesc(PRODUCT_ID, "Fee Management"))
                    .thenReturn(Optional.of(published(1)));

            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", ""),
                    row("Fee Management", "Configuration", "Fee Head Setup", ""),
                    row("Fee Management", "Configuration", "Fee Head Setup", "A check")));

            assertThat(preview.errors()).singleElement()
                    .satisfies(e -> assertThat(e.rowNumber()).isEqualTo(3));
        }
    }

    // ------------------------------------------------------------------ targets

    @Nested
    @DisplayName("what each named service resolves to")
    class Targets {

        @Test
        @DisplayName("a service the product does not have will be CREATEd")
        void absentServiceIsCreated() throws IOException {
            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            assertThat(preview.services()).singleElement()
                    .satisfies(s -> assertThat(s.action()).isEqualTo(ServiceAction.CREATE));
        }

        @Test
        @DisplayName("a service whose latest version is an unpublished draft will be REPLACEd")
        void draftServiceIsReplaced() throws IOException {
            when(templates.findTopByProductIdAndNameOrderByVersionDesc(PRODUCT_ID, "Admission Management"))
                    .thenReturn(Optional.of(draft(55L)));

            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            assertThat(preview.services()).singleElement()
                    .satisfies(s -> assertThat(s.action()).isEqualTo(ServiceAction.REPLACE));
        }
    }

    // ------------------------------------------------------------------ commit

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("binds each step, then writes the tasks it resolved them to")
        void writesThroughTheTemplateService() throws IOException {
            when(templates.findTopByProductIdAndNameOrderByVersionDesc(PRODUCT_ID, "Admission Management"))
                    .thenReturn(Optional.of(draft(55L)));
            when(templateService.ensureStageGroup(anyLong(), anyLong()))
                    .thenAnswer(inv -> group(inv.getArgument(1)));

            ModuleImportResult result = service.commit(PRODUCT_ID, CALLER, fileOf(
                    row("Admission Management", "Data Migration", "Student Data Port", "Validate source file"),
                    row("Admission Management", "Data Migration", "Student Data Port", "Reconcile record counts"),
                    row("Admission Management", "Configuration", "Admission Form Setup", "")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TaskToWrite>> written = ArgumentCaptor.forClass(List.class);
            verify(templateService).replaceTasksFromImport(anyLong(), written.capture());

            assertThat(written.getValue()).extracting(TaskToWrite::name)
                    .containsExactly("Student Data Port", "Admission Form Setup");
            assertThat(written.getValue().get(0).checklist())
                    .containsExactly("Validate source file", "Reconcile record counts");
            // Blank Checklist column, so this one carries the defaulted item.
            assertThat(written.getValue().get(1).checklist())
                    .containsExactly("Admission Form Setup");
            assertThat(result.servicesCreated()).isZero();
            assertThat(result.servicesReplaced()).isEqualTo(1);
            assertThat(result.stepCount()).isEqualTo(2);
            assertThat(result.taskCount()).isEqualTo(2);
            assertThat(result.checklistCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("creates a draft for a service the product does not have yet")
        void createsAnAbsentService() throws IOException {
            ObJourneyTemplate created = draft(77L);
            when(templateService.createTemplate(anyLong(), anyString(), anyInt(), any(), anyLong()))
                    .thenReturn(created);
            when(templateService.ensureStageGroup(anyLong(), anyLong()))
                    .thenAnswer(inv -> group(inv.getArgument(1)));

            ModuleImportResult result = service.commit(PRODUCT_ID, CALLER, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            verify(templateService).createTemplate(PRODUCT_ID, "Admission Management", 1, List.of(), CALLER);
            verify(templateService).ensureStageGroup(anyLong(), anyLong());
            assertThat(result.servicesCreated()).isEqualTo(1);
            assertThat(result.servicesReplaced()).isZero();
        }

        /*
          The default is applied in two places - build() for the preview and
          checklistOrDefault() for the write - so the pair is worth pinning:
          a preview that promised a checklist the commit then declined to
          write would be a confirm screen that lies.
        */
        @Test
        @DisplayName("the defaulted checklist the preview shows is the one the commit writes")
        void previewAndCommitAgreeOnTheDefault() throws IOException {
            when(templates.findTopByProductIdAndNameOrderByVersionDesc(PRODUCT_ID, "Admission Management"))
                    .thenReturn(Optional.of(draft(55L)));
            when(templateService.ensureStageGroup(anyLong(), anyLong()))
                    .thenAnswer(inv -> group(inv.getArgument(1)));

            ModuleImportPreview preview = service.preview(PRODUCT_ID, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));
            service.commit(PRODUCT_ID, CALLER, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", "")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TaskToWrite>> written = ArgumentCaptor.forClass(List.class);
            verify(templateService).replaceTasksFromImport(anyLong(), written.capture());

            assertThat(written.getValue().get(0).checklist())
                    .isEqualTo(preview.services().get(0).steps().get(0).tasks().get(0).checklist());
        }

        /*
          The check that matters. A file whose last row is bad must write none
          of the rows before it - the transaction is what guarantees that in
          production, but nothing may be attempted before validation has
          passed, or a rollback is the only thing standing between a typo and
          a half-built catalogue.
        */
        @Test
        @DisplayName("one bad row writes nothing at all, not even the services before it")
        void oneBadRowWritesNothing() throws IOException {
            assertThatThrownBy(() -> service.commit(PRODUCT_ID, CALLER, fileOf(
                    row("Admission Management", "Data Migration", "Enquiry Data Port", ""),
                    row("Fee Management", "Invented Stage", "Fee Head Setup", ""))))
                    .isInstanceOf(ModuleImportValidationException.class);

            verify(templateService, never()).createTemplate(anyLong(), anyString(), anyInt(), any(), anyLong());
            verify(templateService, never()).ensureStageGroup(anyLong(), anyLong());
            verify(templateService, never()).replaceTasksFromImport(anyLong(), any());
        }
    }

    // ------------------------------------------------------------------ round trip

    @Test
    @DisplayName("the downloadable template's own example rows validate against a live master")
    void generatedTemplateRoundTrips() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeTemplate(out);

        ModuleImportPreview preview =
                service.preview(PRODUCT_ID, new ByteArrayInputStream(out.toByteArray()));

        assertThat(preview.errors()).isEmpty();
        assertThat(preview.valid()).isTrue();
        assertThat(preview.services()).extracting(s -> s.name())
                .containsExactly("Admission Management", "Fee Management");
    }

    // ------------------------------------------------------------------ fixtures

    private ObJourneyTemplate draft(long id) {
        ObJourneyTemplate template = new ObJourneyTemplate();
        template.setId(id);
        template.setProductId(PRODUCT_ID);
        template.setVersion(1);
        return template;
    }

    private ObJourneyTemplate published(int version) {
        ObJourneyTemplate template = draft(ids.incrementAndGet());
        template.setVersion(version);
        template.setPublishedAt(Instant.now());
        return template;
    }

    private ObJourneyTemplateStage group(long implementationStageId) {
        ObJourneyTemplateStage stage =
                new ObJourneyTemplateStage(55L, implementationStageId, "Bound", 1);
        stage.setId(1000L + implementationStageId);
        return stage;
    }
}
