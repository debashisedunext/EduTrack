package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;

import java.util.Map;

/**
 * B-110 · one claimed {@code ob_notification_outbox} row, with its recipient
 * resolved.
 *
 * <p>The queue row stores an id into {@code users} or {@code ob_client_contacts};
 * an adapter needs an address. Resolving at claim time rather than at enqueue
 * is deliberate: a contact whose email is corrected while a reminder waits in
 * the queue gets the reminder at the corrected address, and a contact
 * deactivated meanwhile gets nothing — both of which a copied address would
 * get wrong.
 *
 * <p>No rendered body, for the reason the migration gives: {@code payload} is
 * the template's variables, and B-111 renders them at send time.
 *
 * @param attempts delivery attempts already made — 0 on a row never tried, so
 *                 the count <em>including</em> the one about to happen is
 *                 {@code attempts + 1}
 */
public record ObOutboxMessage(
        long id,
        String eventKey,
        ObChannel channel,
        ObRecipient recipient,
        RecipientDetails details,
        Long obClientId,
        Long journeyId,
        Long stepId,
        Map<String, Object> payload,
        int attempts) {

    public ObOutboxMessage {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /**
     * What the recipient's row says about reaching them.
     *
     * @param name           display name, for a salutation
     * @param email          {@code users.email} or {@code ob_client_contacts.email}
     * @param phone          {@code users.mobile} or {@code ob_client_contacts.phone}; may be null
     * @param whatsappOptIn  B-103's recorded consent. Always false for staff —
     *                       consent is a client-contact fact, and D-101 must
     *                       not send business-initiated WhatsApp without it
     * @param active         false once the user or contact has been deactivated;
     *                       the dispatcher fails such rows without trying
     */
    public record RecipientDetails(
            String name,
            String email,
            String phone,
            boolean whatsappOptIn,
            boolean active) {
    }
}
