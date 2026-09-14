package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.signoff.ObSignoffCsatUnavailableException;
import com.edunext.edutrack.api.feature.onboarding.signoff.ObSignoffNotForClientException;
import com.edunext.edutrack.api.feature.onboarding.signoff.ObSignoffNotPendingException;
import com.edunext.edutrack.api.feature.onboarding.signoff.PortalSignoffDecisionService;
import com.edunext.edutrack.api.security.PrincipalType;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-122 · what CP-05 actually receives from {@code GET
 * /portal/onboarding/signoffs} — {@code PortalTicketControllerTest}'s own
 * reasoning, one controller over: a slice test cannot instantiate every
 * {@code @RestControllerAdvice} in the app, so this goes through the real
 * chain with only {@link PortalSignoffReader} mocked, exactly as that class
 * mocks only {@code PortalTicketService}. Every other {@code
 * PortalOnboardingController} dependency is the real bean — none of them
 * touches a datasource on a call this test never makes.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class PortalOnboardingSignoffsControllerTest {

    private static final String BASE = "/api/v1/portal/onboarding/signoffs";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PortalSignoffReader reader;

    @MockitoBean
    PortalSignoffDecisionService decisions;

    /** {@code PortalTicketControllerTest}'s client token, verbatim. */
    private static JwtAuthenticationToken client(Long obClientId) {
        Jwt.Builder jwt = Jwt.withTokenValue("client")
                .header("alg", "HS256")
                .subject("55")
                .claim(PrincipalType.CLAIM, "CLIENT")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (obClientId != null) {
            jwt.claim("ob_client_id", obClientId);
        } else {
            // A client whose only master is ticketing still needs a claim to
            // be admitted at all — ClientPrincipal.fromToken refuses a token
            // holding neither id.
            jwt.claim("client_id", 7);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    private static PortalOnboardingDtos.PortalSignoff pending() {
        return new PortalOnboardingDtos.PortalSignoff(
                1, ObSignoffKind.STEP, ObSignoffStatus.PENDING,
                "Payroll", "UAT sign-off",
                Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-08T10:00:00Z"),
                "spoc@acme.example",
                null, null, null, null, null,
                false);
    }

    private static PortalOnboardingDtos.PortalSignoff signed() {
        return new PortalOnboardingDtos.PortalSignoff(
                2, ObSignoffKind.GO_LIVE, ObSignoffStatus.SIGNED,
                null, null,
                Instant.parse("2026-08-01T10:00:00Z"),
                Instant.parse("2026-08-08T10:00:00Z"),
                "spoc@acme.example",
                Instant.parse("2026-08-02T09:00:00Z"), "Priya Nair", "Looks good.",
                null, null,
                false);
    }

    @Nested
    @DisplayName("the list")
    class List_ {

        @Test
        @DisplayName("is a { data } envelope carrying pending and past rows")
        void servesTheDeclaredEnvelope() throws Exception {
            when(reader.listFor(anyLong())).thenReturn(List.of(pending(), signed()));

            mvc.perform(get(BASE).with(authentication(client(9L))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].kind").value("STEP"))
                    .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                    .andExpect(jsonPath("$.data[0].productName").value("Payroll"))
                    .andExpect(jsonPath("$.data[0].stepTitle").value("UAT sign-off"))
                    .andExpect(jsonPath("$.data[0].sentToEmail").value("spoc@acme.example"))
                    .andExpect(jsonPath("$.data[0].hasCertificate").value(false))
                    .andExpect(jsonPath("$.data[1].kind").value("GO_LIVE"))
                    .andExpect(jsonPath("$.data[1].status").value("SIGNED"))
                    .andExpect(jsonPath("$.data[1].signedName").value("Priya Nair"));
        }

        /**
         * The never-visible list, asserted as absence — {@code
         * PortalTicketControllerTest}'s own pattern: a serializer cannot omit a
         * component a record declares, so a field appearing here means the
         * record grew one.
         */
        @Test
        @DisplayName("carries no token, hash, OTP state, IP or user agent")
        void omitsEverythingOnTheNeverVisibleList() throws Exception {
            when(reader.listFor(anyLong())).thenReturn(List.of(pending()));

            mvc.perform(get(BASE).with(authentication(client(9L))))
                    .andExpect(jsonPath("$.data[0].token").doesNotExist())
                    .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist())
                    .andExpect(jsonPath("$.data[0].otpHash").doesNotExist())
                    .andExpect(jsonPath("$.data[0].otpAttempts").doesNotExist())
                    .andExpect(jsonPath("$.data[0].signedIp").doesNotExist())
                    .andExpect(jsonPath("$.data[0].signedUserAgent").doesNotExist())
                    .andExpect(jsonPath("$.data[0].requestedBy").doesNotExist());
        }
    }

    /**
     * Deciding one, on the authenticated surface — no link, no OTP.
     *
     * <p>{@link PortalSignoffDecisionService} is mocked for the same reason
     * {@link PortalSignoffReader} is: it reaches Redis and a datasource, and
     * what this class tests is the controller's half — the envelope, the
     * status codes, the problem documents, and the one thing that must never
     * regress, which is that {@code obClientId} is taken from the token rather
     * than from anything the caller sent.
     */
    @Nested
    @DisplayName("deciding")
    class Deciding {

        private static PortalSignoffDecisionService.Review review(ObSignoffStatus status) {
            return new PortalSignoffDecisionService.Review(
                    1, ObSignoffKind.STEP, status,
                    "Acme", "Payroll", "UAT sign-off",
                    Instant.parse("2026-09-01T10:00:00Z"),
                    status == ObSignoffStatus.PENDING, false,
                    List.of(new PortalSignoffDecisionService.ChecklistItem(
                            31, 1, "Data migration verified", true, true)));
        }

        private static PortalSignoffDecisionService.Decision accepted() {
            return new PortalSignoffDecisionService.Decision(
                    1, ObSignoffStatus.SIGNED,
                    Instant.parse("2026-09-02T09:00:00Z"), "Priya Nair", "Looks good.",
                    null, null,
                    false, List.of("ob-step-docs-missing"), false, false, false);
        }

        @Test
        @DisplayName("the review is a { data } envelope carrying the checklist")
        void servesTheReview() throws Exception {
            when(decisions.review(anyLong(), anyLong())).thenReturn(review(ObSignoffStatus.PENDING));

            mvc.perform(get(BASE + "/1").with(authentication(client(9L))))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("ETag"))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.canDecide").value(true))
                    .andExpect(jsonPath("$.data.stepTitle").value("UAT sign-off"))
                    .andExpect(jsonPath("$.data.checklist[0].label").value("Data migration verified"))
                    .andExpect(jsonPath("$.data.checklist[0].isDone").value(true));
        }

        /**
         * The minted session is an internal handle to
         * {@code ObSignoffAcceptService}, not a credential the client is given.
         * Asserted as absence, on the list's own precedent: a serializer cannot
         * omit a component the record declares, so a field appearing here means
         * somebody widened the record.
         */
        @Test
        @DisplayName("the review carries no session token and no staff attribution")
        void reviewOmitsTheNeverVisibleList() throws Exception {
            when(decisions.review(anyLong(), anyLong())).thenReturn(review(ObSignoffStatus.PENDING));

            mvc.perform(get(BASE + "/1").with(authentication(client(9L))))
                    .andExpect(jsonPath("$.data.sessionToken").doesNotExist())
                    .andExpect(jsonPath("$.data.token").doesNotExist())
                    .andExpect(jsonPath("$.data.requestedBy").doesNotExist())
                    .andExpect(jsonPath("$.data.sentToContact").doesNotExist());
        }

        @Test
        @DisplayName("a sign-off on another client's onboarding is 404, never 403")
        void foreignSignoffIsNotFound() throws Exception {
            when(decisions.review(anyLong(), anyLong()))
                    .thenThrow(new ObSignoffNotForClientException());

            mvc.perform(get(BASE + "/1").with(authentication(client(9L))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        /**
         * The assertion this whole surface rests on: the client is read off the
         * token, and the only thing the path contributes is the sign-off id. A
         * caller cannot widen what they are deciding, because there is no
         * parameter carrying it.
         */
        @Test
        @DisplayName("scopes the decision by the token's own obClientId, not by anything sent")
        void scopesByTheToken() throws Exception {
            when(decisions.accept(anyLong(), anyLong(), any(), any(), any())).thenReturn(accepted());

            mvc.perform(post(BASE + "/1/accept")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"acceptedName\":\"Priya Nair\",\"obClientId\":4242}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Long> signoffId = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> obClientId = ArgumentCaptor.forClass(Long.class);
            verify(decisions).accept(signoffId.capture(), obClientId.capture(),
                    eq("Priya Nair"), isNull(), any());

            assertThat(signoffId.getValue()).isEqualTo(1L);
            assertThat(obClientId.getValue()).isEqualTo(9L);
        }

        /**
         * {@code stepCompleted: false} with a non-empty {@code gateFailures} is
         * a <b>successful</b> acceptance — the contract's own shape, carried
         * across from {@code acceptObSignoff}. A 200 here rather than a 4xx is
         * the whole point: the client accepted, and what is unfinished is ours.
         */
        @Test
        @DisplayName("an acceptance our own gate could not act on is still a 200")
        void acceptanceStandsWhenTheGateRefuses() throws Exception {
            when(decisions.accept(anyLong(), anyLong(), any(), any(), any())).thenReturn(accepted());

            mvc.perform(post(BASE + "/1/accept")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"acceptedName\":\"Priya Nair\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SIGNED"))
                    .andExpect(jsonPath("$.data.stepCompleted").value(false))
                    .andExpect(jsonPath("$.data.gateFailures[0]").value("ob-step-docs-missing"));
        }

        @Test
        @DisplayName("an acceptance with no typed name is refused before anything is written")
        void blankNameIsRejected() throws Exception {
            mvc.perform(post(BASE + "/1/accept")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"acceptedName\":\"   \"}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(decisions);
        }

        @Test
        @DisplayName("an objection with no reason is refused")
        void blankObjectionIsRejected() throws Exception {
            mvc.perform(post(BASE + "/1/object")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"\"}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(decisions);
        }

        @Test
        @DisplayName("an objection is recorded and hands back the objected row")
        void objectionIsRecorded() throws Exception {
            when(decisions.object(anyLong(), anyLong(), any())).thenReturn(
                    new PortalSignoffDecisionService.Decision(
                            1, ObSignoffStatus.OBJECTED,
                            null, null, null,
                            Instant.parse("2026-09-02T09:00:00Z"), "The UAT data is stale.",
                            false, List.of(), false, false, false));

            mvc.perform(post(BASE + "/1/object")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"The UAT data is stale.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OBJECTED"))
                    .andExpect(jsonPath("$.data.objectionNote").value("The UAT data is stale."))
                    .andExpect(jsonPath("$.data.stepCompleted").value(false));

            verify(decisions).object(1L, 9L, "The UAT data is stale.");
        }

        /**
         * <p>The status is asserted on {@code signoffStatus} rather than
         * {@code status}, and that is the point of the assertion as much as the
         * value is: RFC 9457 already defines {@code status} as the HTTP code, so
         * a handler setting a property of that name emits the member twice. An
         * earlier version of this handler did, and this is what pins it.
         */
        @Test
        @DisplayName("a decision on a settled sign-off is 422 and says which way it went")
        void settledSignoffIsUnprocessable() throws Exception {
            when(decisions.accept(anyLong(), anyLong(), any(), any(), any()))
                    .thenThrow(new ObSignoffNotPendingException(ObSignoffStatus.CANCELLED));

            mvc.perform(post(BASE + "/1/accept")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"acceptedName\":\"Priya Nair\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("https://edutrack/errors/portal-signoff-not-pending"))
                    .andExpect(jsonPath("$.signoffStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.status").value(422));
        }

        @Test
        @DisplayName("the go-live survey answers 204 and hands back nothing")
        void csatIsNoContent() throws Exception {
            mvc.perform(post(BASE + "/1/csat")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"score\":5,\"comment\":\"Smooth.\"}"))
                    .andExpect(status().isNoContent());

            verify(decisions).submitCsat(1L, 9L, 5, "Smooth.");
        }

        @Test
        @DisplayName("a survey score outside one to five is refused")
        void csatScoreIsBounded() throws Exception {
            mvc.perform(post(BASE + "/1/csat")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"score\":9}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(decisions);
        }

        @Test
        @DisplayName("a survey on a sign-off that is not offering one is 422, not 500")
        void csatUnavailableIsUnprocessable() throws Exception {
            doThrow(new ObSignoffCsatUnavailableException("This client has already been surveyed", null))
                    .when(decisions).submitCsat(anyLong(), anyLong(), anyInt(), any());

            mvc.perform(post(BASE + "/1/csat")
                            .with(authentication(client(9L)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"score\":5}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("https://edutrack/errors/portal-csat-unavailable"));
        }
    }

    /**
     * A client whose account carries no onboarding master — plan §2.3's
     * disjoint masters, and the row-scoping rule applies here exactly as it
     * does to a ticket: 404, never 403, and never an empty {@code 200}
     * either, which would say "you have an onboarding account and it has no
     * sign-offs" about a caller who has no such account at all.
     */
    @Test
    @DisplayName("a caller with no onboarding client gets 404, not an empty list")
    void noOnboardingClientIsNotFound() throws Exception {
        mvc.perform(get(BASE).with(authentication(client(null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
