package com.edunext.edutrack.worker.onboarding.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * B-110 · the default email transport until B-111 lands the templates that
 * render a body.
 *
 * <p>Stamps a {@code provider_message_id} of {@code logging-transport:{id}}
 * rather than leaving it null, for D-010's reason: a row reading SENT with no
 * way to tell that nothing left the process is the deniable case blueprint
 * §17 exists to rule out. The marker makes it obvious in the data, not just in
 * the configuration.
 */
@Component
@ConditionalOnProperty(name = "edutrack.ob-outbox.email.transport", havingValue = "logging",
        matchIfMissing = true)
public class LoggingObMailTransport implements ObMailTransport {

    public static final String PROVIDER_ID_PREFIX = "logging-transport:";

    private static final Logger log = LoggerFactory.getLogger(LoggingObMailTransport.class);

    @Override
    public DeliveryOutcome send(ObOutboxMessage message) {
        log.info("ob-outbox: would email id={} event={} to={} payload={}",
                message.id(), message.eventKey(), message.details().email(), message.payload().keySet());
        return new DeliveryOutcome.Sent(PROVIDER_ID_PREFIX + message.id());
    }
}
