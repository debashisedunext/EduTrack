package com.edunext.edutrack.domain.onboarding.outbox;

import java.util.Locale;
import java.util.Optional;

/**
 * B-110 · the delivery channels {@code ob_notification_outbox.channel} admits.
 *
 * <p>Three values, matching {@code ck_ob_outbox_channel} in A-107's migration.
 * {@code WHATSAPP} is here while nothing sends it: PHASE-2-BUILD-PLAN.md §6.1
 * keeps the enum value so that adding the channel later is one adapter class
 * (D-101) rather than a migration against a table with production rows. A row
 * queued for a channel with no adapter waits in the queue rather than failing
 * — see {@code ObOutboxDispatcher} — so the deferral is not a one-way door.
 *
 * <p>Distinct from {@link com.edunext.edutrack.domain.notifications.NotificationChannel}
 * on purpose. That enum is D-042's <em>preference</em> vocabulary for the
 * ticketing bell (it has {@code PUSH} and no {@code WHATSAPP}); this is the
 * onboarding queue's delivery vocabulary, and the CHECK constraint is the
 * authority on it.
 */
public enum ObChannel {

    EMAIL,
    WHATSAPP,
    IN_APP;

    /** Tolerant read: a row carrying a value this build does not know is skipped, not thrown on. */
    public static Optional<ObChannel> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
