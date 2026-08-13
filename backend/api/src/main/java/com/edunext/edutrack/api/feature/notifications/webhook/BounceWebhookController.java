package com.edunext.edutrack.api.feature.notifications.webhook;

import com.edunext.edutrack.domain.mail.EmailSuppressions;
import com.edunext.edutrack.domain.mail.EmailSuppressions.SuppressionReason;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * D-034 · {@code POST /api/v1/webhooks/email/bounce}.
 *
 * <p>Blueprint §4B.6: bounce and complaint webhooks "mark the address invalid
 * and alert the Admin". Both halves matter — suppression stops us mailing a
 * dead address and burning sender reputation; the alert is how a human learns
 * that an assignee has stopped receiving anything at all.
 *
 * <p>Takes the body as {@code byte[]} because the signature is computed over
 * the exact bytes received. Binding to a record first and re-serialising to
 * verify would compare a digest of Jackson's output, not of what the provider
 * sent, and would never match.
 */
@RestController
@RequestMapping("/api/v1/webhooks/email")
public class BounceWebhookController {

    static final NotificationEvent EVENT_CODE = NotificationEvent.EMAIL_ADDRESS_SUPPRESSED;
    private static final String ADMIN_ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(BounceWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final EmailSuppressions suppressions;
    private final NotificationWriter notifications;
    private final ObjectMapper objectMapper;

    /**
     * The two JDBC-backed collaborators are injected {@code @Lazy}. A
     * controller is an eager singleton, so injecting them directly would force
     * a {@code DataSource} at context startup and break the contexts that
     * deliberately run without one — api's {@code ApplicationSmokeTest} proves
     * the application wires on a laptop with nothing installed. The proxy
     * resolves on first request, by which point a real deployment has a
     * database.
     */
    public BounceWebhookController(WebhookSignatureVerifier signatureVerifier,
                                   @Lazy EmailSuppressions suppressions,
                                   @Lazy NotificationWriter notifications,
                                   ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.suppressions = suppressions;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    /*
     * A-033 · permitAll, and it means "do not check the wrong credential"
     * rather than "do not check".
     *
     * The caller is a mail provider with no EduTrack account and no token to
     * present, so there is no principal for a permission to attach to. What
     * authenticates it is the HMAC over the raw bytes, verified on the first
     * line of the method by WebhookSignatureVerifier — which fails closed when
     * no secret is configured, so an unconfigured deployment rejects every
     * request rather than accepting every request.
     *
     * Written explicitly for the same reason as login's: RouteAuthorizationTest
     * requires a decision on every handler, and "public" is a decision. The
     * alternative — leaving it off and letting SecurityConfig's path list carry
     * it alone — is exactly how a route ends up public because nobody looked.
     */
    @PostMapping("/bounce")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> bounce(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature) {

        if (!signatureVerifier.isValid(rawBody, signature)) {
            // 401 with no body: an unverified caller learns nothing about
            // whether the address, or the endpoint's expectations, are right.
            log.warn("webhook: rejected a bounce notification with an invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        BounceNotification notification;
        try {
            notification = objectMapper.readValue(rawBody, BounceNotification.class);
        } catch (Exception e) {
            log.warn("webhook: bounce payload could not be parsed", e);
            return ResponseEntity.badRequest().build();
        }

        SuppressionReason reason = reasonOf(notification.type());
        if (notification.email() == null || notification.email().isBlank() || reason == null) {
            log.warn("webhook: bounce payload missing email or with unknown type '{}'",
                    notification.type());
            return ResponseEntity.badRequest().build();
        }

        boolean firstTime = suppressions.suppress(
                notification.email(), reason, notification.detail(),
                notification.providerMessageId());

        // Only alert on the first suppression. A provider may replay a bounce,
        // and re-alerting on every replay is how an Admin learns to dismiss
        // these without reading them.
        if (firstTime) {
            alertAdmins(notification, reason);
        }

        // 202, not 200: the provider is being told we accepted the report, not
        // that downstream work finished. Anything heavier here would put the
        // provider's retry timeout on our critical path.
        return ResponseEntity.accepted().build();
    }

    private void alertAdmins(BounceNotification notification, SuppressionReason reason) {
        List<Long> admins = notifications.activeUsersInRole(ADMIN_ROLE);
        if (admins.isEmpty()) {
            log.warn("webhook: {} for {} suppressed, but there is no active Admin to alert",
                    reason, notification.email());
            return;
        }
        String title = reason == SuppressionReason.COMPLAINT
                ? "Email marked as spam — address suppressed"
                : "Email bounced — address suppressed";
        String body = notification.email()
                + " will no longer receive EduTrack mail."
                + (notification.detail() == null || notification.detail().isBlank()
                        ? "" : " Provider said: " + notification.detail());
        for (Long adminId : admins) {
            notifications.write(new NewNotification(adminId, null, EVENT_CODE, title, body, null));
        }
    }

    private static SuppressionReason reasonOf(String type) {
        if (type == null) {
            return null;
        }
        String normalised = type.trim().toUpperCase(Locale.ROOT);
        // Providers spell these variously: "Bounce", "bounce", "COMPLAINT",
        // "spamreport". Match on the substring rather than an exact set.
        if (normalised.contains("COMPLAINT") || normalised.contains("SPAM")) {
            return SuppressionReason.COMPLAINT;
        }
        if (normalised.contains("BOUNCE")) {
            return SuppressionReason.BOUNCE;
        }
        return null;
    }
}
