package com.edunext.edutrack.api.feature.clients;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-066 · {@code GET /clients/{clientId}/tickets} — S-32's Client 360 view.
 *
 * <p>Every authenticated role, on {@link ClientController}'s own §2 row 51
 * argument for {@code listClients}/{@code getClient}: a client is not row
 * scoped and every role already reads every client through the ticket-form
 * dropdown, so a role that can enumerate clients learns nothing new by
 * opening one's 360. See {@link Client360Service} for why the tickets
 * <em>inside</em> the response are a different question and stay scoped.
 *
 * <p>A separate controller from {@link ClientController} rather than a tenth
 * method on it — the way A-069's Resource 360 got its own
 * {@code Profile360Controller} beside the resource master it reads from.
 */
@RestController
@Tag(name = "clients")
class Client360Controller {

    private final Client360Service service;

    Client360Controller(Client360Service service) {
        this.service = service;
    }

    @GetMapping(path = "/api/v1/clients/{clientId}/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getClient360", summary = "Client 360 view (S-32)")
    Client360Dtos.Client360Response view(
            Authentication caller,
            @PathVariable long clientId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String status) {

        return service.view(caller, clientId, status, cursor, limit)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No client with id " + clientId + "."));
    }
}
