package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-019 · the Settings tab's decisions, against a mocked repository.
 *
 * <p>The bulk of it is one rule stated from several directions: <b>an empty
 * allow-list means unrestricted, not empty</b>. Everything else on this screen
 * is bookkeeping; that one is the difference between a migration that ships and
 * a migration that stops ticket creation across the organisation.
 *
 * <p>{@code ProjectSettingsIT} proves the same behaviours against real MySQL,
 * including the two things a mock cannot settle — that the {@code JSON} column
 * round-trips and that the {@code CHECK} accepts what this writes.
 */
class ProjectSettingsServiceTest {

    private static final long PROJECT = 7L;

    private ProjectSettingsRepository repository;
    private ProjectSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectSettingsRepository.class);
        service = new ProjectSettingsService(repository);

        when(repository.projectExists(PROJECT)).thenReturn(true);
        when(repository.taskTypeExists(anyInt())).thenReturn(true);
        when(repository.settings(PROJECT)).thenReturn(row("MANUAL"));
        when(repository.taskTypesFor(PROJECT)).thenReturn(List.of(
                taskType(1, "CHANGE_REQUEST", "Change Request", true, false),
                taskType(2, "PROD_BUG", "Production Bug", true, false),
                taskType(3, "CLIENT_REQUEST", "Client Request", true, false)));
    }

    // ------------------------------------------------------------------
    // the rule the feature turns on
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an empty allow-list means unrestricted")
    class Unrestricted {

        @Test
        @DisplayName("no rows: restrictsTaskTypes is false and every active type reads as allowed")
        void noRowsAllowsEverything() {
            ProjectSettingsDtos.ProjectSettings settings = service.settings(PROJECT);

            assertThat(settings.restrictsTaskTypes()).isFalse();
            assertThat(settings.taskTypes())
                    .extracting(ProjectSettingsDtos.SettingsTaskType::isAllowed)
                    .containsOnly(true);
        }

        @Test
        @DisplayName("one row: the project is restricted and the other two are refused")
        void oneRowRestrictsTheRest() {
            when(repository.taskTypesFor(PROJECT)).thenReturn(List.of(
                    taskType(1, "CHANGE_REQUEST", "Change Request", true, false),
                    taskType(2, "PROD_BUG", "Production Bug", true, true),
                    taskType(3, "CLIENT_REQUEST", "Client Request", true, false)));

            ProjectSettingsDtos.ProjectSettings settings = service.settings(PROJECT);

            assertThat(settings.restrictsTaskTypes()).isTrue();
            assertThat(allowedIds(settings)).containsExactly(2);
        }

        @Test
        @DisplayName("saving an empty list clears the rows rather than writing none of them")
        void anEmptyWriteClearsTheRestriction() {
            // The request an administrator makes by unticking the last box. It
            // is a legitimate one and it removes the restriction — a project
            // that allowed no task type could raise no ticket, so that state
            // does not exist and there is deliberately no control that reaches
            // it.
            service.replace(PROJECT, write("MANUAL", List.of(), List.of()));

            verify(repository).clearAllowedTaskTypes(PROJECT);
            verify(repository, never()).allowTaskType(anyLong(), anyInt());
        }
    }

    // ------------------------------------------------------------------
    // the retired task type
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a retired task type this project still allows")
    class Retired {

        @Test
        @DisplayName("is returned, flagged inactive, so the next save cannot drop it silently")
        void isRenderedSoItSurvivesTheReplace() {
            // The repository's LEFT JOIN is what puts it in the list. If it
            // were not there, the screen would assemble a PUT from the rows it
            // was given and delete a membership it never displayed — the
            // deletion-by-omission SlaMatrixRepository guards against for
            // project-level SLA defaults.
            when(repository.taskTypesFor(PROJECT)).thenReturn(List.of(
                    taskType(2, "PROD_BUG", "Production Bug", true, true),
                    taskType(9, "BROWSER_ISSUE", "Browser Issue", false, true)));

            ProjectSettingsDtos.ProjectSettings settings = service.settings(PROJECT);

            assertThat(settings.taskTypes()).hasSize(2);
            assertThat(settings.taskTypes().get(1).isActive()).isFalse();
            assertThat(settings.taskTypes().get(1).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("may still be written — existence is checked, activity is not")
        void mayStillBeWritten() {
            // Refusing the id would make the row uneditable and unremovable
            // through the only screen that can see it.
            when(repository.taskTypeExists(9)).thenReturn(true);

            service.replace(PROJECT, write("MANUAL", List.of(), List.of(9)));

            verify(repository).allowTaskType(PROJECT, 9);
        }
    }

    // ------------------------------------------------------------------
    // mandatory fields
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("mandatory fields")
    class MandatoryFields {

        @Test
        @DisplayName("a stored NULL and a stored [] both read as none")
        void nullAndEmptyAreTheSame() {
            // The column is nullable, every row predating this feature holds
            // NULL, and the screen writes [] when the last box is unticked.
            // Two representations of one state is a distinction every reader
            // would otherwise have to remember.
            when(repository.settings(PROJECT)).thenReturn(row("MANUAL", List.of()));
            assertThat(service.settings(PROJECT).mandatoryFields()).isEmpty();
        }

        @Test
        @DisplayName("are stored upper-cased, in the order sent")
        void areNormalised() {
            service.replace(PROJECT, write("MANUAL", List.of("module", "DESCRIPTION"), List.of()));

            verify(repository).updateSettings(PROJECT, "MANUAL", List.of("MODULE", "DESCRIPTION"));
        }

        @Test
        @DisplayName("a code outside the vocabulary is a 400 keyed on the field")
        void anUnknownCodeIsRefused() {
            assertThatThrownBy(() -> service.replace(PROJECT,
                    write("MANUAL", List.of("TICKET_ID"), List.of())))
                    .isInstanceOf(ProjectSettingsService.SettingsValidationException.class)
                    .hasMessageContaining("no such ticket field: TICKET_ID");
        }

        @Test
        @DisplayName("the vocabulary excludes every field TicketCreateRequest already requires")
        void excludesTheAlwaysRequiredFields() {
            // A checkbox that cannot change the outcome is a control that lies:
            // somebody ticks it and believes something happened.
            assertThat(ProjectSettingsService.FIELD_NAMES)
                    .doesNotContain("PROJECT", "PROJECT_ID", "TITLE", "TASK_TYPE", "TASK_TYPE_ID", "LEVEL");
        }

        @Test
        @DisplayName("an unrecognised stored code is dropped, not thrown on")
        void anUnrecognisedStoredCodeIsDropped() {
            // ck_projects_mandatory_fields constrains shape and not vocabulary,
            // so a value this build has never heard of is reachable after a
            // rollback. A read that threw would leave the only screen that
            // could repair it behind the failure.
            when(repository.settings(PROJECT)).thenReturn(row("MANUAL", List.of("MODULE", "WHAT_IS_THIS")));

            assertThat(service.settings(PROJECT).mandatoryFields())
                    .containsExactly(ProjectSettingsDtos.TicketField.MODULE);
        }
    }

    // ------------------------------------------------------------------
    // the auto-assign rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the auto-assign rule")
    class Rule {

        @Test
        @DisplayName("is one vocabulary shared with the General tab, and it is the CHECK's")
        void isOneVocabulary() {
            // ProjectService validated this against a Set<String> of its own
            // until B-019. Two lists for one column is how two screens end up
            // accepting different values for it.
            assertThat(ProjectSettingsService.RULE_NAMES)
                    .containsExactly("ROUND_ROBIN", "LEAST_LOADED", "MANUAL");
        }

        @Test
        @DisplayName("is upper-cased on the way in")
        void isUpperCased() {
            service.replace(PROJECT, write("round_robin", List.of(), List.of()));

            verify(repository).updateSettings(PROJECT, "ROUND_ROBIN", List.of());
        }

        @Test
        @DisplayName("an unknown value is a 400 keyed on the field, not a 500 from valueOf")
        void anUnknownRuleIsRefused() {
            assertThatThrownBy(() -> service.replace(PROJECT,
                    write("WHOEVER_IS_FREE", List.of(), List.of())))
                    .isInstanceOf(ProjectSettingsService.SettingsValidationException.class)
                    .hasMessageContaining("must be one of");
        }

        @Test
        @DisplayName("null falls back to MANUAL rather than to whatever is stored")
        void nullIsManual() {
            // Round-robin and least-loaded both assign live work to somebody
            // without a human deciding, which is not a behaviour a project
            // should acquire by omission — B-016's migration makes the same
            // argument for the column default.
            service.replace(PROJECT, write(null, List.of(), List.of()));

            verify(repository).updateSettings(PROJECT, "MANUAL", List.of());
        }
    }

    // ------------------------------------------------------------------
    // refusals
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unknown project is 404 on the read, never 403")
        void unknownProjectRead() {
            assertThatThrownBy(() -> service.settings(999L))
                    .isInstanceOf(ProjectSettingsService.NoSuchProjectException.class);
        }

        @Test
        @DisplayName("an unknown project is 404 on the write, and nothing is written")
        void unknownProjectWrite() {
            assertThatThrownBy(() -> service.replace(999L, write("MANUAL", List.of(), List.of())))
                    .isInstanceOf(ProjectSettingsService.NoSuchProjectException.class);

            verify(repository, never()).updateSettings(anyLong(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("a task type listed twice is refused before the insert can fail on the primary key")
        void duplicateTaskType() {
            // The composite primary key would refuse the second insert with a
            // message naming a MySQL index, which is a 500 the caller cannot
            // act on rather than a rule they can fix.
            assertThatThrownBy(() -> service.replace(PROJECT,
                    write("MANUAL", List.of(), List.of(2, 2))))
                    .isInstanceOf(ProjectSettingsService.SettingsValidationException.class)
                    .hasMessageContaining("listed twice");
        }

        @Test
        @DisplayName("a field listed twice is refused rather than quietly de-duplicated")
        void duplicateField() {
            assertThatThrownBy(() -> service.replace(PROJECT,
                    write("MANUAL", List.of("MODULE", "module"), List.of())))
                    .isInstanceOf(ProjectSettingsService.SettingsValidationException.class)
                    .hasMessageContaining("listed twice");
        }

        @Test
        @DisplayName("an unknown task type is refused, and nothing is written")
        void unknownTaskType() {
            when(repository.taskTypeExists(404)).thenReturn(false);

            assertThatThrownBy(() -> service.replace(PROJECT,
                    write("MANUAL", List.of(), List.of(404))))
                    .isInstanceOf(ProjectSettingsService.SettingsValidationException.class)
                    .hasMessageContaining("no such task type: 404");

            // Everything is validated before anything is written. The body is
            // one transaction, so a value refused halfway through would roll
            // back the rows before it and the caller would be told about the
            // fourth id of a save that also silently did not apply the first
            // three.
            verify(repository, never()).clearAllowedTaskTypes(anyLong());
        }
    }

    // ------------------------------------------------------------------
    // the replace
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the replace")
    class Replace {

        @Test
        @DisplayName("clears before it inserts, so a removed task type cannot survive")
        void clearsThenInserts() {
            service.replace(PROJECT, write("LEAST_LOADED", List.of("MODULE"), List.of(1, 3)));

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
            order.verify(repository).clearAllowedTaskTypes(PROJECT);
            order.verify(repository).allowTaskType(PROJECT, 1);
            order.verify(repository).allowTaskType(PROJECT, 3);
        }

        @Test
        @DisplayName("answers with the settings as they now read, not with what was sent")
        void answersWithTheStoredState() {
            // The two differ wherever the allow-list was cleared: the request
            // said [] and the response says "every task type is allowed".
            ProjectSettingsDtos.ProjectSettings saved =
                    service.replace(PROJECT, write("MANUAL", List.of(), List.of()));

            assertThat(saved.restrictsTaskTypes()).isFalse();
            assertThat(saved.taskTypes())
                    .extracting(ProjectSettingsDtos.SettingsTaskType::isAllowed)
                    .containsOnly(true);
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static List<Integer> allowedIds(ProjectSettingsDtos.ProjectSettings settings) {
        return settings.taskTypes().stream()
                .filter(ProjectSettingsDtos.SettingsTaskType::isAllowed)
                .map(ProjectSettingsDtos.SettingsTaskType::taskTypeId)
                .toList();
    }

    private static ProjectSettingsRepository.SettingsRow row(String rule) {
        return row(rule, List.of());
    }

    private static ProjectSettingsRepository.SettingsRow row(String rule, List<String> fields) {
        return new ProjectSettingsRepository.SettingsRow(rule, fields);
    }

    private static ProjectSettingsRepository.TaskTypeRow taskType(
            int id, String code, String name, boolean active, boolean allowed) {
        return new ProjectSettingsRepository.TaskTypeRow(id, code, name, active, allowed);
    }

    private static ProjectSettingsDtos.ProjectSettingsWrite write(
            String rule, List<String> fields, List<Integer> taskTypeIds) {
        return new ProjectSettingsDtos.ProjectSettingsWrite(rule, fields, taskTypeIds);
    }
}
