package com.edunext.edutrack.api.feature.tickets.assign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-049 · the problem document {@code UnknownAssigneeException} renders as,
 * on {@code BulkReassignExceptionHandler}'s own shape for the identical
 * mistake, one ticket at a time rather than a whole batch.
 */
class AssignExceptionHandlerTest {

    private final AssignExceptionHandler handler = new AssignExceptionHandler();

    @Test
    @DisplayName("an unknown assignee is 400 keyed onto assigneeId")
    void unknownAssigneeIs400() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnknownAssignee(new UnknownAssigneeException(999L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).hasToString("https://edutrack/errors/validation");

        assertThat(problem.getProperties().get("errors")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) problem.getProperties().get("errors");
        assertThat(errors)
                .as("only the field the assign dialog's picker owns")
                .containsOnlyKeys("assigneeId");
    }
}
