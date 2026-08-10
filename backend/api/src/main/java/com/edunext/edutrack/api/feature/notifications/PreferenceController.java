package com.edunext.edutrack.api.feature.notifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * D-042 · {@code /me/notification-preferences} — the S-26 preference matrix.
 *
 * <p>Under {@code /me} rather than {@code /users/{id}/…} on purpose: these are
 * only ever your own. A path taking a user id would be a route an Admin might
 * reasonably expect to work, and answering 403 to that is a worse conversation
 * than never offering it — nobody should be editing somebody else's idea of
 * what is worth interrupting them for.
 */
@RestController
@RequestMapping("/api/v1/me/notification-preferences")
@Tag(name = "notifications")
class PreferenceController {

    private final PreferenceService preferences;

    PreferenceController(PreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getNotificationPreferences",
            summary = "The per-user notification matrix (S-26)")
    ResponseEntity<PreferenceDtos.PreferenceMatrix> get(Authentication authentication) {
        return ResponseEntity.ok(preferences.matrixFor(CurrentUser.idOf(authentication)));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateNotificationPreferences",
            summary = "Change which events reach you, on which channel")
    ResponseEntity<?> update(Authentication authentication,
                             @RequestBody PreferenceDtos.PreferenceUpdateRequest request) {

        long userId = CurrentUser.idOf(authentication);
        return switch (preferences.save(userId, request)) {
            case SAVED -> ResponseEntity.ok(preferences.matrixFor(userId));
            // Returning the matrix rather than 204 so a client that just saved
            // sees what actually took effect — including a mandatory mail it
            // asked to disable and did not.
            case UNKNOWN_EVENT -> ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem());
        };
    }

    /** RFC 9457, per CONVENTIONS.md §3. */
    private static Map<String, Object> problem() {
        return Map.of(
                "type", "https://edutrack/errors/invalid-body",
                "title", "Unknown notification event",
                "status", HttpStatus.BAD_REQUEST.value(),
                "detail", "One or more eventKey values are not notification events");
    }
}
