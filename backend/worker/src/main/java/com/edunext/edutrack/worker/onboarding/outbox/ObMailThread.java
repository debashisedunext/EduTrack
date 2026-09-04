package com.edunext.edutrack.worker.onboarding.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B-111 · the identifiers that make one client's onboarding mail one
 * conversation.
 *
 * <p>D-032's argument, applied to a journey instead of a ticket: Outlook and
 * Gmail thread on RFC 5322 {@code Message-ID}, {@code In-Reply-To} and
 * {@code References}, never on the subject. An eight-step journey with
 * reminders, sign-off requests and escalations is comfortably twenty mails, and
 * without these headers a SPOC's inbox holds twenty unrelated messages about
 * one implementation — which is the worst view of an onboarding anybody has.
 *
 * <p><strong>The root is synthesised and never sent</strong>, exactly as
 * {@code MailThread.rootOf} does it. The first mail about a journey and the
 * twentieth both reference the same ancestor without anyone having to know which
 * came first, and a first mail that bounced does not orphan the rest. Clients
 * thread on a referenced id whether or not they ever saw a message carrying it.
 *
 * <p>Its own class rather than a method added to {@code MailThread}: that file is
 * Stream D's and its two methods are ticket-shaped. Twenty lines here cost less
 * than a cross-stream edit to the class every ticket mail depends on. The domain
 * property is deliberately the same one — a second Message-ID domain in one
 * deployment is how half the mail gets spam-scored.
 */
@Component
class ObMailThread {

    private final String domain;

    ObMailThread(@Value("${edutrack.mail.message-id-domain:edutrack.local}") String domain) {
        this.domain = domain;
    }

    /**
     * This mail's own identity, keyed on the queue row.
     *
     * <p>Per row rather than per journey, because a duplicate
     * {@code Message-ID} lets a client treat the second mail as a copy of the
     * first and silently drop it — indistinguishable, from the reader's side,
     * from the mail never arriving.
     */
    String messageIdOf(long outboxId) {
        return "<ob." + outboxId + "@" + domain + ">";
    }

    /**
     * The conversation this mail belongs to.
     *
     * <p>The journey where there is one, the client otherwise — a login mail and
     * a prerequisite reminder arrive before any journey has started, and they
     * still belong with everything that follows. Empty only when the row names
     * neither, which A-107 permits for a digest.
     */
    Optional<String> rootOf(ObOutboxMessage message) {
        if (message.journeyId() != null) {
            return Optional.of("<ob-journey." + message.journeyId() + "@" + domain + ">");
        }
        if (message.obClientId() != null) {
            return Optional.of("<ob-client." + message.obClientId() + "@" + domain + ">");
        }
        return Optional.empty();
    }
}
