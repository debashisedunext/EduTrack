package com.edunext.edutrack.api.feature.tickets.cycles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-040 · the close route's problem document — {@code ReopenExceptionHandlerTest}'s
 * own reasoning, including its duplicate-key regression guard, one route over.
 */
class CloseExceptionHandlerTest {

    private final CloseExceptionHandler handler = new CloseExceptionHandler();

    /**
     * {@code ReopenExceptionHandlerTest}'s own guard, carried here rather than
     * assumed: {@code status} is RFC 9457's reserved member for the HTTP code, and
     * {@code setProperty("status", …)} does not replace it.
     */
    @Test
    @DisplayName("no custom property collides with a reserved RFC 9457 member")
    void noCustomPropertyShadowsAReservedMember() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotResolved(new TicketNotResolvedException("IN_PROGRESS"));

        Map<String, Object> properties = response.getBody().getProperties();

        assertThat(properties).doesNotContainKeys("status", "title", "detail", "type", "instance");
        assertThat(properties).containsEntry("ticketStatus", "IN_PROGRESS");
    }

    @Test
    @DisplayName("a ticket that is not resolved is 422 with a switchable type")
    void notResolvedIs422() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotResolved(new TicketNotResolvedException("IN_PROGRESS"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType())
                .as("a stable URI the dialog switches on — detail is prose and gets reworded")
                .hasToString("https://edutrack/errors/ticket-not-resolved");
        assertThat(problem.getDetail()).contains("IN_PROGRESS");
        assertThat(problem.getProperties()).containsEntry("ticketStatus", "IN_PROGRESS");
    }

    @Test
    @DisplayName("an already-closed ticket names itself specifically, not just its status code")
    void alreadyClosedNamesItself() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotResolved(new TicketNotResolvedException("CLOSED"));

        assertThat(response.getBody().getDetail()).contains("already CLOSED");
    }
}
