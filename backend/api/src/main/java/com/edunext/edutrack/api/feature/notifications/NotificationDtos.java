package com.edunext.edutrack.api.feature.notifications;

import java.time.Instant;

/**
 * The wire shapes for {@code /notifications}, matching
 * {@code contracts/openapi.yaml} §notifications.
 */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One bell entry.
     *
     * @param eventKey the {@code NotificationEvent} name. Sent as the raw
     *                 stored string rather than a parsed enum, so a row written
     *                 by a newer deploy still renders instead of failing the
     *                 whole page.
     * @param ticketId the human ticket code (CRM-26-00347), not the row id —
     *                 the contract types it as a string and the code is what a
     *                 client links on. Null when the notification belongs to no
     *                 ticket, which a digest and a suppressed address do not.
     * @param deepLink where clicking it goes.
     */
    public record Notification(
            long id,
            String eventKey,
            String title,
            String body,
            String ticketId,
            boolean isRead,
            Instant createdAt,
            String deepLink) {
    }

    /**
     * @param unreadCount always the caller's <strong>total</strong> unread, not
     *                    the count within the current tab. It drives the bell
     *                    badge, and a badge that changed every time you clicked
     *                    a tab would be reporting something nobody asked about.
     */
    public record Meta(String nextCursor, boolean hasMore, int unreadCount) {
    }
}
