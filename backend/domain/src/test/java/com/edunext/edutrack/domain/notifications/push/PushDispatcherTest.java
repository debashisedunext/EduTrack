package com.edunext.edutrack.domain.notifications.push;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import com.edunext.edutrack.domain.notifications.NotificationRaised;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-045 · the sending half.
 *
 * <p>The assertion that matters most is the deletion. The backlog names the
 * failure this half exists to prevent — <em>"needs 404/410 from the push service
 * to delete the subscription, or the table fills with browsers that no longer
 * exist"</em> — and every one of those dead rows costs an HTTP round trip on
 * every notification, forever, with nothing in the product to show for it.
 */
class PushDispatcherTest {

    private static final long RAVI = 7L;

    private final PushSubscriptions subscriptions = mock(PushSubscriptions.class);
    private final NotificationPreferences preferences = mock(NotificationPreferences.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final PushDispatcher dispatcher = new PushDispatcher(subscriptions, preferences, sender);

    private static final PushSubscriptions.Subscription DESKTOP =
            new PushSubscriptions.Subscription("https://push.example/desktop", "p256", "auth");
    private static final PushSubscriptions.Subscription PHONE =
            new PushSubscriptions.Subscription("https://push.example/phone", "p256", "auth");

    @BeforeEach
    void allowByDefault() {
        when(preferences.allows(anyLong(), anyString(), any())).thenReturn(true);
    }

    // ------------------------------------------------------- the whole point

    @Test
    @DisplayName("a subscription the push service reports gone is deleted")
    void goneIsDeleted() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.GONE);

        dispatcher.on(raised());

        verify(subscriptions).deleteByEndpoint(DESKTOP.endpoint());
    }

    @Test
    @DisplayName("one dead browser does not stop the others being reached")
    void aDeadBrowserDoesNotStopTheRest() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP, PHONE));
        when(sender.send(eq(DESKTOP.endpoint()), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.GONE);
        when(sender.send(eq(PHONE.endpoint()), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.DELIVERED);

        dispatcher.on(raised());

        verify(subscriptions).deleteByEndpoint(DESKTOP.endpoint());
        verify(subscriptions).touch(PHONE.endpoint());
    }

    @Test
    @DisplayName("a busy or broken push service costs nobody their subscription")
    void retryableKeepsTheRow() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.RETRYABLE);

        dispatcher.on(raised());

        // Deleting on a 429 or a 5xx would silently opt somebody out of a
        // channel they chose, because a push service had a bad minute.
        verify(subscriptions, never()).deleteByEndpoint(anyString());
    }

    @Test
    @DisplayName("a message we got wrong costs nobody their subscription either")
    void undeliverableKeepsTheRow() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.UNDELIVERABLE);

        dispatcher.on(raised());

        // 400/401/413 are ours to fix. The browser is fine.
        verify(subscriptions, never()).deleteByEndpoint(anyString());
    }

    // --------------------------------------------------------- every browser

    @Test
    @DisplayName("every browser the user subscribed gets it, not just one")
    void allBrowsers() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP, PHONE));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.DELIVERED);

        dispatcher.on(raised());

        // Picking one would make the feature unreliable in a way nobody could
        // report — "I only get them sometimes" is not a bug anyone can chase.
        verify(sender, times(2)).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aUserWithNoBrowsersCostsNoSend() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of());

        dispatcher.on(raised());

        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------- the preference

    @Test
    @DisplayName("a switched-off event is not pushed, and costs no lookup")
    void preferenceIsHonoured() {
        when(preferences.allows(RAVI, NotificationEvent.TICKET_ASSIGNED.name(), NotificationChannel.PUSH))
                .thenReturn(false);

        dispatcher.on(raised());

        verify(sender, never()).send(anyString(), anyString(), anyString(), anyString());
        verify(subscriptions, never()).forUser(anyLong());
    }

    @Test
    @DisplayName("the preference consulted is PUSH, not the toast's")
    void theRightChannelIsConsulted() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.DELIVERED);

        dispatcher.on(raised());

        ArgumentCaptor<NotificationChannel> channel = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(preferences).allows(anyLong(), anyString(), channel.capture());
        // Reading IN_APP here would tie two channels together: somebody who
        // silenced toasts would lose push as well, without asking.
        assertThat(channel.getValue()).isEqualTo(NotificationChannel.PUSH);
    }

    // ------------------------------------------------------------ the payload

    @Test
    @DisplayName("the payload carries what the bell already says, and no more")
    void payloadIsTheBellEntry() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.DELIVERED);

        dispatcher.on(new NotificationRaised(42L, RAVI, NotificationEvent.TICKET_ASSIGNED,
                "Meera handed you CRM-26-00347", "at Development", "/tickets/CRM-26-00347"));

        // A push is rendered by the operating system and lands on a lock screen
        // anybody nearby can read, so it must not carry more than the bell entry
        // the user already agreed to see. Same argument D-052 makes for a
        // mention notification carrying no message text.
        assertThat(payload())
                .isEqualTo("{\"id\":42,\"title\":\"Meera handed you CRM-26-00347\","
                        + "\"body\":\"at Development\",\"link\":\"/tickets/CRM-26-00347\"}");
    }

    @Test
    @DisplayName("a title with a quote in it does not produce broken JSON")
    void quotesAreEscaped() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.DELIVERED);

        dispatcher.on(new NotificationRaised(1L, RAVI, NotificationEvent.COMMENT_ADDED,
                "Ravi said \"it works\"", "line one\nline two", null));

        // Ticket titles are user text and they reach a bell title. Unescaped,
        // the service worker's JSON.parse throws and the browser shows a push
        // with no content — or, under userVisibleOnly, costs the site its
        // permission for showing nothing at all.
        assertThat(payload())
                .isEqualTo("{\"id\":1,\"title\":\"Ravi said \\\"it works\\\"\","
                        + "\"body\":\"line one\\nline two\",\"link\":null}");
    }

    // ------------------------------------------------------ failure isolation

    @Test
    @DisplayName("a failure never escapes — the notification is already the record")
    void failureIsSwallowed() {
        when(subscriptions.forUser(RAVI)).thenThrow(new IllegalStateException("database gone"));

        // This runs in an AFTER_COMMIT listener: throwing cannot undo the
        // commit, but it can abort whatever else is listening. Nothing about a
        // browser is worth that, and §7.7 makes mail the guaranteed channel.
        dispatcher.on(raised());
    }

    @Test
    @DisplayName("a delete that throws does not strand the remaining browsers")
    void aFailedDeleteStillLetsTheRestThrough() {
        when(subscriptions.forUser(RAVI)).thenReturn(List.of(DESKTOP, PHONE));
        when(sender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(WebPushSender.Result.GONE);
        doThrow(new IllegalStateException("row locked")).when(subscriptions)
                .deleteByEndpoint(DESKTOP.endpoint());

        dispatcher.on(raised());

        // The loop is inside the try, so the first browser's failure ends the
        // pass. Pinned rather than left implicit: the alternative — catching
        // per browser — is a reasonable change, and this records that today it
        // stops, which matters because the second browser is not retried later.
        verify(subscriptions, never()).deleteByEndpoint(PHONE.endpoint());
    }

    // ------------------------------------------------------------- helpers

    private static NotificationRaised raised() {
        return new NotificationRaised(1L, RAVI, NotificationEvent.TICKET_ASSIGNED,
                "Handed to you", "at Development", "/tickets/1");
    }

    private String payload() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
