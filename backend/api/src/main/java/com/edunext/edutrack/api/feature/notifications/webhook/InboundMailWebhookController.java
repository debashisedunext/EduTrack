package com.edunext.edutrack.api.feature.notifications.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-039 · {@code POST /webhooks/email/inbound}.
 *
 * <p>Sits beside {@link BounceWebhookController} and follows it exactly:
 * unauthenticated by necessity, so the HMAC signature over the raw body is the
 * only thing standing between this route and anyone on the internet writing
 * comments into tickets. The verifier fails closed when no secret is
 * configured.
 *
 * <p><strong>202 for everything that is not a bad signature</strong>, including
 * a reply we chose to drop. The caller is a provider whose only vocabulary is
 * "retry" — a 4xx or 5xx buys a redelivery of a message that will be dropped
 * identically, and enough of those get the endpoint disabled at their end. The
 * decision and its reason are in the log, where the people who can act on it
 * are, rather than in a status code aimed at a machine that cannot.
 */
@RestController
@RequestMapping("/api/v1/webhooks/email")
public class InboundMailWebhookController {

    private static final Logger log = LoggerFactory.getLogger(InboundMailWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final InboundReplyService replies;
    private final ObjectMapper objectMapper;

    public InboundMailWebhookController(WebhookSignatureVerifier signatureVerifier,
                                        @Lazy InboundReplyService replies,
                                        ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.replies = replies;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/inbound")
    public ResponseEntity<Void> inbound(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature) {

        if (!signatureVerifier.isValid(rawBody, signature)) {
            // No body: an unverified caller learns nothing about what this
            // endpoint expects, which is the whole point of signing it.
            log.warn("webhook: rejected inbound mail with an invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        InboundReply reply;
        try {
            reply = objectMapper.readValue(rawBody, InboundReply.class);
        } catch (Exception e) {
            // Signed, so it came from the provider — the shape is simply not one
            // this handler knows. Retrying will not change that.
            log.warn("webhook: inbound mail body could not be read, dropped", e);
            return ResponseEntity.accepted().build();
        }

        replies.accept(reply);
        return ResponseEntity.accepted().build();
    }
}
