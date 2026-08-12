package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.mail.InboundMailReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-032 writes the thread headers; D-039 reads a ticket id back out of them.
 * They are in different modules and neither imports the other, so nothing but
 * this test stops the two drifting apart — and the drift would be silent.
 * Changing the id format would not fail a compile or any test on either side;
 * inbound replies would simply stop matching a ticket and start being dropped,
 * which looks exactly like nobody replying.
 *
 * <p>The worker is the only module that can see both, which is why this lives
 * here rather than next to either class.
 */
class MailThreadRoundTripTest {

    private final MailThread thread = new MailThread("edutrack.local");

    @Test
    @DisplayName("the thread root D-032 emits resolves back to its ticket")
    void rootRoundTrips() {
        String root = thread.rootOf(347L);

        assertThat(InboundMailReference.ticketIdFrom(root, null)).contains(347L);
    }

    @Test
    @DisplayName("a per-mail Message-ID resolves back to its ticket")
    void messageIdRoundTrips() {
        String messageId = thread.messageIdOf(347L, 9001L);

        assertThat(InboundMailReference.ticketIdFrom(messageId, null)).contains(347L);
    }

    @Test
    @DisplayName("a real reply — In-Reply-To the last mail, References the whole chain")
    void aRealReplyResolves() {
        // What a client actually sends back: it answers one mail and quotes the
        // chain, so both headers are present and both name the same ticket.
        String inReplyTo = thread.messageIdOf(347L, 9002L);
        String references = thread.rootOf(347L) + " "
                + thread.messageIdOf(347L, 9001L) + " "
                + thread.messageIdOf(347L, 9002L);

        assertThat(InboundMailReference.ticketIdFrom(inReplyTo, references)).contains(347L);
    }

    @Test
    @DisplayName("mail with no ticket resolves to nothing rather than to a wrong ticket")
    void nonTicketMailResolvesToNothing() {
        // messageIdOf(null, …) emits <mail.{id}@domain> — a password reset, a
        // digest. There is no ticket to file a reply against.
        String messageId = thread.messageIdOf(null, 5000L);

        assertThat(InboundMailReference.ticketIdFrom(messageId, null)).isEmpty();
    }

    @Test
    @DisplayName("a chain stitched across two tickets resolves to neither")
    void twoTicketsInOneChainIsAmbiguous() {
        String references = thread.rootOf(347L) + " " + thread.rootOf(999L);

        assertThat(InboundMailReference.ticketIdFrom(null, references)).isEmpty();
    }

    @Test
    @DisplayName("the domain is configuration and must not affect resolution")
    void domainIsIrrelevantToResolution() {
        // message-id-domain is per-deployment. If the parser were anchored to
        // one, every reply on staging would silently stop matching.
        MailThread other = new MailThread("tickets.edunext.co.in");

        assertThat(InboundMailReference.ticketIdFrom(other.rootOf(347L), null)).contains(347L);
    }
}
