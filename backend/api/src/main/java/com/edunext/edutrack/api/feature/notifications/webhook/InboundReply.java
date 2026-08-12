package com.edunext.edutrack.api.feature.notifications.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * D-039 · EduTrack's canonical inbound-mail payload.
 *
 * <p>Same reasoning as {@link BounceNotification}: the contract types this body
 * as a free-form object because the shape belongs to whichever provider is
 * eventually chosen, so this is the shape <em>we</em> accept, with aliases for
 * the names SES, SendGrid and Mailgun use. A thin adapter maps a real envelope
 * onto this record and the handler does not change.
 *
 * <p>Unknown fields are ignored — providers add them, and a webhook that 400s
 * on an unrecognised key becomes a retry storm on their side.
 *
 * @param from       the sender's address, matched against {@code users.email}
 * @param inReplyTo  RFC 5322 {@code In-Reply-To}; names the mail being answered
 * @param references RFC 5322 {@code References}; the whole quoted chain
 * @param text       the plain-text part, before quoted material is stripped
 * @param subject    carried for logging only — deliberately <strong>not</strong>
 *                   used to identify the ticket, see {@link
 *                   com.edunext.edutrack.domain.mail.InboundMailReference}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundReply(
        @JsonAlias({"sender", "fromAddress", "From"}) String from,
        @JsonAlias({"in-reply-to", "In-Reply-To", "inReplyToHeader"}) String inReplyTo,
        @JsonAlias({"References", "referencesHeader"}) String references,
        @JsonAlias({"plain", "text-body", "bodyPlain", "TextBody"}) String text,
        @JsonAlias({"Subject"}) String subject) {
}
