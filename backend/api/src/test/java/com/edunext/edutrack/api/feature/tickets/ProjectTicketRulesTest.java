package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-071 · the rules B-019's settings turn into, without a database.
 *
 * <p>Four things are asserted because each is invisible when it goes wrong:
 *
 * <ul>
 *   <li><b>An empty allow-list allows everything.</b> Reversed, this stops
 *       ticket creation on every project in the organisation at once, and every
 *       project is in that state until somebody configures one — so the wrong
 *       answer is also the universal one;</li>
 *   <li><b>an unrecognised stored code is ignored.</b> Thrown on, it would be a
 *       requirement nobody can satisfy, blocking a project permanently through a
 *       screen the same code has already been dropped from;</li>
 *   <li><b>the error keys are the form's field names.</b> A message under the
 *       wrong key renders as an empty banner over a form that looks fine, which
 *       no compiler and no round-trip test can see;</li>
 *   <li><b>an empty rich-text editor is not an answer.</b> {@code <p><br></p>}
 *       is what a focused-and-abandoned editor holds, and it is 13 characters
 *       that {@code isBlank()} calls present.</li>
 * </ul>
 */
class ProjectTicketRulesTest {

    private final RichTextSanitizer sanitizer = new RichTextSanitizer();

    // ------------------------------------------------------------------
    // The allow-list
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Task type allow-list")
    class TaskTypes {

        @Test
        @DisplayName("no rows means unrestricted — every task type is allowed")
        void emptyAllowsEverything() {
            assertThat(ProjectTicketRules.taskTypeRefused(Set.of(), 7)).isFalse();
        }

        @Test
        @DisplayName("a configured project refuses a type outside its list")
        void refusesOutsideTheList() {
            assertThat(ProjectTicketRules.taskTypeRefused(Set.of(2, 5), 7)).isTrue();
        }

        @Test
        @DisplayName("and accepts one inside it")
        void acceptsInsideTheList() {
            assertThat(ProjectTicketRules.taskTypeRefused(Set.of(2, 5), 5)).isFalse();
        }

