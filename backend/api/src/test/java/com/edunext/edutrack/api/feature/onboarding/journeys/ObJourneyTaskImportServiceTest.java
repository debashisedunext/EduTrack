package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStage;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStageRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.TaskImportPreview;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.TaskImportResult;

/**
 * OB-07 · {@link ObJourneyTaskImportService} against a real workbook built by
 * {@link ObJourneyTaskImportWorkbook} — this is the parse-then-validate round
 * trip {@code ObJourneyTemplateServiceTest}'s own {@code replaceTasksFromImport}
 * tests deliberately do not cover, since those start from an already-validated
 * {@code ImportedTask} list. {@code ObJourneyTemplateService} itself is mocked:
 * what is under test here is row parsing and validation, not the write.
 */
class ObJourneyTaskImportServiceTest {

    private static final long TEMPLATE_ID = 42L;

    private final ObJourneyTaskImportWorkbook workbook = new ObJourneyTaskImportWorkbook();
    private final ObJourneyTemplateRepository templates = mock(ObJourneyTemplateRepository.class);
    private final ObJourneyTemplateStageRepository stageGroups = mock(ObJourneyTemplateStageRepository.class);
    private final ObJourneyTemplateService templateService = mock(ObJourneyTemplateService.class);

    private final ObJourneyTaskImportService service =
            new ObJourneyTaskImportService(workbook, templates, stageGroups, templateService);

    private ObJourneyTemplate draft;

    @BeforeEach
    void draftTemplate() {
        draft = mock(ObJourneyTemplate.class);
        when(draft.getPublishedAt()).thenReturn(null);
        when(templates.findById(TEMPLATE_ID)).thenReturn(Optional.of(draft));
        when(stageGroups.findByTemplateIdOrderBySequenceAscIdAsc(TEMPLATE_ID))
                .thenReturn(List.of(stage("Configuration"), stage("Training")));
    }

    private static ObJourneyTemplateStage stage(String name) {
        ObJourneyTemplateStage group = new ObJourneyTemplateStage(TEMPLATE_ID, null, name, 1);
        return group;
    }

    // ------------------------------------------------------------------ workbook builder

    private static InputStream workbookOf(String[][] tasks, String[][] items, String[][] docs) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSheet(wb, ObJourneyTaskImportWorkbook.TASKS_SHEET,
                    new String[] { "Stage", "Task Name", "Description", "TAT Days", "Requires Sign-off (Y/N)",
                            "Depends On Task" },
                    tasks);
            writeSheet(wb, ObJourneyTaskImportWorkbook.ITEMS_SHEET,
                    new String[] { "Task Name", "Item Label", "Mandatory (Y/N)" }, items);
            writeSheet(wb, ObJourneyTaskImportWorkbook.DOCS_SHEET,
                    new String[] { "Task Name", "Document Label", "Required (Y/N)" }, docs);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private static void writeSheet(XSSFWorkbook wb, String name, String[] header, String[][] rows) {
        Sheet sheet = wb.createSheet(name);
        writeRow(sheet, 0, header);
        for (int r = 0; r < rows.length; r++) {
            writeRow(sheet, r + 1, rows[r]);
        }
    }

