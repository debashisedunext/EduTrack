package com.edunext.edutrack.domain.notifications;

/**
 * D-045 · a notification row has been written.
 *
 * <p>Published by {@link NotificationWriter}, which is the one place every bell
 * entry in the system passes through. Delivery to a channel that is not the
 * database hangs off this rather than off each producer, and that is the whole
 * point: there are roughly two dozen §11 events and D-040 has yet to wire most
 * of them, so a design where each producer remembers to push is one where the
 * ones written last silently do not.
 *
 * <p>The listener runs <strong>after commit</strong>. A push is an HTTP call to
 * a third party; making it part of the business transaction would hold a row
 * lock open across somebody else's network, and a rolled-back handoff would
 * already have interrupted the assignee about work that then did not happen.
 *
 * @param notificationId the row, so a client can act on the thing it was told
 *                       about — D-043's Open and Dismiss are actions on a row,
 *                       and a push carrying only text leaves nothing to mark read
 */
public record NotificationRaised(
        long notificationId,
        long userId,
        NotificationEvent event,
        String title,
        String body,
        String linkUrl) {
}
