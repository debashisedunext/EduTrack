package com.edunext.edutrack.api.feature.notifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * D-041 · {@code /notifications} per {@code contracts/openapi.yaml} — S-26.
 *
 * <p>The bell dropdown and the full page are the same endpoint. "Last 10" is a
 * {@code limit}, not a second route.
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "notifications")
class NotificationController {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @org.springframework.web.bind.annotation.GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listNotifications", summary = "Notification centre (S-26)")
    ResponseEntity<?> list(Authentication authentication,
                           @RequestParam(required = false) String tab,
                           @RequestParam(required = false) Boolean unreadOnly,
                           @RequestParam(required = false) String cursor,
                           @RequestParam(required = false) Integer limit) {

        Optional<NotificationTab> resolved = NotificationTab.fromQuery(tab);
        if (resolved.isEmpty()) {
            // Falling through to All would show a caller who mistyped `mention`
            // everything, and look like it worked.
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem("Unknown tab", "No such tab: " + tab));
        }

        Long after;
        try {
            after = parseCursor(cursor);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem("Malformed cursor", "cursor must be one returned in meta.nextCursor"));
        }

        return ResponseEntity.ok(notifications.list(
                CurrentUser.idOf(authentication),
                resolved.get(),
                Boolean.TRUE.equals(unreadOnly),
                after,
                clamp(limit)));
    }

    @PatchMapping(path = "/{notificationId}/read")
    @Operation(operationId = "markNotificationRead", summary = "Mark one as read")
    ResponseEntity<Void> markRead(Authentication authentication, @PathVariable long notificationId) {
        // Already-read answers 204 alongside just-marked: the caller asked for a
        // state, and it holds. Only "not yours or no such row" is a 404 — and it
        // is a 404 rather than a 403 so it cannot be used to test which
        // notification ids exist.
        return switch (notifications.markRead(notificationId, CurrentUser.idOf(authentication))) {
            case MARKED, ALREADY_READ -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    @PatchMapping(path = "/read-all")
    @Operation(operationId = "markAllNotificationsRead", summary = "Mark all as read")
    ResponseEntity<Void> markAllRead(Authentication authentication) {
        // Deliberately not scoped to the current tab: the contract takes no tab
        // here, and "mark all read" that left some unread would be a lie the
        // badge immediately contradicts.
        notifications.markAllRead(CurrentUser.idOf(authentication));
        return ResponseEntity.noContent().build();
    }

    /**
     * The cursor is opaque to the client and an id to us. Keeping it a string on
     * the wire is what lets it become something else later — a composite key,
     * an encoded timestamp — without a contract change.
     */
    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        return Long.valueOf(cursor.trim());
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** RFC 9457, per CONVENTIONS.md §3. */
    private static Map<String, Object> problem(String title, String detail) {
        return Map.of(
                "type", "https://edutrack/errors/invalid-query",
                "title", title,
                "status", HttpStatus.BAD_REQUEST.value(),
                "detail", detail);
    }
}