    private static void writeRow(Sheet sheet, int index, String[] values) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < values.length; c++) {
            row.createCell(c).setCellValue(values[c]);
        }
    }

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("a well-formed workbook parses into a valid tree, checklist attached")
        void wellFormedWorkbookIsValid() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "Configuration", "Kickoff call", "Intro", "2", "N", "" } },
                    new String[][] { { "Kickoff call", "Order form signed", "Y" } },
                    new String[][] { { "Kickoff call", "Signed contract", "Y" } });

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isTrue();
            assertThat(preview.errors()).isEmpty();
            assertThat(preview.tasks()).hasSize(1);
            var task = preview.tasks().get(0);
            assertThat(task.name()).isEqualTo("Kickoff call");
            assertThat(task.stageGroupName()).isEqualTo("Configuration");
            assertThat(task.tatDays()).isEqualTo(2);
            assertThat(task.requiresSignoff()).isFalse();
            assertThat(task.items()).extracting(ObJourneyTaskImportShapes.ImportedItem::label)
                    .containsExactly("Order form signed");
            assertThat(task.docs()).extracting(ObJourneyTaskImportShapes.ImportedDoc::label)
                    .containsExactly("Signed contract");
        }

        @Test
        @DisplayName("a blank stage files the task under Ungrouped rather than failing")
        void blankStageIsAllowed() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "", "Kickoff call", "", "1", "", "" } }, new String[0][], new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isTrue();
            assertThat(preview.tasks().get(0).stageGroupName()).isNull();
        }

        @Test
        @DisplayName("a stage that is not one of this template's groups is rejected, naming the row")
        void unknownStageIsRejected() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "Not A Real Stage", "Kickoff call", "", "1", "", "" } },
                    new String[0][], new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors()).hasSize(1);
            assertThat(preview.errors().get(0).rowNumber()).isEqualTo(2);
            assertThat(preview.errors().get(0).message()).contains("Not A Real Stage");
        }

        @Test
        @DisplayName("TAT Days must be a positive whole number")
        void tatDaysMustBePositive() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "", "Kickoff call", "", "0", "", "" } }, new String[0][], new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors().get(0).message()).contains("TAT Days");
        }

        @Test
        @DisplayName("two tasks with the same name in one file are refused")
        void duplicateTaskNamesAreRejected() throws IOException {
            InputStream file = workbookOf(
                    new String[][] {
                            { "", "Kickoff call", "", "1", "", "" },
                            { "", "Kickoff call", "", "1", "", "" },
                    }, new String[0][], new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors().get(0).rowNumber()).isEqualTo(3);
            assertThat(preview.errors().get(0).message()).contains("Duplicate");
        }

        @Test
        @DisplayName("Depends On Task must name a task earlier in the same sheet")
        void dependsOnMustBeEarlier() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "", "Data migration", "", "1", "", "Kickoff call" } },
                    new String[0][], new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors().get(0).message()).contains("Depends On Task");
        }

        @Test
        @DisplayName("a Task List row naming a task the Tasks sheet does not have is refused")
        void itemsMustReferenceARealTask() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "", "Kickoff call", "", "1", "", "" } },
                    new String[][] { { "Some Other Task", "A checklist item", "Y" } },
                    new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors().get(0).sheet()).isEqualTo(ObJourneyTaskImportWorkbook.ITEMS_SHEET);
        }

        @Test
        @DisplayName("an empty Tasks sheet is one file-level error, not silently nothing to import")
        void emptyTasksSheetIsRejected() throws IOException {
            InputStream file = workbookOf(new String[0][], new String[0][], new String[0][]);

            TaskImportPreview preview = service.preview(TEMPLATE_ID, file);

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors()).hasSize(1);
        }

        @Test
        @DisplayName("a published template is refused before the file is even read")
        void publishedTemplateIsRefused() throws IOException {
            when(draft.getPublishedAt()).thenReturn(java.time.Instant.now());

            TaskImportPreview preview = service.preview(TEMPLATE_ID,
                    workbookOf(new String[0][], new String[0][], new String[0][]));

            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors().get(0).message()).contains("published");
        }
    }

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("a valid file is handed to ObJourneyTemplateService.replaceTasksFromImport, and counts add up")
        void validFileReplacesTheTree() throws IOException {
            InputStream file = workbookOf(
                    new String[][] { { "", "Kickoff call", "", "1", "N", "" } },
                    new String[][] { { "Kickoff call", "Item one", "Y" }, { "Kickoff call", "Item two", "N" } },
                    new String[][] { { "Kickoff call", "Doc one", "Y" } });

            TaskImportResult result = service.commit(TEMPLATE_ID, file);

            assertThat(result.taskCount()).isEqualTo(1);
            assertThat(result.itemCount()).isEqualTo(2);
            assertThat(result.docCount()).isEqualTo(1);
            verify(templateService).replaceTasksFromImport(org.mockito.ArgumentMatchers.eq(TEMPLATE_ID), any());
        }

        @Test
        @DisplayName("an invalid file is refused before ObJourneyTemplateService is ever called")
        void invalidFileNeverReachesTheTemplateService() {
            org.junit.jupiter.api.Assertions.assertThrows(TaskImportValidationException.class, () ->
                    service.commit(TEMPLATE_ID, workbookOf(
                            new String[][] { { "", "Kickoff call", "", "not a number", "", "" } },
                            new String[0][], new String[0][])));

            verify(templateService, never()).replaceTasksFromImport(anyLong(), any());
        }
    }
}
