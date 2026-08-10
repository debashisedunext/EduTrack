package com.edunext.edutrack.api.feature.notifications;

import java.util.List;

/**
 * D-042 · the wire shapes for {@code /me/notification-preferences}.
 */
public final class PreferenceDtos {

    private PreferenceDtos() {
    }

    /**
     * One row of the matrix.
     *
     * <p><strong>The server sends the whole catalogue, not just the
     * overrides.</strong> A screen that had to know every event code to render
     * the grid would be a second copy of {@link
     * com.edunext.edutrack.domain.notifications.NotificationEvent}, and the two
     * would drift the first time an event was added — the new switch simply
     * would not appear, silently, for everyone.
     *
     * @param eventKey   the stored code
     * @param category   S-26's grouping, so the screen can section the grid
     *                   without a second mapping of its own
     * @param inApp      whether the toast is on
     * @param email      whether mail is on
     * @param emailLocked D-036: this mail cannot be switched off. Sent rather
     *                   than inferred from the category, so the rule has one
     *                   home and the UI cannot disagree with the send path.
     */
    public record PreferenceRow(
            String eventKey,
            String category,
            boolean inApp,
            boolean email,
            boolean emailLocked) {
    }

    public record PreferenceMatrix(List<PreferenceRow> data) {
    }

    /**
     * A change to one event's channels.
     *
     * <p>Boxed so absent means "leave alone". A screen saving one row must not
     * have to restate every other switch, and a client that omits a field
     * should not silently turn it off.
     */
    public record PreferenceUpdate(String eventKey, Boolean inApp, Boolean email) {
    }

    public record PreferenceUpdateRequest(List<PreferenceUpdate> preferences) {

        public PreferenceUpdateRequest {
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
        }
    }
}
