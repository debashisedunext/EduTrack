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

    /**
     * D-046 · the ids the client has actually put on screen.
     *
     * <p>Ids the caller does not own are ignored rather than rejected. This is
     * a report, not a command: there is nothing useful a client can do about
     * "one of those was not yours", and answering 404 would turn the ack into
     * a way to probe which notification ids exist.
     */
    public record DeliveredRequest(java.util.List<Long> ids) {

        public DeliveredRequest {
            ids = ids == null ? java.util.List.of() : java.util.List.copyOf(ids);
        }
    }
}
