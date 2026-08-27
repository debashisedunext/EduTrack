package com.edunext.edutrack.api.feature.tickets.cycles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-038 · the problem documents, and one regression that only a running server
 * revealed.
 *
 * <p>Neither {@code ReopenServiceTest} nor {@code ReopenIT} covers this class —
 * the first stops at the exception, the second calls the service directly and
 * never goes through the advice. So the wire shape of a refusal was untested,
 * and a refusal <em>is</em> the response for every case S-22's dialog has to
 * render.
 */
class ReopenExceptionHandlerTest {

    private final ReopenExceptionHandler handler = new ReopenExceptionHandler();

    /**
     * 🔴 The bug this test exists for. {@code status} is a reserved RFC 9457
     * member holding the HTTP code, and {@code ProblemDetail.setProperty} does not
     * replace it — both are serialised, so the document went out as
     * {@code {"status":422, … ,"status":"IN_PROGRESS"}}. Duplicate keys are legal
     * JSON and parsers keep the last, so a client reading {@code problem.status}
     * got a string where the generated client's type says number.
     *
     * <p>Asserted over the reserved names rather than only on {@code ticketStatus}
     * being present, so the next property added here cannot repeat the mistake
     * with {@code title}, {@code detail}, {@code type} or {@code instance}.
     */
    @Test
    @DisplayName("no custom property collides with a reserved RFC 9457 member")
    void noCustomPropertyShadowsAReservedMember() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotClosed(new TicketNotClosedException("IN_PROGRESS"));

        Map<String, Object> properties = response.getBody().getProperties();

        assertThat(properties)
                .as("status, title, detail, type and instance are ProblemDetail's own fields; "
                        + "a custom property of the same name is serialised alongside it and wins "
                        + "on parse")
                .doesNotContainKeys("status", "title", "detail", "type", "instance");
        assertThat(properties).containsEntry("ticketStatus", "IN_PROGRESS");
    }

    /**
     * {@code IN_PROGRESS} rather than {@code RESOLVED}, and the swap is the
     * point. {@code RESOLVED} is a status this route now <em>accepts</em> — the
     * Support desk refusing a sign-off starts a new cycle — so
     * {@link TicketNotClosedException}'s own javadoc says it "can never reach
     * this exception". A test that built one was asserting on a case the code
     * cannot produce. What is still refused is a ticket mid-attempt, which is
     * what this now uses.
     *
     * <p>The detail is asserted to <em>name the status</em> and nothing more.
     * Pinning the sentence contradicted the reason given one line above for
     * switching on {@code type} instead — "detail is prose and gets reworded" —
     * and it was that pinned phrase, not any behaviour, that turned the build
     * red when the message was rewritten.
     */
    @Test
    @DisplayName("a ticket that is not closed is 422 with a switchable type")
    void notClosedIs422() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotClosed(new TicketNotClosedException("IN_PROGRESS"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType())
                .as("a stable URI the dialog switches on — detail is prose and gets reworded")
                .hasToString("https://edutrack/errors/ticket-not-closed");
        assertThat(problem.getDetail())
                .as("the reader has to be told which status blocked them, however it is worded")
                .contains("IN_PROGRESS");
        assertThat(problem.getProperties()).containsEntry("ticketStatus", "IN_PROGRESS");
    }

    /**
     * Field-keyed, unlike the 422 — this one <em>is</em> about the value sent, so
     * S-22 can mark the restart-stage picker instead of banner-ing a form whose
     * other four fields are fine.
     */
    @Test
    @DisplayName("an unknown restart stage is 400 keyed onto restartStageCode")
    void unknownStageIs400() {
        ResponseEntity<ProblemDetail> response = handler.handleUnknownStage(
                new UnknownRestartStageException("SIGNOFF", 1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType()).hasToString("https://edutrack/errors/validation");

        assertThat(problem.getProperties().get("errors")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) problem.getProperties().get("errors");
        assertThat(errors)
                .as("only the field the caller can act on — a banner would be the wrong affordance")
                .containsOnlyKeys("restartStageCode");
    }

    /**
     * The message names the stage and the template, because "invalid stage" sends
     * the reader to the wrong place — the code is usually a real stage, just not
     * one of <em>this</em> ticket's.
     */
    @Test
    @DisplayName("the refusal says which stage and which template")
    void theMessageNamesBoth() {
        ResponseEntity<ProblemDetail> response = handler.handleUnknownStage(
                new UnknownRestartStageException("DEPLOY", 7L));

        assertThat(response.getBody().getDetail()).contains("DEPLOY").contains("7");
    }
}
