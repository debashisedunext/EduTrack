package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * B-112 · the {@code IN_APP} channel — OB-13's half of the outbox.
 *
 * <p>The adapter B-110 deliberately did not write: <em>"IN_APP is not shipped
 * here, and that is a scope line rather than a gap. B-112 owns OB-13 and is
 * where 'what does an onboarding bell entry look like' gets decided; an adapter
 * written before that would either invent it or reach into Stream D's
 * NotificationEvent."</em> This is that decision, and it reached neither: the
 * wording is {@link ObInAppTemplate}, the tab is
 * {@link com.edunext.edutrack.domain.onboarding.outbox.ObCategory} on the
 * module's own event catalogue, and the store is {@code ob_notifications}.
 *
 * <p><strong>Delivery here is a database write, which is the only thing that
 * makes this channel unusual.</strong> Every other adapter hands a message to
 * something outside the process and cannot know whether it arrived; this one
 * "delivers" by inserting the row the bell reads. What that buys is exactness —
 * {@code uq_ob_notifications_outbox} makes a re-delivered lease a no-op instead
 * of a duplicate entry — and what it costs is that a bell entry is only as
 * durable as the row, which is the same thing the reader is looking at anyway.
 *
 * <h2>A client contact is refused, permanently, and that is the scope line</h2>
 *
 * <p>§9's portal screens (CP-01…CP-07) list no notification centre, so there is
 * nowhere for a client's in-app entry to appear. The alternatives were both
 * worse: writing it to {@code ob_notifications} would mean a staff-worded row
 * one forgotten predicate away from a client's screen, and leaving it PENDING
 * — the treatment WHATSAPP gets — would be wrong, because that state means "an
 * adapter is coming" and an in-app notice delivered six months late is not the
 * message anybody queued.
 *
 * <p>So it fails now, with a reason, and B-110's
 * {@link ObDeliveryFailureNotifier} raises it to the Admins. Visible beats
 * silent: somebody queued that row believing the client would be told.
 */
@Component
public class InAppChannelAdapter implements ObChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(InAppChannelAdapter.class);

    static final String NO_CLIENT_SURFACE =
            "The client portal has no notification centre; OB-13 is a staff screen. "
                    + "Send this one by email.";

    /**
     * What {@code provider_message_id} records for this channel. Prefixed so a
     * bell id can never be mistaken for a mail provider's, which is what the
     * webhook looks rows up by.
     */
    private static final String ID_PREFIX = "ob-notification:";

    private final ObInAppRenderer renderer;
    private final ObInAppNotificationWriter writer;

    InAppChannelAdapter(ObInAppRenderer renderer, ObInAppNotificationWriter writer) {
        this.renderer = renderer;
        this.writer = writer;
    }

    @Override
    public ObChannel channel() {
        return ObChannel.IN_APP;
    }

    @Override
    public DeliveryOutcome deliver(ObOutboxMessage message) {
        if (!(message.recipient() instanceof ObRecipient.Staff staff)) {
            return new DeliveryOutcome.PermanentFailure(NO_CLIENT_SURFACE);
        }

        ObInAppContent content = renderer.render(message);
        OptionalLong written = writer.write(staff.userId(), message, content);
        if (written.isPresent()) {
            return new DeliveryOutcome.Sent(ID_PREFIX + written.getAsLong());
        }

        // Not a failure. The lease lapsed and the row was re-delivered; the
        // entry that collided with this insert is the delivery. See
        // ObInAppNotificationWriter's note.
        OptionalLong existing = writer.findByOutboxId(message.id());
        log.info("ob-inapp: row {} already has bell entry {} — re-delivery after a lapsed lease",
                message.id(), existing.isPresent() ? existing.getAsLong() : "unknown");
        return new DeliveryOutcome.Sent(
                existing.isPresent() ? ID_PREFIX + existing.getAsLong() : null);
    }
}
