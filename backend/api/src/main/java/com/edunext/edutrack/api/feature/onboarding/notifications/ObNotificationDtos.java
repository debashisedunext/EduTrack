package com.edunext.edutrack.api.feature.onboarding.notifications;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.time.Instant;
import java.util.List;

/**
 * B-112 · the wire shapes for {@code /onboarding/notifications}, matching
 * {@code contracts/openapi.yaml}'s {@code onboarding-notifications} tag.
 */
public final class ObNotificationDtos {

    private ObNotificationDtos() {
    }

    /**
     * One OB-13 entry.
     *
     * @param eventKey the stored string, not a parsed enum, for
     *                 {@code NotificationDtos.Notification}'s reason: a row
     *                 written by a newer deploy must still render rather than
     *                 failing the whole page.
     * @param category OB-13's tab. Also sent as the raw string, and for the
     *                 same reason — the screen groups on it, and an unknown
     *                 value should cost the grouping and not the row.
     * @param deepLink an app-relative path, or null. Derived from the row's own
     *                 client and journey ids by the worker, never from a mail
     *                 payload — see {@code ObMailLinks.inAppPath}.
     * @param obClientId the client this is about, for a screen that wants to
     *                   filter or group without parsing {@code deepLink}.
     */
    public record ObNotification(
            long id,
            String eventKey,
            String category,
            String title,
            String body,
            Long obClientId,
            Long journeyId,
            Long stepId,
            boolean isRead,
            Instant createdAt,
            String deepLink) {
    }

    /**
     * The contract's {@code ObNotificationListResponse.meta} —
     * {@code allOf: [Meta, {unreadCount}]}.
     *
     * <p><strong>Composes {@link PageMeta} rather than restating its two
     * fields</strong>, exactly as {@code EffortLogDtos.ListMeta} does and for
     * A-053's reason: {@code PaginationRulesTest} refuses a sixth class
     * declaring its own {@code nextCursor}, on the five-classes-three-shapes
     * history in that test's own javadoc. Worth naming the near miss, because
     * this endpoint's obvious model is the one it must not copy — S-26's
     * {@code NotificationDtos.Meta} is that fifth record and is grandfathered,
     * so following it would have re-created the thing the rule exists to stop
     * on the one screen most likely to be diffed against it.
     *
     * <p>{@code @JsonUnwrapped} flattens {@code page} onto this record's own
     * JSON level, which is what keeps the wire shape the contract's flat
     * {@code allOf} despite the nesting on the Java side.
     *
     * @param unreadCount always the caller's <strong>total</strong> unread, not
     *                    the count within the current tab. It drives the bell
     *                    badge, which is read on every page load and must mean
     *                    one thing; a badge that changed as you clicked between
     *                    tabs would be answering a question nobody asked.
     */
    public record Meta(@JsonUnwrapped PageMeta page, int unreadCount) {
    }

    public record ObNotificationListResponse(List<ObNotification> data, Meta meta) {
    }
}
