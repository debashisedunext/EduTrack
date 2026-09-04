package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.mail.EmailSuppressions;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.springframework.stereotype.Component;

/**
 * B-110 · the EMAIL channel — the one adapter phase 2 ships.
 *
 * <p>What is channel-specific and therefore lives here rather than in the
 * dispatcher: an address has to exist, and it must not be one the provider
 * has told us is dead. D-034's {@link EmailSuppressions} is consulted before
 * every send for the same reason D-010 consults it — every avoidable send to a
 * bounced address costs sender reputation, which decides whether the
 * <em>next</em> sign-off request reaches anyone.
 *
 * <p>Both refusals are permanent. Neither changes by waiting, and the failure
 * notice the dispatcher raises is how somebody learns to fix the address.
 */
@Component
public class EmailChannelAdapter implements ObChannelAdapter {

    private final ObMailTransport transport;
    private final EmailSuppressions suppressions;

    public EmailChannelAdapter(ObMailTransport transport, EmailSuppressions suppressions) {
        this.transport = transport;
        this.suppressions = suppressions;
    }

    @Override
    public ObChannel channel() {
        return ObChannel.EMAIL;
    }

    @Override
    public DeliveryOutcome deliver(ObOutboxMessage message) {
        String email = message.details().email();
        if (email == null || email.isBlank()) {
            return new DeliveryOutcome.PermanentFailure("Recipient has no email address on file");
        }
        if (suppressions.isSuppressed(email)) {
            return new DeliveryOutcome.PermanentFailure(
                    "Address suppressed after an earlier bounce or complaint");
        }
        return transport.send(message);
    }
}
