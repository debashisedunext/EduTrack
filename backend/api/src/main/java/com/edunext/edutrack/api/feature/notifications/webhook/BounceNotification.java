package com.edunext.edutrack.api.feature.notifications.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * EduTrack's canonical bounce/complaint payload.
 *
 * <p>The contract types this body as a free-form object because the shape is
 * the provider's, not ours, and the provider is not chosen yet (D-033 notes
 * that {@code JavaMailSender} surfaces no provider message id either). Rather
 * than commit to one vendor's envelope, this is the shape we accept, with
 * aliases for the field names SES and SendGrid happen to use. When a provider
 * is chosen, a thin adapter maps its envelope onto this record — the handler
 * below does not change.
 *
 * <p>Unknown fields are ignored: providers add them, and a webhook that 400s on
 * an unrecognised key becomes a retry storm on their side.
 *
 * @param email      the address that bounced or complained
 * @param type       {@code BOUNCE} or {@code COMPLAINT}; anything else is rejected
 * @param detail     the provider's diagnostic, stored verbatim
 * @param providerMessageId the send that triggered it, if the provider says
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BounceNotification(
        @JsonAlias({"recipient", "emailAddress"}) String email,
        @JsonAlias({"eventType", "notificationType"}) String type,
        @JsonAlias({"diagnosticCode", "reason"}) String detail,
        @JsonAlias({"messageId", "providerMsgId"}) String providerMessageId) {
}
