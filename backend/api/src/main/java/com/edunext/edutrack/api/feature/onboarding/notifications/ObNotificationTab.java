package com.edunext.edutrack.api.feature.onboarding.notifications;

import com.edunext.edutrack.domain.onboarding.outbox.ObCategory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-112 · OB-13's tabs — All / Assignments / Escalations / Reminders.
 *
 * <p>A tab is a view over {@link ObCategory}, not a second taxonomy. The
 * categories live in {@code domain} because the worker stamps them onto every
 * row it writes; the tabs live here because they are a decision about one
 * screen, and renaming a tab must not require touching the dispatcher. Exactly
 * the split {@code NotificationTab} makes for S-26, and stated again because
 * the two files look similar enough that the next person will wonder whether
 * one should have been reused — see {@link ObCategory} for why not.
 *
 * <p><strong>{@link ObCategory#UPDATE} has no tab, deliberately.</strong> A
 * gate opening, a go-live, a prerequisite verified: worth a bell entry, not
 * worth a tab. They appear under All, which is what All is for.
 */
enum ObNotificationTab {

    ALL("all"),
    ASSIGNMENTS("assignments", ObCategory.ASSIGNMENT),
    ESCALATIONS("escalations", ObCategory.ESCALATION),
    REMINDERS("reminders", ObCategory.REMINDER);

    private final String queryValue;
    private final Set<ObCategory> categories;

    ObNotificationTab(String queryValue, ObCategory... categories) {
        this.queryValue = queryValue;
        this.categories = Set.of(categories);
    }

    /**
     * Matched by hand rather than through {@code valueOf}, so an unrecognised
     * tab stays a 400 rather than falling through to All — which would show a
     * caller who mistyped {@code reminder} everything and look like it worked.
     */
    static Optional<ObNotificationTab> fromQuery(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(ALL);
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(tab -> tab.queryValue.equals(normalised))
                .findFirst();
    }

    /**
     * @return the categories this tab admits, or empty for All — which filters
     *         on nothing rather than on "every category we happen to know
     *         about". The difference is what lets a row stamped by a newer
     *         deploy, with a category this build has never heard of, still
     *         appear under All rather than vanishing from every tab at once.
     */
    List<String> categoryCodes() {
        return categories.stream().map(Enum::name).sorted().toList();
    }
}
