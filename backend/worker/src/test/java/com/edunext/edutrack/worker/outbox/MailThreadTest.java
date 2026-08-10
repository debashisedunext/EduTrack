package com.edunext.edutrack.worker.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** D-032 · the identifiers a client threads on. */
class MailThreadTest {

    private final MailThread thread = new MailThread("edutrack.test");

    @Test
    @DisplayName("every mail about one ticket shares a root")
    void theRootIsStablePerTicket() {
        assertThat(thread.rootOf(347L)).isEqualTo(thread.rootOf(347L));
        assertThat(thread.rootOf(347L)).isNotEqualTo(thread.rootOf(348L));
    }

    @Test
    @DisplayName("no two mails share a Message-ID")
    void everyMailIsIdentifiedByItsOwnRow() {
        // A duplicate lets a client treat the second mail as a copy of the
        // first and drop it, which is indistinguishable from never sending it.
        assertThat(thread.messageIdOf(347L, 1L)).isNotEqualTo(thread.messageIdOf(347L, 2L));
    }

    @Test
    @DisplayName("a mail never claims the root as its own id")
    void theRootIsNeverAMessageId() {
        // The root is referenced by every mail and sent by none. If one mail
        // owned it, threading would depend on that mail existing.
        assertThat(thread.messageIdOf(347L, 1L)).isNotEqualTo(thread.rootOf(347L));
    }

    @Test
    void nonTicketMailStillGetsAnId() {
        assertThat(thread.messageIdOf(null, 9L)).isEqualTo("<mail.9@edutrack.test>");
    }

    @Test
    @DisplayName("ids are RFC 5322 addr-spec in angle brackets")
    void idsAreWellFormed() {
        assertThat(thread.rootOf(347L)).isEqualTo("<ticket.347@edutrack.test>");
        assertThat(thread.messageIdOf(347L, 12L)).isEqualTo("<ticket.347.mail.12@edutrack.test>");
    }
}
