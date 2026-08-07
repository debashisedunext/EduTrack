package com.edunext.edutrack.api.feature.notifications.webhook;

import com.edunext.edutrack.domain.mail.EmailSuppressions;
import com.edunext.edutrack.domain.mail.EmailSuppressions.SuppressionReason;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-034 · {@code POST /api/v1/webhooks/email/bounce}, against the contract:
 * 202 on acceptance, 401 on an unverified signature.
 */
class BounceWebhookControllerTest {

    private static final String URL = "/api/v1/webhooks/email/bounce";
    private static final String BOUNCE =
            "{\"email\":\"ravi@example.com\",\"type\":\"Bounce\","
                    + "\"detail\":\"550 mailbox unavailable\",\"providerMessageId\":\"msg-1\"}";

    private WebhookSignatureVerifier signatureVerifier;
    private EmailSuppressions suppressions;
    private NotificationWriter notifications;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        signatureVerifier = mock(WebhookSignatureVerifier.class);
        suppressions = mock(EmailSuppressions.class);
        notifications = mock(NotificationWriter.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new BounceWebhookController(
                signatureVerifier, suppressions, notifications, new ObjectMapper())).build();
    }

    private void signatureIs(boolean valid) {
        when(signatureVerifier.isValid(any(), any())).thenReturn(valid);
    }

    @Test
    void anUnverifiedSignatureIs401AndChangesNothing() throws Exception {
        signatureIs(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "forged")
                        .content(BOUNCE))
                .andExpect(status().isUnauthorized());

        verify(suppressions, never()).suppress(anyString(), any(), any(), any());
        verify(notifications, never()).write(any());
    }

    @Test
    void aMissingSignatureIs401RatherThanA400() throws Exception {
        signatureIs(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(BOUNCE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aVerifiedBounceSuppressesTheAddressAndAlertsAdmins() throws Exception {
        signatureIs(true);
        when(suppressions.suppress(anyString(), any(), any(), any())).thenReturn(true);
        when(notifications.activeUsersInRole("ADMIN")).thenReturn(List.of(7L, 8L));

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid")
                        .content(BOUNCE))
                .andExpect(status().isAccepted());

        verify(suppressions).suppress(eq("ravi@example.com"), eq(SuppressionReason.BOUNCE),
                eq("550 mailbox unavailable"), eq("msg-1"));

        ArgumentCaptor<NewNotification> raised = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications, org.mockito.Mockito.times(2)).write(raised.capture());
        assertThat(raised.getAllValues()).extracting(NewNotification::userId)
                .containsExactly(7L, 8L);
        assertThat(raised.getAllValues().getFirst().body()).contains("ravi@example.com");
    }

    @Test
    void aComplaintIsSuppressedAsHardAsABounce() throws Exception {
        signatureIs(true);
        when(suppressions.suppress(anyString(), any(), any(), any())).thenReturn(true);
        when(notifications.activeUsersInRole("ADMIN")).thenReturn(List.of(7L));

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid")
                        .content("{\"email\":\"ravi@example.com\",\"type\":\"SpamReport\"}"))
                .andExpect(status().isAccepted());

        verify(suppressions).suppress(eq("ravi@example.com"), eq(SuppressionReason.COMPLAINT),
                any(), any());
    }

    /**
     * Providers replay webhooks. Re-alerting on every replay is how an Admin
     * learns to dismiss these without reading them.
     */
    @Test
    void aReplayedBounceIsAcceptedButDoesNotAlertAgain() throws Exception {
        signatureIs(true);
        when(suppressions.suppress(anyString(), any(), any(), any())).thenReturn(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid")
                        .content(BOUNCE))
                .andExpect(status().isAccepted());

        verify(notifications, never()).write(any());
    }

    @Test
    void anUnrecognisedEventTypeIsRejectedRatherThanGuessed() throws Exception {
        signatureIs(true);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid")
                        .content("{\"email\":\"ravi@example.com\",\"type\":\"Delivery\"}"))
                .andExpect(status().isBadRequest());

        verify(suppressions, never()).suppress(anyString(), any(), any(), any());
    }

    @Test
    void aPayloadWithNoAddressIsRejected() throws Exception {
        signatureIs(true);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid")
                        .content("{\"type\":\"Bounce\"}"))
                .andExpect(status().isBadRequest());

        verify(suppressions, never()).suppress(anyString(), any(), any(), any());
    }

    /** Providers add fields; 400-ing on them turns into a retry storm. */
    @Test
    void unknownFieldsAreIgnored() throws Exception {
        signatureIs(true);
        when(suppressions.suppress(anyString(), any(), any(), any())).thenReturn(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid")
                        .content("{\"recipient\":\"ravi@example.com\",\"eventType\":\"bounce\","
                                + "\"somethingNew\":{\"nested\":true}}"))
                .andExpect(status().isAccepted());

        verify(suppressions).suppress(eq("ravi@example.com"), eq(SuppressionReason.BOUNCE),
                any(), any());
    }
}
