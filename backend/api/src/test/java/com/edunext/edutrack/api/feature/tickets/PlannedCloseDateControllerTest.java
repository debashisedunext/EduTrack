package com.edunext.edutrack.api.feature.tickets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-012 · what the controller decides on its own — the mapping onto the wire,
 * where it is mounted, and the two failures it renders.
 *
 * <p>The ladder is {@link PlannedCloseDateServiceTest}'s subject and is not
 * restated. The route prefix is checked here for the reason
 * {@code MasterRoutesTest} exists: a controller tested as a plain object never
 * consults its own request mapping, and B-023 shipped nine operations on a path
 * no client calls because of exactly that gap.
 */
class PlannedCloseDateControllerTest {

    private static final Instant FROM = Instant.parse("2026-08-14T12:30:00Z");
    private static final Instant DUE = Instant.parse("2026-08-17T05:00:00Z");
    private static final Instant RESPONSE_DUE = Instant.parse("2026-08-14T13:00:00Z");

    private final PlannedCloseDateService service = mock(PlannedCloseDateService.class);
    private final PlannedCloseDateController controller = new PlannedCloseDateController(service);
    private final TicketExceptionHandler problems = new TicketExceptionHandler();

    @Test
    @DisplayName("every parameter reaches the service, in the right order")
    void parametersReachTheService() {
        when(service.preview(any(), any(), any(), any(), any()))
                .thenReturn(new PlannedCloseDateService.Preview(FROM, DUE, null, SlaResolution.none()));

        controller.preview(1L, 7, "HIGH", 3L, FROM);

        verify(service).preview(1L, 7, "HIGH", 3L, FROM);
    }

    /**
     * The whole resolution, not just the date. A date with no explanation is one
     * the user cannot argue with, and the source is the only way the person
     * configuring S-13's matrix can see which row is being applied.
     */
    @Test
    @DisplayName("the response carries the resolution as well as the dates")
    void theResponseExplainsItself() {
        SlaResolution sla = new SlaResolution(SlaResolution.Source.PROJECT_TASK_TYPE, 11L,
                BigDecimal.valueOf(4), BigDecimal.valueOf(16));
        when(service.preview(any(), any(), any(), any(), any()))
                .thenReturn(new PlannedCloseDateService.Preview(FROM, DUE, RESPONSE_DUE, sla));

        PlannedCloseDateDtos.PlannedCloseDatePreview body =
                controller.preview(1L, 7, "HIGH", null, FROM).data();

        assertThat(body.from()).isEqualTo(FROM);
        assertThat(body.plannedCloseDate()).isEqualTo(DUE);
        assertThat(body.firstResponseDue()).isEqualTo(RESPONSE_DUE);
        assertThat(body.resolutionHrs()).isEqualByComparingTo("16");
        assertThat(body.responseHrs()).isEqualByComparingTo("4");
        assertThat(body.source()).isEqualTo(SlaResolution.Source.PROJECT_TASK_TYPE);
        assertThat(body.slaPolicyId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("no SLA at all serialises as nulls, not as a fabricated date")
    void noSlaSerialisesAsNulls() {
        when(service.preview(any(), any(), any(), any(), any()))
                .thenReturn(new PlannedCloseDateService.Preview(FROM, null, null, SlaResolution.none()));

        PlannedCloseDateDtos.PlannedCloseDatePreview body =
                controller.preview(1L, 7, "HIGH", null, FROM).data();

        assertThat(body.plannedCloseDate()).isNull();
        assertThat(body.resolutionHrs()).isNull();
        assertThat(body.source()).isEqualTo(SlaResolution.Source.NONE);
    }

    // ------------------------------------------------------------------
    // Where it is mounted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mounted under /api/v1 — nothing adds the prefix for it")
    void carriesTheApiPrefix() {
        RequestMapping mapping = PlannedCloseDateController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value().length > 0 ? mapping.value() : mapping.path())
                .containsExactly("/api/v1/tickets");
    }

    /**
     * The literal segment has to stay literal. Written as {@code /tickets/{x}}
     * it would collide with the detail read, and Spring would resolve one of
     * them by pattern precedence rather than by intent.
     */
    @Test
    @DisplayName("the path is the contract's, and is a literal segment")
    void theSubPathIsTheContracts() throws NoSuchMethodException {
        Method preview = PlannedCloseDateController.class.getDeclaredMethod(
                "preview", long.class, Integer.class, String.class, Long.class, Instant.class);
        GetMapping mapping = preview.getAnnotation(GetMapping.class);

        assertThat(mapping.value().length > 0 ? mapping.value() : mapping.path())
                .containsExactly("/planned-close-date");
    }

    // ------------------------------------------------------------------
    // The two failures
    // ------------------------------------------------------------------

    /**
     * <b>404, and the body must not say which of the two it was.</b> Once
     * A-034's scope guard lands, an out-of-scope project arrives as "does not
     * exist"; a response that distinguished them would confirm which project ids
     * are real. The assertion names the words that would leak it, so a
     * well-meaning copy edit fails rather than reintroducing the leak.
     */
    @Test
    @DisplayName("an unknown project is 404 and says nothing more")
    void unknownProjectIs404AndSaysNothing() {
        ResponseEntity<ProblemDetail> response =
                problems.handleUnknownProject(new UnknownProjectException(404L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).doesNotContain("404").doesNotContain("project id");
        assertThat(String.valueOf(response.getBody().getDetail()).toLowerCase())
                .doesNotContain("permission").doesNotContain("forbidden").doesNotContain("not allowed");
    }

    @Test
    @DisplayName("an unknown level is 400 with the message against the level field")
    void unknownLevelIsAFieldError() {
        ResponseEntity<ProblemDetail> response =
                problems.handleUnknownLevel(new UnknownLevelException("URGENT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        Map<String, Object> properties = response.getBody().getProperties();
        assertThat(properties).containsKey("errors");
        assertThat((String[]) ((Map<?, ?>) properties.get("errors")).get("level"))
                .anySatisfy(message -> assertThat(message).contains("URGENT"));
    }
}
