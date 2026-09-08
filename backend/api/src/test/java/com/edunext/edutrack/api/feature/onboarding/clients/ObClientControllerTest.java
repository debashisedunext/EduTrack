package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-102 · the concurrency contract, which lives entirely in the controller.
 *
 * <p>Plain construction against mocked services, on
 * {@code ObJourneyTemplateControllerTest}'s convention: this proves the
 * envelope, the tag and the precondition, not the Spring wiring.
 *
 * <p>Worth its own class because {@code If-Match} is the one rule with no
 * service behind it to test — CONVENTIONS.md §5's whole point is that a
 * precondition treated as optional protects only the callers who already opted
 * in, which is the set that needed it least.
 */
class ObClientControllerTest {

    private final ObClientService service = mock(ObClientService.class);
    private final ObClientWriteService writes = mock(ObClientWriteService.class);
    private final ObClientController controller = new ObClientController(service, writes);

    private static final long CLIENT = 42L;

    @Test
    @DisplayName("mounted where the contract puts it")
    void mountedWhereTheContractPutsIt() {
        assertThat(ObClientController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/onboarding/clients");
    }

    @Test
    @DisplayName("the detail read emits an ETag — without it the PATCH would be uncallable")
    void detailEmitsATag() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.of(detail("Horizon Academy")));

        ResponseEntity<ObClientDtos.ObClientDetailResponse> response =
                controller.get(caller(), CLIENT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isNotBlank();
    }

    @Test
    @DisplayName("a client out of scope is 404, and the tag question never arises")
    void detailOutOfScopeIs404() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(caller(), CLIENT))
                .isInstanceOf(ObClientNotFoundException.class);
    }

    @Test
    @DisplayName("the create answers 201 and tags it, so the wizard can edit without a second read")
    void createIsTaggedOnTheWayOut() {
        when(writes.create(any(), anyLong(), any())).thenReturn(detail("Horizon Academy"));

        ResponseEntity<ObClientDtos.ObClientDetailResponse> response =
                controller.create(caller(), "an-idempotency-key", createRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isNotBlank();
    }

    /** The rule this class exists for. */
    @Test
    @DisplayName("a PATCH with no If-Match is 428, not allowed through")
    void missingPreconditionIsRefused() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.of(detail("Horizon Academy")));

        assertThatThrownBy(() -> controller.update(caller(), CLIENT, null, new ObClientUpdateRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

        verify(writes, never()).update(any(), anyLong(), any());
    }

    @Test
    @DisplayName("a stale If-Match is 412 — somebody else changed the client")
    void staleTagIsRefused() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.of(detail("Horizon Academy")));

        assertThatThrownBy(() -> controller.update(
                caller(), CLIENT, "\"deadbeef\"", new ObClientUpdateRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.PRECONDITION_FAILED));

        verify(writes, never()).update(any(), anyLong(), any());
    }

    @Test
    @DisplayName("the tag the read handed out is the tag the write accepts")
    void currentTagIsAccepted() {
        ObClientDtos.ObClientDetail current = detail("Horizon Academy");
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.of(current));
        when(writes.update(any(), anyLong(), any())).thenReturn(detail("Horizon Renamed"));

        String tag = controller.get(caller(), CLIENT).getHeaders().getFirst(HttpHeaders.ETAG);
        ResponseEntity<ObClientDtos.ObClientDetailResponse> response =
                controller.update(caller(), CLIENT, tag, new ObClientUpdateRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().name()).isEqualTo("Horizon Renamed");
    }

    /**
     * The tag moves when the document does — including on a journey, which the
     * contract asks for by name: "ETag covers the whole document, journeys
     * included, so a step transition made elsewhere costs the editor a reload
     * rather than a lost update".
     */
    @Test
    @DisplayName("a change anywhere in the document, journeys included, moves the tag")
    void theTagCoversTheWholeDocument() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.of(detail("Horizon Academy")));
        String before = controller.get(caller(), CLIENT).getHeaders().getFirst(HttpHeaders.ETAG);

        when(service.findDetail(any(), anyLong()))
                .thenReturn(Optional.of(detailWithJourney("Horizon Academy")));
        String after = controller.get(caller(), CLIENT).getHeaders().getFirst(HttpHeaders.ETAG);

        assertThat(after).isNotEqualTo(before);
    }

    /** RFC 9110: {@code *} matches anything. */
    @Test
    @DisplayName("If-Match: * is accepted, per RFC 9110")
    void wildcardIsAccepted() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.of(detail("Horizon Academy")));
        when(writes.update(any(), anyLong(), any())).thenReturn(detail("Horizon Academy"));

        assertThat(controller.update(caller(), CLIENT, "*", new ObClientUpdateRequest())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * A 404 before a 428: answering "your precondition is missing" for a client
     * that is not there would send the caller to fetch a tag from a URL that
     * will 404 too.
     */
    @Test
    @DisplayName("a missing client is 404 even when the precondition is also missing")
    void notFoundBeatsPreconditionRequired() {
        when(service.findDetail(any(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.update(caller(), CLIENT, null, new ObClientUpdateRequest()))
                .isInstanceOf(ObClientNotFoundException.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Authentication caller() {
        DevPrincipal principal =
                new DevPrincipal(7L, "ayush", "Ayush Tiwari", "ADMIN", List.of(), List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static ObClientDtos.ObClientDetail detail(String name) {
        return new ObClientDtos.ObClientDetail(
                CLIENT, name, LocalDate.of(2026, 9, 7), "ONBOARDING", null, "LOCKED", 1, 0,
                List.of(), null, null, null, false,
                null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static ObClientDtos.ObClientDetail detailWithJourney(String name) {
        ObClientDtos.ObJourneyStrip strip = new ObClientDtos.ObJourneyStrip(
                9L, new ObClientDtos.ObProductRef(1L, "ERP", "ERP"), "LOCKED", null, 0,
                null, 5, null,
                List.of(new ObClientDtos.ObStepDot(3L, 1, "Kickoff", "IN_PROGRESS", "AMBER", null)));

        ObClientDtos.ObClientDetail base = detail(name);
        return new ObClientDtos.ObClientDetail(
                base.id(), base.name(), base.onboardingDate(), base.status(), base.rag(),
                base.gateStatus(), base.journeyCount(), base.journeysComplete(), base.products(),
                base.salesPerson(), base.primaryContact(), base.liveAt(), base.hasPortalLogin(),
                base.description(), base.address(), base.licenseType(), base.pan(),
                base.statusReason(), base.contacts(), base.applications(), base.requirements(),
                List.of(strip), base.createdBy(), base.createdAt());
    }

    private static ObClientDtos.ObClientCreateRequest createRequest() {
        return new ObClientDtos.ObClientCreateRequest(
                "Horizon Academy", null, LocalDate.of(2026, 9, 7), null, null, null, null,
                List.of(new ObClientDtos.ObContactWriteRequest(
                        "SPOC", null, "spoc@example.com", null, false, null, true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                null, false, false);
    }
}
