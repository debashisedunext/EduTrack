package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * D-042 · reading and writing the preference matrix · D-036 · and refusing to
 * write the one thing it must not.
 */
@Service
public class PreferenceService {

    private final NotificationPreferences preferences;
    private final PreferenceRepository repository;

    PreferenceService(NotificationPreferences preferences, PreferenceRepository repository) {
        this.preferences = preferences;
        this.repository = repository;
    }

    /**
     * The whole catalogue with this user's answers applied.
     *
     * <p>Built from the enum rather than from the table, so an event nobody has
     * ever customised still appears — with the table holding only deviations,
     * a matrix assembled from stored rows would be empty for most people.
     */
    @Transactional(readOnly = true)
    public PreferenceDtos.PreferenceMatrix matrixFor(long userId) {
        Map<String, Boolean> inApp = new HashMap<>();
        Map<String, Boolean> email = new HashMap<>();
        Map<String, Boolean> push = new HashMap<>();

        for (NotificationPreferences.ChannelPreference override : preferences.overridesFor(userId)) {
            // A row naming a channel this build has dropped matches neither and
            // is ignored, rather than failing the whole screen.
            if (NotificationChannel.IN_APP.name().equals(override.channel())) {
                inApp.put(override.eventCode(), override.enabled());
            } else if (NotificationChannel.EMAIL.name().equals(override.channel())) {
                email.put(override.eventCode(), override.enabled());
            } else if (NotificationChannel.PUSH.name().equals(override.channel())) {
                push.put(override.eventCode(), override.enabled());
            }
        }

        return new PreferenceDtos.PreferenceMatrix(
                Arrays.stream(NotificationEvent.values())
                        .map(event -> new PreferenceDtos.PreferenceRow(
                                event.name(),
                                event.category().name(),
                                // D-040. The mirror of the mail rule below: the
                                // screen must never show a switch as *on* that
                                // the send path ignores. §11 gives some events a
                                // dash in the in-app popup column, so there is
                                // no toast to enable and the honest reading is
                                // off. The bell entry is unaffected — it is not
                                // a preference and never appears on this screen.
                                event.popsUp() && inApp.getOrDefault(event.name(), true),
                                // A locked mail always reads as on, whatever a
                                // stale row says. The screen must never show a
                                // switch as off that the send path ignores.
                                event.isMandatoryMail() || email.getOrDefault(event.name(), true),
                                event.isMandatoryMail(),
                                // D-045. Never locked: §7.7 gives the guarantee
                                // to mail, and a push only reaches a browser
                                // still subscribed on a device switched on, for
                                // a permission the user can revoke without
                                // telling us. Absence still means enabled, so a
                                // newly declared event pushes immediately.
                                push.getOrDefault(event.name(), true)))
                        .toList());
    }

    /** What happened to a save. */
    public enum SaveOutcome { SAVED, UNKNOWN_EVENT }

    /**
     * Apply a set of changes.
     *
     * <p><strong>An attempt to disable a mandatory mail is discarded, not
     * rejected.</strong> The screen sends the row it was showing, and a 400
     * would fail an otherwise valid save because of a switch the user could not
     * have moved — most likely a client that posted the whole grid back. What
     * matters is that the value never reaches the table; refusing the request
     * as well would punish the caller for our own UI.
     *
     * <p>An event code this build does not know <em>is</em> rejected: unlike a
     * read, there is no row to preserve and no way to honour it, and silently
     * accepting a typo would leave the user believing they had switched
     * something off.
     */
    @Transactional
    public SaveOutcome save(long userId, PreferenceDtos.PreferenceUpdateRequest request) {
        for (PreferenceDtos.PreferenceUpdate update : request.preferences()) {
            NotificationEvent event = NotificationEvent.of(update.eventKey()).orElse(null);
            if (event == null) {
                return SaveOutcome.UNKNOWN_EVENT;
            }
        }

        for (PreferenceDtos.PreferenceUpdate update : request.preferences()) {
            NotificationEvent event = NotificationEvent.of(update.eventKey()).orElseThrow();

            if (update.inApp() != null && event.popsUp()) {
                // D-040, discarded rather than rejected for the same reason a
                // locked mail is: the client posts back the grid it was shown.
                // Storing the row instead would be worse than useless — it
                // would record a choice the send path has no way to honour, and
                // the next reader would have to work out why it changed nothing.
                repository.upsert(userId, event.name(), NotificationChannel.IN_APP, update.inApp());
            }
            if (update.email() != null && !event.isMandatoryMail()) {
                repository.upsert(userId, event.name(), NotificationChannel.EMAIL, update.email());
            }
            if (update.push() != null) {
                // No mandatory guard, because there is no mandatory push.
                repository.upsert(userId, event.name(), NotificationChannel.PUSH, update.push());
            }
        }
        return SaveOutcome.SAVED;
    }
}
