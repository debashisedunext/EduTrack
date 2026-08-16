package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-025 · what the controller does with what the service hands it.
 *
 * <p>Constructed with {@code new} rather than through a slice test: these
 * assertions are about the envelope, the 404s and the filters reaching the
 * service, none of which need a context. Where the controller is <em>mounted</em>
 * is {@code ClientRoutesTest}'s question — a wiring mistake needs a wiring test,
 * which is the gap B-023's calendar fell through.
 */
class ClientControllerTest {

    private ClientService service;
    private ClientController controller;

    @BeforeEach
    void setUp() {
        service = mock(ClientService.class);
        controller = new ClientController(service);
    }

    @Test
    @DisplayName("the list carries the page's meta through unchanged")
    void listPassesMetaThrough() {
        PageMeta meta = new PageMeta("cursor-value", true);
        when(service.list(any(), any(), any()))
                .thenReturn(new CursorPage<>(List.of(client(1, "Acme")), meta));

        ClientDtos.ClientListResponse response =
                controller.clients(null, null, null, null, null, null, null);

        assertThat(response.data()).hasSize(1);
        assertThat(response.meta()).isEqualTo(meta);
    }

    /**
     * A complete list carries no {@code meta} at all, and that is the signal —
     * PageMeta's javadoc: an empty one says something different.
     */
    @Test
    @DisplayName("a complete list has null meta, not an empty one")
    void completeListHasNoMeta() {
        when(service.list(any(), any(), any()))
                .thenReturn(CursorPage.complete(List.of(client(1, "Acme"))));

        assertThat(controller.clients(null, null, null, null, null, null, null).meta()).isNull();
    }

    @Test
    @DisplayName("every S-32 filter reaches the service")
    void filtersReachTheService() {
        when(service.list(any(), any(), any())).thenReturn(CursorPage.complete(List.of()));

        controller.clients("cur", 25, "acme", true, 3L, "Premium", 7L);

        ArgumentCaptor<ClientQueryRepository.Filter> filter =
                ArgumentCaptor.forClass(ClientQueryRepository.Filter.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(service).list(filter.capture(), eq("cur"), limit.capture());

        assertThat(filter.getValue()).isEqualTo(
                new ClientQueryRepository.Filter("acme", true, 3L, "Premium", 7L));
        assertThat(limit.getValue()).isEqualTo(25);
    }

    @Test
    @DisplayName("the bulk setter answers a complete list, never a page")
    void bulkStatusAnswersACompleteList() {
        when(service.setStatusBulk(any(), eq(false)))
                .thenReturn(List.of(client(1, "Acme"), client(2, "Bluewave")));

        ClientDtos.ClientListResponse response = controller.bulkStatus(
                new ClientDtos.BulkStatusRequest(List.of(1L, 2L), false));

        assertThat(response.data()).hasSize(2);
        assertThat(response.meta())
                .as("the caller named every row in the body; there is nothing to resume")
                .isNull();
    }

    @Test
    @DisplayName("a client that is not there is 404, never 403")
    void unknownClientIsNotFound() {
        when(service.setStatus(anyLong(), any(Boolean.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.status(9, new ClientDtos.StatusRequest(false)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("contacts for a client that is not there are 404, not an empty list")
    void unknownClientContactsAreNotFound() {
        when(service.contactsOf(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.contacts(9))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("contacts come back wrapped in { data }")
    void contactsAreWrapped() {
        when(service.contactsOf(1L)).thenReturn(Optional.of(List.of(
                new ClientDtos.Contact(1, "Sara Kapoor", "sara@acme.example", null,
                        true, true, true))));

        assertThat(controller.contacts(1).data()).hasSize(1);
    }

    private static ClientDtos.Client client(long id, String name) {
        return new ClientDtos.Client(id, name.toUpperCase(java.util.Locale.ROOT), name,
                null, null, "Premium", null, "Asia/Kolkata", true, 0, null, List.of(), null);
    }
}
