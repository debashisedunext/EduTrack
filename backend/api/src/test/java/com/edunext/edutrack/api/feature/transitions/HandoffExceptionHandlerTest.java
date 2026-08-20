package com.edunext.edutrack.api.feature.transitions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-044 · the handoff route's problem documents — {@code CloseExceptionHandlerTest}'s
 * own shape, one route over.
 */
class HandoffExceptionHandlerTest {

    private final HandoffExceptionHandler handler = new HandoffExceptionHandler();

    @Test
    @DisplayName("the golden rule's refusal is 422 with a switchable type, not field-keyed")
    void notStageOwnerIs422() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotStageOwner(new NotCurrentStageOwnerException(347L, 55L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString("https://edutrack/errors/stage-owner-required");
        assertThat(problem.getProperties()).isNullOrEmpty();
    }

    @Test
    @DisplayName("no open stage is 422 with its own switchable type")
    void noOpenStageIs422() {
        ResponseEntity<ProblemDetail> response = handler.handleNoOpenStage(new NoOpenStageException(347L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getType()).hasToString("https://edutrack/errors/no-open-stage");
    }

    @Test
    @DisplayName("a missing effortHours is 400, keyed onto the effortHours field")
    void missingEffortIs400KeyedOnField() {
        ResponseEntity<ProblemDetail> response = handler.handleEffortRequired(new EffortHoursRequiredException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).hasToString("https://edutrack/errors/validation");
        @SuppressWarnings("unchecked")
        var errors = (java.util.Map<String, String[]>) problem.getProperties().get("errors");
        assertThat(errors).containsKey("effortHours");
    }

    @Test
    @DisplayName("a toStageCode outside the template is 400, keyed onto the toStageCode field")
    void unknownStageIs400KeyedOnField() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnknownStage(new UnknownTransitionStageException("RELEASE", 3L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        var errors = (java.util.Map<String, String[]>) response.getBody().getProperties().get("errors");
        assertThat(errors).containsKey("toStageCode");
    }
}
