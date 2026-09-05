package com.edunext.edutrack.api.feature.onboarding.notifications;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * B-112 · {@code /onboarding/notifications} per
 * {@code contracts/openapi.yaml} — OB-13.
 *
 * <p>The bell popover and the full history page are the same endpoint;
 * "the last eight" is a {@code limit}, not a second route.
 *
 * <h2>A second centre beside {@code /notifications}, not a filter on it</h2>
 *
 * <p>The obvious alternative was a {@code module} parameter on Stream D's
 * route. The reasons against are in the migration and in {@code ObCategory},
 * and the short form is that they share neither a store, a tab vocabulary nor
 * an event catalogue — a query parameter would have been the only thing they
 * did share, and it would have had to fan out to two tables underneath.
 *
 * <h2>Auth: {@code isAuthenticated()}, and nothing more, on purpose</h2>
 *
 * <p>Two separate reasons, and both are worth stating because the route looks
 * like one that should carry a permission.
 *
 * <p>First, every operation here is already pinned to the caller's own id.
 * There is no route that takes a user id, the repository scopes all five
 * statements by {@code recipient_user_id}, and mark-read answers 404 rather
 * than 403 for an entry that is not yours, so it cannot be used to probe which
 * ids exist. Blueprint §2 grants no notification capability because receiving
 * notifications is not one — a role that could not read its own bell would be
 * a role that never learns a service was assigned to it. That is
 * {@code NotificationController}'s argument and it transfers whole.
 *
 * <p>Second, module gating. Every {@code /onboarding/**} path is drawn behind
 * {@code ModuleAccessGuard} (A-111) answering 404 to a caller without
 * {@code ONBOARDING} in their {@code modules} claim, and that guard is written
 * but not yet wired into {@code SecurityConfig} — see
 * {@code ObJourneyTemplateController}'s javadoc, which states the same interim
 * position for OB-07. So this route falls to the blanket
 * {@code /api/**.authenticated()} until that wiring lands, and the practical
 * consequence is narrow: a caller with no onboarding access sees an empty list,
 * because nothing ever addressed them an entry. Not a gap this task
 * introduces, and not one it papers over with a bespoke filter.
 */
@RestController
@RequestMapping("/api/v1/onboarding/notifications")
@Tag(name = "onboarding-notifications")
@PreAuthorize("isAuthenticated()")
class ObNotificationController {

    /** The full page. */
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final ObNotificationService notifications;

    ObNotificationController(ObNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObNotifications", summary = "Onboarding notification centre (OB-13)")
    ResponseEntity<?> list(Authentication authentication,
                           @RequestParam(required = false) String tab,
                           @RequestParam(required = false) Boolean unreadOnly,
                           @RequestParam(required = false) String cursor,
                           @RequestParam(required = false) Integer limit) {

        Optional<ObNotificationTab> resolved = ObNotificationTab.fromQuery(tab);
        if (resolved.isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem("Unknown tab", "No such tab: " + tab));
        }

        Long after;
        try {
            after = parseCursor(cursor);
        } catch (NumberFormatException malformed) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem("Malformed cursor", "cursor must be one returned in meta.nextCursor"));
        }

        return ResponseEntity.ok(notifications.list(
                callerId(authentication),
                resolved.get(),
                Boolean.TRUE.equals(unreadOnly),
                after,
                clamp(limit)));
    }

    @PatchMapping(path = "/{notificationId}/read")
    @Operation(operationId = "markObNotificationRead", summary = "Mark one as read")
    ResponseEntity<Void> markRead(Authentication authentication, @PathVariable long notificationId) {
        // Already-read answers 204 alongside just-marked: the caller asked for
        // a state and it holds. Only "not yours or no such row" is a 404 — and
        // a 404 rather than a 403 so it cannot be used to test which ids exist.
        return switch (notifications.markRead(notificationId, callerId(authentication))) {
            case MARKED, ALREADY_READ -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    @PatchMapping(path = "/read-all")
    @Operation(operationId = "markAllObNotificationsRead", summary = "Mark all as read")
    ResponseEntity<Void> markAllRead(Authentication authentication) {
        // Deliberately not scoped to the open tab: the contract takes none
        // here, and a "mark all read" that left some unread would be a lie the
        // badge contradicts a second later.
        notifications.markAllRead(callerId(authentication));
        return ResponseEntity.noContent().build();
    }

    /**
     * @throws IllegalStateException when nobody identifiable is authenticated.
     *         That is a wiring fault rather than a client error — the route
     *         sits behind the filter chain — and failing loudly beats
     *         defaulting to a user id and serving somebody else's bell.
     */
    private static long callerId(Authentication authentication) {
        return CallerIdentity.of(authentication)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-notifications route reached with no resolvable caller"));
    }

    /**
     * The cursor is opaque to the client and an id to us. Keeping it a string
     * on the wire is what lets it become something else later without a
     * contract change.
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
