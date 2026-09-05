package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-112 · the IN_APP adapter's three decisions — write, refuse, or recognise a
 * re-delivery.
 *
 * <p>No database here; {@code ObInAppDeliveryIT} proves the write against real
 * MySQL, including the unique key these tests assume.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InAppChannelAdapterTest {

    private final ObInAppRenderer renderer = new ObInAppRenderer(new ObMailLinks("https://edutrack.example"));
    private final ObInAppNotificationWriter writer = mock(ObInAppNotificationWriter.class);
    private final InAppChannelAdapter adapter = new InAppChannelAdapter(renderer, writer);

    @Test
    void it_is_the_in_app_channel() {
        assertThat(adapter.channel()).isEqualTo(ObChannel.IN_APP);
    }

    @Test
    void a_staff_recipient_gets_an_entry_and_the_row_is_marked_sent() {
        when(writer.write(anyLong(), any(), any())).thenReturn(OptionalLong.of(4242));

        DeliveryOutcome outcome = adapter.deliver(staff(ObNotificationEvent.TAT_BREACHED, Map.of(
                "client_name", "Northwind Technologies",
                "step_title", "Data migration",
                "overdue_by", "2 working days")));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Sent.class);
        // The bell id is recorded as the provider message id, prefixed so it can
        // never be mistaken for a mail provider's — which is what the bounce
        // webhook looks rows up by.
        assertThat(((DeliveryOutcome.Sent) outcome).providerMessageId()).isEqualTo("ob-notification:4242");
        verify(writer).write(eq(7L), any(), any());
    }

    /**
     * <b>The scope line, asserted.</b> §9's portal screens have no notification
     * centre, so an entry addressed to a contact would be a notification with
     * nowhere to appear — and whoever queued it would believe the client had
     * been told. Permanent rather than a wait, because unlike WHATSAPP the
     * missing thing is a screen and not an adapter, and an in-app notice
     * delivered six months late is not the message anybody queued.
     */
    @Test
    void a_client_contact_is_refused_permanently_and_nothing_is_written() {
        ObOutboxMessage message = new ObOutboxMessage(
                101, ObNotificationEvent.SIGNOFF_REQUESTED.key(), ObChannel.IN_APP,
                new ObRecipient.Client(9),
                new ObOutboxMessage.RecipientDetails("Priya Nair", "priya@acme.test", null, true, true),
                77L, 12L, 34L, Map.of("client_name", "Acme"), 0);

        DeliveryOutcome outcome = adapter.deliver(message);

        assertThat(outcome).isInstanceOf(DeliveryOutcome.PermanentFailure.class);
        assertThat(((DeliveryOutcome.PermanentFailure) outcome).reason())
                .isEqualTo(InAppChannelAdapter.NO_CLIENT_SURFACE);
        verify(writer, never()).write(anyLong(), any(), any());
    }

    /**
     * A lapsed lease means the dispatcher re-delivers. On EMAIL that costs a
     * duplicate mail; here the unique key turns it into a no-op, and the entry
     * it collided with <em>is</em> the delivery — so this is Sent, not a
     * failure that would climb the retry ladder to FAILED and raise a notice
     * about a notification that was in fact delivered.
     */
    @Test
    void a_re_delivery_after_a_lapsed_lease_is_not_a_failure() {
        when(writer.write(anyLong(), any(), any())).thenReturn(OptionalLong.empty());
        when(writer.findByOutboxId(101)).thenReturn(OptionalLong.of(4242));

        DeliveryOutcome outcome = adapter.deliver(staff(ObNotificationEvent.GO_LIVE,
                Map.of("client_name", "Contoso")));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Sent.class);
        assertThat(((DeliveryOutcome.Sent) outcome).providerMessageId()).isEqualTo("ob-notification:4242");
    }

    @Test
    @DisplayName("a re-delivery whose entry cannot be found is still Sent")
    void aRedeliveryWithNoFoundRowIsStillSent() {
        // The row was purged between the collision and the lookup — vanishingly
        // rare, and still not a reason to retry a message that has landed.
        when(writer.write(anyLong(), any(), any())).thenReturn(OptionalLong.empty());
        when(writer.findByOutboxId(anyLong())).thenReturn(OptionalLong.empty());

        DeliveryOutcome outcome = adapter.deliver(staff(ObNotificationEvent.GO_LIVE,
                Map.of("client_name", "Contoso")));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Sent.class);
        assertThat(((DeliveryOutcome.Sent) outcome).providerMessageId()).isNull();
    }

    /**
     * The adapter passes the rendered content through untouched — the wording
     * decision is the template's and the renderer's, and an adapter that
     * adjusted it would be a second place to look for why an entry reads oddly.
     */
    @Test
    void the_rendered_content_reaches_the_writer_unchanged() {
        when(writer.write(anyLong(), any(), any())).thenReturn(OptionalLong.of(1));
        ArgumentCaptor<ObInAppContent> written = ArgumentCaptor.forClass(ObInAppContent.class);

        adapter.deliver(staff(ObNotificationEvent.STEP_ASSIGNED, Map.of(
                "client_name", "Contoso",
                "step_title", "User training",
                "due_on", "12 Sep 2026")));

        verify(writer).write(anyLong(), any(), written.capture());
        assertThat(written.getValue().title()).isEqualTo("User training — Contoso");
        assertThat(written.getValue().body()).isEqualTo("Assigned to you, due 12 Sep 2026.");
        assertThat(written.getValue().linkUrl()).isEqualTo("/onboarding/clients/77");
    }

    private static ObOutboxMessage staff(ObNotificationEvent event, Map<String, Object> payload) {
        return new ObOutboxMessage(
                101, event.key(), ObChannel.IN_APP, new ObRecipient.Staff(7),
                new ObOutboxMessage.RecipientDetails("Meera Iyer", "meera@edunext.test", null, false, true),
                77L, 12L, 34L, payload, 0);
    }
}
