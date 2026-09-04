package com.edunext.edutrack.worker.onboarding.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * B-110 · the default email transport, still the default now that B-111 has
 * landed the templates that render a body.
 *
 * <p>Stamps a {@code provider_message_id} of {@code logging-transport:{id}}
 * rather than leaving it null, for D-010's reason: a row reading SENT with no
 * way to tell that nothing left the process is the deniable case blueprint §17
 * exists to rule out. The marker makes it obvious in the data, not just in the
 * configuration.
 *
 * <p><strong>B-111 · it renders, and that is the difference from
 * {@code LoggingMailTransport}.</strong> On the ticketing side rendering lives
 * only in the SMTP transport, so the default configuration never exercises a
 * template and a body that throws or a layout that will not resolve is
 * discovered by whoever first sets {@code transport=smtp} — in whichever
 * environment that happens to be. Rendering here costs one template render per
 * row in a process that is otherwise idle, and buys the guarantee that every
 * environment running the default has already proved the wording renders. The
 * log line carries the subject that would have been sent, which is also the
 * only way to read one locally.
 */
@Component
@ConditionalOnProperty(name = "edutrack.ob-outbox.email.transport", havingValue = "logging",
        matchIfMissing = true)
public class LoggingObMailTransport implements ObMailTransport {

    public static final String PROVIDER_ID_PREFIX = "logging-transport:";

    private static final Logger log = LoggerFactory.getLogger(LoggingObMailTransport.class);

    private final ObMailRenderer renderer;

    LoggingObMailTransport(ObMailRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public DeliveryOutcome send(ObOutboxMessage message) {
        ObMailContent content;
        try {
            content = renderer.render(message);
        } catch (RuntimeException e) {
            // Same judgement as the SMTP transport: the renderer is built not to
            // throw, so this is a bug, and a bug is transient — the row waits for
            // the fix rather than being failed and forgotten. It would be worse
            // here than there, because a row failed by the *logging* transport
            // was never going anywhere anyway.
            log.error("ob-outbox: rendering failed for id={} event={}",
                    message.id(), message.eventKey(), e);
            return new DeliveryOutcome.TransientFailure("Rendering failed: "
                    + e.getClass().getSimpleName());
        }

        log.info("ob-outbox: would email id={} event={} to={} subject=\"{}\" body={} chars",
                message.id(), message.eventKey(), message.details().email(),
                content.subject(), content.html().length());
        return new DeliveryOutcome.Sent(PROVIDER_ID_PREFIX + message.id());
    }
}
