package com.edunext.edutrack.domain.notifications;

/**
 * An in-app notification to raise.
 *
 * @param userId   who sees it in their bell
 * @param ticketId the ticket it concerns, or null for system notices
 * @param event    the §11 event. Typed rather than a string because S-26's tabs
 *                 filter on it (D-041) — a misspelt code would land the
 *                 notification in "All" and nowhere else, silently and forever.
 * @param title    the bell headline
 * @param body     the detail shown when expanded
 * @param linkUrl  deep link to the relevant screen, or null
 */
public record NewNotification(
        long userId,
        Long ticketId,
        NotificationEvent event,
        String title,
        String body,
        String linkUrl) {

    public NewNotification {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
    }
}
