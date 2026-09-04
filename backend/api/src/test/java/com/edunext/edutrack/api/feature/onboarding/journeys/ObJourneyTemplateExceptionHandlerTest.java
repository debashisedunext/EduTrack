package com.edunext.edutrack.api.feature.onboarding.journeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** C-102 · the problem documents {@link ObJourneyTemplateExceptionHandler} renders, on {@code AssignExceptionHandlerTest}'s own shape. */
class ObJourneyTemplateExceptionHandlerTest {

    private final ObJourneyTemplateExceptionHandler handler = new ObJourneyTemplateExceptionHandler();

    @Test
    @DisplayName("a not-found id is 404")
    void notFoundIs404() {
        ResponseEntity<ProblemDetail> response = handler.handleNotFound(new TemplateNotFoundException(999L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("editing a published template is 409")
    void notEditableIs409() {
        ResponseEntity<ProblemDetail> response = handler.handleConflict(new TemplateNotEditableException(1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getType()).hasToString("https://edutrack/errors/conflict");
    }

    @Test
    @DisplayName("deleting a step other steps depend on is 409 and names the dependent ids")
    void stepHasDependentsIs409WithIds() {
        ResponseEntity<ProblemDetail> response =
                handler.handleStepHasDependents(new StepHasDependentsException(5L, List.of(6L, 7L)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties().get("dependentStepIds")).isEqualTo(List.of(6L, 7L));
    }

    @Test
    @DisplayName("publishing an empty draft is 422")
    void noStepsIs422() {
        ResponseEntity<ProblemDetail> response = handler.handleNoSteps(new TemplateHasNoStepsException(1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a malformed reorder list is 400")
    void reorderMismatchIs400() {
        ResponseEntity<ProblemDetail> response =
                handler.handleReorderMismatch(new StepReorderMismatchException(1L, "missing an id"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getType()).hasToString("https://edutrack/errors/validation");
    }
}
