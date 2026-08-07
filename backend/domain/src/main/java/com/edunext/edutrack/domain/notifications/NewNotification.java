package com.edunext.edutrack.domain.notifications;

/**
 * An in-app notification to raise.
 *
 * @param userId    who sees it in their bell
 * @param ticketId  the ticket it concerns, or null for system notices
 * @param eventCode the §11 event, e.g. {@code MAIL_DELIVERY_FAILED}
 * @param title     the bell headline
 * @param body      the detail shown when expanded
 * @param linkUrl   deep link to the relevant screen, or null
 */
public record NewNotification(
        long userId,
        Long ticketId,
        String eventCode,
        String title,
        String body,
        String linkUrl) {

    public NewNotification {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (eventCode == null || eventCode.isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
    }
}
