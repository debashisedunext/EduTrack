package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.domain.notifications.NotificationEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * S-26's tabs — All / Mentions / Assignments / Escalations / Status requests.
 *
 * <p>A tab is a view over {@link NotificationEvent.Category}, not a second
 * taxonomy. The categories live in {@code domain} because the worker raises
 * events too; the tabs live here because they are a decision about one screen,
 * and renaming a tab must not require touching a scanner.
 *
 * <p><strong>Not every event has a tab, and that is deliberate.</strong>
 * {@code Category.OTHER} events — a comment, an attachment, the daily digest —
 * are worth a bell entry and are not worth a tab of their own. They appear
 * under All, which is what All is for. Inventing tabs S-26 does not have would
 * be a change to the screen rather than to this enum.
 */
enum NotificationTab {

    ALL("all"),
    MENTIONS("mentions", NotificationEvent.Category.MENTION),
    ASSIGNMENTS("assignments", NotificationEvent.Category.ASSIGNMENT),
    ESCALATIONS("escalations", NotificationEvent.Category.ESCALATION),
    STATUS_REQUESTS("status-requests", NotificationEvent.Category.STATUS_REQUEST);

    private final String queryValue;
    private final Set<NotificationEvent.Category> categories;

    NotificationTab(String queryValue, NotificationEvent.Category... categories) {
        this.queryValue = queryValue;
        this.categories = Set.of(categories);
    }

    /**
     * The enum name cannot be the query value — {@code status-requests} carries
     * a hyphen, so {@code valueOf} could never parse it. Matching by hand also
     * keeps an unrecognised tab a 400 rather than a silent fall-through to All,
     * which would show a confused caller everything and look like it worked.
     */
    static Optional<NotificationTab> fromQuery(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(ALL);
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(tab -> tab.queryValue.equals(normalised))
                .findFirst();
    }

    /**
     * @return the event codes this tab admits, or empty for All — which filters
     *         on nothing rather than on "every code we happen to know about".
     *         The difference matters: a row written by a newer deploy, whose
     *         code this build has never heard of, must still show under All.
     */
    List<String> eventCodes() {
        if (categories.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(NotificationEvent.values())
                .filter(event -> categories.contains(event.category()))
                .map(Enum::name)
                .toList();
    }
}
