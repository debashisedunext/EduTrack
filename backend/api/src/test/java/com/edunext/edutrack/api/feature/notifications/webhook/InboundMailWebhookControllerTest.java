package com.edunext.edutrack.api.feature.notifications.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-039 · {@code POST /api/v1/webhooks/email/inbound}, against the contract:
 * 202 on acceptance, 401 on an unverified signature.
 */
class InboundMailWebhookControllerTest {

    private static final String URL = "/api/v1/webhooks/email/inbound";
    private static final String REPLY = """
            {"from":"Priya Nair <priya.nair@edunext.test>",
             "inReplyTo":"<ticket.347.mail.9001@edutrack.local>",
             "references":"<ticket.347@edutrack.local>",
             "text":"Fixed, please retest.\\n\\nOn Tue, EduTrack wrote:\\n> anything",
             "subject":"Re: [CRM-26-00347] Handed to you at QA"}
            """;

    private WebhookSignatureVerifier signatureVerifier;
    private InboundReplyService replies;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        signatureVerifier = mock(WebhookSignatureVerifier.class);
        replies = mock(InboundReplyService.class);
        when(replies.accept(any())).thenReturn(Optional.of(1L));
        mockMvc = MockMvcBuilders.standaloneSetup(new InboundMailWebhookController(
                signatureVerifier, replies, new ObjectMapper())).build();
    }

    private void signatureIs(boolean valid) {
        when(signatureVerifier.isValid(any(), any())).thenReturn(valid);
    }

    @Test
    @DisplayName("an unverified signature is 401 and writes nothing")
    void anUnverifiedSignatureIs401AndChangesNothing() throws Exception {
        signatureIs(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "wrong").content(REPLY))
                .andExpect(status().isUnauthorized());

        // The route is unauthenticated by necessity, so this assertion is the
        // whole security boundary: no signature, no comment.
        verify(replies, never()).accept(any());
    }

    @Test
    @DisplayName("a missing signature header is 401, not a 400 about a missing header")
    void aMissingSignatureIs401() throws Exception {
        signatureIs(false);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON).content(REPLY))
                .andExpect(status().isUnauthorized());

        verify(replies, never()).accept(any());
    }

    @Test
    @DisplayName("a signed reply is accepted and handed on with its headers intact")
    void aSignedReplyIsAccepted() throws Exception {
        signatureIs(true);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "good").content(REPLY))
                .andExpect(status().isAccepted());

        ArgumentCaptor<InboundReply> captor = ArgumentCaptor.forClass(InboundReply.class);
        verify(replies).accept(captor.capture());
        InboundReply parsed = captor.getValue();
        assertThat(parsed.from()).isEqualTo("Priya Nair <priya.nair@edunext.test>");
        assertThat(parsed.inReplyTo()).isEqualTo("<ticket.347.mail.9001@edutrack.local>");
        assertThat(parsed.references()).isEqualTo("<ticket.347@edutrack.local>");
        assertThat(parsed.text()).contains("Fixed, please retest.");
    }

    @Test
    @DisplayName("provider field names are accepted as well as ours")
    void providerAliasesAreAccepted() throws Exception {
        signatureIs(true);
        // SendGrid/Mailgun style. The contract types this body as free-form
        // because the envelope belongs to whichever provider is chosen.
        String vendorShape = """
                {"sender":"priya.nair@edunext.test",
                 "In-Reply-To":"<ticket.347@edutrack.local>",
                 "bodyPlain":"Retested, closing.",
                 "Subject":"Re: [CRM-26-00347]"}
                """;

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "good").content(vendorShape))
                .andExpect(status().isAccepted());

        ArgumentCaptor<InboundReply> captor = ArgumentCaptor.forClass(InboundReply.class);
        verify(replies).accept(captor.capture());
        assertThat(captor.getValue().from()).isEqualTo("priya.nair@edunext.test");
        assertThat(captor.getValue().text()).isEqualTo("Retested, closing.");
    }

    @Test
    @DisplayName("an unknown field does not make the provider retry forever")
    void unknownFieldsAreIgnored() throws Exception {
        signatureIs(true);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "good")
                        .content("{\"from\":\"a@b.test\",\"spamScore\":0.1,\"text\":\"hi\"}"))
                .andExpect(status().isAccepted());

        verify(replies).accept(any());
    }

    @Test
    @DisplayName("an unreadable body is 202, not 500 — a 5xx is a redelivery loop")
    void anUnreadableBodyIsAccepted() throws Exception {
        signatureIs(true);

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "good").content("[\"not an object\"]"))
                .andExpect(status().isAccepted());

        verify(replies, never()).accept(any());
    }

    @Test
    @DisplayName("a dropped reply is still 202 — the provider cannot act on the reason")
    void aDroppedReplyIsStillAccepted() throws Exception {
        signatureIs(true);
        when(replies.accept(any())).thenReturn(Optional.empty());

        mockMvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .header("X-Webhook-Signature", "good").content(REPLY))
                .andExpect(status().isAccepted());
    }
}
