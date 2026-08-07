package com.edunext.edutrack.worker.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default transport until D-029 lands the templates that render a body.
 *
 * <p>It stamps a {@code provider_msg_id} of {@code logging-transport:{id}}
 * rather than leaving it null. {@code email_log} is the delivery-proof table
 * (D-033) and blueprint §17 wants a missed mail to be provable rather than
 * deniable — a row reading SENT with no way to tell that nothing left the
 * process would be exactly the deniable case. The marker makes it obvious in
 * the data, not just in the configuration.
 */
@Component
@ConditionalOnProperty(name = "edutrack.outbox.transport", havingValue = "logging", matchIfMissing = true)
public class LoggingMailTransport implements MailTransport {

    public static final String PROVIDER_ID_PREFIX = "logging-transport:";

    private static final Logger log = LoggerFactory.getLogger(LoggingMailTransport.class);

    @Override
    public SendOutcome send(OutboxMessage message) {
        log.info("outbox: would send id={} event={} to={} subject={}",
                message.id(), message.eventCode(), message.toEmail(), message.subject());
        return new SendOutcome.Sent(PROVIDER_ID_PREFIX + message.id());
    }
}