        @Test
        @DisplayName("a null task type is Bean Validation's to refuse, not this rule's")
        void nullIsNotThisRulesQuestion() {
            assertThat(ProjectTicketRules.taskTypeRefused(Set.of(2, 5), null)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Mandatory fields
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Mandatory fields")
    class MandatoryFields {

        @Test
        @DisplayName("no configured fields means nothing to report")
        void noneConfigured() {
            assertThat(missing(List.of(), empty())).isEmpty();
        }

        @Test
        @DisplayName("an unrecognised code is dropped, never a requirement nobody can meet")
        void unknownCodeIsIgnored() {
            assertThat(missing(List.of("SOMETHING_A_LATER_VERSION_ADDED"), empty())).isEmpty();
        }

        @Test
        @DisplayName("a code stored in the wrong case still counts")
        void codeMatchingIsCaseInsensitive() {
            assertThat(missing(List.of("  module  "), empty())).containsOnlyKeys("moduleId");
        }

        /**
         * Every code, one test. The point is not each individual mapping so much
         * as that no code silently maps to nothing — a field the enum knows and
         * {@code isAnswered} forgets would be a checkbox that saves, renders as
         * ticked, and does nothing, which is the exact failure this whole task
         * exists to fix.
         */
        @ParameterizedTest
        @EnumSource(ProjectTicketRules.RequiredField.class)
        @DisplayName("every configured field is reported when the request leaves it empty")
        void everyFieldIsEnforced(ProjectTicketRules.RequiredField field) {
            Map<String, String> missing = ProjectTicketRules.missingFields(
                    List.of(field.name()), empty(), null, sanitizer);

            assertThat(missing).containsOnlyKeys(field.requestField());
        }

        @ParameterizedTest
        @EnumSource(ProjectTicketRules.RequiredField.class)
        @DisplayName("and is satisfied when it is answered")
        void everyFieldIsSatisfiable(ProjectTicketRules.RequiredField field) {
            Map<String, String> missing = ProjectTicketRules.missingFields(
                    List.of(field.name()), answered(), Instant.parse("2026-09-01T09:00:00Z"), sanitizer);

            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("all missing fields are reported together, not one per round trip")
        void reportsAllAtOnce() {
            Map<String, String> missing =
                    missing(List.of("MODULE", "ESTIMATED_HRS", "SCREEN_NAME"), empty());

            assertThat(missing).containsOnlyKeys("moduleId", "estimatedHrs", "screenName");
        }

        @Test
        @DisplayName("the keys are the create form's field names")
        void keysMatchTheForm() {
            for (ProjectTicketRules.RequiredField field : ProjectTicketRules.RequiredField.values()) {
                assertThat(field.requestField())
                        .as("%s must be reported under a TicketCreateRequest property", field)
                        .isIn("description", "moduleId", "screenName", "feature", "stepsToGenerate",
                                "clientId", "clientContactId", "assigneeId", "estimatedHrs",
                                "plannedCloseDate");
            }
        }
    }

    // ------------------------------------------------------------------
    // Rich text
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Rich text is answered when a reader would see something")
    class RichText {

        @Test
        @DisplayName("an editor that was focused and left is not an answer")
        void emptyEditorMarkupIsNotAnAnswer() {
            assertThat(missing(List.of("DESCRIPTION"), with(empty(), "<p><br></p>")))
                    .containsOnlyKeys("description");
        }

        @Test
        @DisplayName("markup with no prose in it is not an answer either")
        void markupWithoutProse() {
            assertThat(missing(List.of("DESCRIPTION"), with(empty(), "<ul><li>  </li></ul>")))
                    .containsOnlyKeys("description");
        }

        @Test
        @DisplayName("prose is")
        void proseIsAnAnswer() {
            assertThat(missing(List.of("DESCRIPTION"), with(empty(), "<p>The receipt reprints blank.</p>")))
                    .isEmpty();
        }

        @Test
        @DisplayName("a pasted screenshot on its own is — it is the whole answer often enough to matter")
        void animageIsAnAnswer() {
            assertThat(missing(List.of("DESCRIPTION"),
                    with(empty(), "<p><img src=\"https://edutrack.test/shot.png\" alt=\"\"></p>")))
                    .isEmpty();
        }

        @Test
        @DisplayName("and a script tag is not, because nothing survives the sanitiser")
        void strippedMarkupIsNotAnAnswer() {
            assertThat(missing(List.of("DESCRIPTION"), with(empty(), "<script>alert(1)</script>")))
                    .containsOnlyKeys("description");
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Map<String, String> missing(List<String> codes, TicketCreateDtos.CreateRequest request) {
        return ProjectTicketRules.missingFields(codes, request, null, sanitizer);
    }

    /** Everything optional left out — the shape every "is this enforced" case needs. */
    private static TicketCreateDtos.CreateRequest empty() {
        return new TicketCreateDtos.CreateRequest(
                1L, "Receipt reprints blank", null, 3, null, null, null, null, "HIGH",
                null, null, null, null, null, null, null, null);
    }

    /** Every optional field answered, including a zero-hour estimate. */
    private static TicketCreateDtos.CreateRequest answered() {
        return new TicketCreateDtos.CreateRequest(
                1L, "Receipt reprints blank", "<p>It reprints blank.</p>", 3, 4,
                "Fee Receipt Print", "Duplicate watermark", "<p>1. Reprint it.</p>", "HIGH",
                9L, 11L, null, 12L, List.of(), BigDecimal.ZERO, null, null);
    }

    private static TicketCreateDtos.CreateRequest with(TicketCreateDtos.CreateRequest base, String description) {
        return new TicketCreateDtos.CreateRequest(
                base.projectId(), base.title(), description, base.taskTypeId(), base.moduleId(),
                base.screenName(), base.feature(), base.stepsToGenerate(), base.level(),
                base.clientId(), base.clientContactId(), base.isClientRaised(), base.assigneeId(),
                base.watcherIds(), base.estimatedHrs(), base.plannedCloseDate(), base.saveAsDraft());
    }
}
