package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * D-044 · telling the user's other tabs that the badge moved.
 *
 * <p>D-041 left a hole its own README named: nothing announced a read, so
 * marking a notification read in one tab left every other tab's badge counting
 * it until something happened to refetch. The bell is the one piece of chrome
 * on every screen, so a stale one is visible all day — and a badge that says
 * three when there is nothing to read trains people to ignore it, which costs
 * more than the badge is worth.
 *
 * <p><strong>The event carries no unread count.</strong> Only what changed, so
 * the client refetches and takes the authoritative number from the database.
 * Sending a count would mean computing it in a second query after commit and
 * racing every other tab to arrive in order; two reads in the same second could
 * then leave the badge on whichever count landed last rather than the true one.
 * Realtime here is a nudge, exactly as {@code realtime/client.ts} describes —
 * the refetch is what is believed.
 *
 * <p><strong>Published after commit, never inside the transaction.</strong> A
 * rolled-back mark-read that had already told four tabs to decrement would
 * leave every one of them wrong until reload, and this is the surface where
 * that is most obvious.
 */
@Component
class NotificationBroadcaster {

    private final RealtimePublisher realtime;

    NotificationBroadcaster(RealtimePublisher realtime) {
        this.realtime = realtime;
    }

    /** One notification read — the other tabs decrement. */
    void read(long userId, long notificationId) {
        afterCommit(() -> realtime.publish(
                RealtimeDestinations.user(userId),
                Map.of("event", "notification.read", "id", notificationId)));
    }

    /**
     * Everything read at once.
     *
     * <p>A distinct event rather than one {@code notification.read} per row: a
     * user clearing ninety notifications would otherwise put ninety frames on
     * their own queue to say one thing.
     */
    void allRead(long userId, int count) {
        if (count == 0) {
            // Nothing changed, so nothing to announce. Publishing anyway would
            // make every tab refetch to learn the badge is still zero.
            return;
        }
        afterCommit(() -> realtime.publish(
                RealtimeDestinations.user(userId),
                Map.of("event", "notification.all-read", "count", count)));
    }

    /**
     * Run once the surrounding transaction has actually committed.
     *
     * <p>Falls back to running inline when there is no transaction, so a caller
     * outside one still broadcasts rather than silently doing nothing — the
     * failure mode this whole class exists to prevent.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
