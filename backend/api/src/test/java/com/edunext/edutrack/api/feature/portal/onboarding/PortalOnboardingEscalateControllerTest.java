package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.escalations.ObClientEscalationService;
import com.edunext.edutrack.api.security.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-126 · {@code POST /portal/onboarding/steps/{stepId}/escalate} — CP-03's
 * Escalate control, on {@code PortalOnboardingSignoffsControllerTest}'s exact
 * shape one route over: the real controller and its real {@code
 * @RestControllerAdvice}, with only the collaborators that would otherwise
 * touch a datasource mocked out. {@link ObClientEscalationService}'s own
 * business rules (idempotent raise, notifications) are {@code
 * ObClientEscalationServiceTest}'s job; this test is about the route's own
 * wiring — 404 for a foreign or unknown step, 422 for one not running, and
 * what the controller hands the service and hands back.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class PortalOnboardingEscalateControllerTest {

    private static final long STEP_ID = 42L;
    private static final long OB_CLIENT_ID = 9L;
    private static final long CONTACT_ID = 5L;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PortalEscalationStepReader escalationSteps;

    @MockitoBean
    ObClientEscalationService clientEscalations;

    @MockitoBean
    PortalPrimaryContactReader clients;

    private static String url(long stepId) {
        return "/api/v1/portal/onboarding/steps/" + stepId + "/escalate";
    }

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
            jwt.claim("client_id", 7);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    private static PortalEscalationStepReader.StepContext running() {
        return new PortalEscalationStepReader.StepContext(
                STEP_ID, 2L, "IN_PROGRESS", 21L, "Data migration", "Payroll", "Acme");
    }

    private static PortalEscalationStepReader.StepContext blocked() {
        return new PortalEscalationStepReader.StepContext(
                STEP_ID, 2L, "BLOCKED", 21L, "Data migration", "Payroll", "Acme");
    }

    @Nested
    @DisplayName("resolving the step")
    class StepResolution {

        @Test
        @DisplayName("a step on another client, or one that does not exist, is 404")
        void unknownOrForeignStepIsNotFound() throws Exception {
            when(escalationSteps.stepContextFor(OB_CLIENT_ID, STEP_ID)).thenReturn(Optional.empty());

            mvc.perform(post(url(STEP_ID)).with(authentication(client(OB_CLIENT_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"It's down\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a caller with no onboarding client at all is 404, not a 401 or an empty list")
        void noOnboardingClientIsNotFound() throws Exception {
            mvc.perform(post(url(STEP_ID)).with(authentication(client(null)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"It's down\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("a step that is not IN_PROGRESS is 422, even if the caller bypasses the disabled button")
        void notRunningIsUnprocessable() throws Exception {
            when(escalationSteps.stepContextFor(OB_CLIENT_ID, STEP_ID)).thenReturn(Optional.of(blocked()));

            mvc.perform(post(url(STEP_ID)).with(authentication(client(OB_CLIENT_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"It's down\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.title").value("This service is not currently running"));
        }
    }

    @Nested
    @DisplayName("the comment")
    class CommentValidation {

        @Test
        @DisplayName("a blank comment is refused before anything is looked up")
        void blankCommentIsRejected() throws Exception {
            mvc.perform(post(url(STEP_ID)).with(authentication(client(OB_CLIENT_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("raising")
    class Raising {

        @Test
        @DisplayName("a fresh raise is 201, attributed to the client's active primary contact and the step's own owner")
        void freshRaiseIsCreated() throws Exception {
            when(escalationSteps.stepContextFor(OB_CLIENT_ID, STEP_ID)).thenReturn(Optional.of(running()));
            when(clients.activePrimaryContactId(OB_CLIENT_ID)).thenReturn(Optional.of(CONTACT_ID));
            when(clientEscalations.raise(any())).thenReturn(new ObClientEscalationService.RaiseResult(
                    100L, "It's down", Instant.parse("2026-09-09T10:00:00Z"), true));

            mvc.perform(post(url(STEP_ID)).with(authentication(client(OB_CLIENT_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"It's down\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(100))
                    .andExpect(jsonPath("$.data.comment").value("It's down"))
                    .andExpect(jsonPath("$.data.isNew").value(true));

            var captor = org.mockito.ArgumentCaptor.forClass(ObClientEscalationService.RaiseCommand.class);
            verify(clientEscalations).raise(captor.capture());
            var cmd = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(cmd.obClientId()).isEqualTo(OB_CLIENT_ID);
            org.assertj.core.api.Assertions.assertThat(cmd.stepId()).isEqualTo(STEP_ID);
            org.assertj.core.api.Assertions.assertThat(cmd.raisedByContactId()).isEqualTo(CONTACT_ID);
            org.assertj.core.api.Assertions.assertThat(cmd.ownerUserId()).isEqualTo(21L);
            org.assertj.core.api.Assertions.assertThat(cmd.comment()).isEqualTo("It's down");
        }

        @Test
        @DisplayName("finding an already-open escalation on this step is 200, not 201")
        void alreadyOpenIsOk() throws Exception {
            when(escalationSteps.stepContextFor(OB_CLIENT_ID, STEP_ID)).thenReturn(Optional.of(running()));
            when(clients.activePrimaryContactId(OB_CLIENT_ID)).thenReturn(Optional.of(CONTACT_ID));
            when(clientEscalations.raise(any())).thenReturn(new ObClientEscalationService.RaiseResult(
                    55L, "Already open", Instant.parse("2026-09-09T09:00:00Z"), false));

            mvc.perform(post(url(STEP_ID)).with(authentication(client(OB_CLIENT_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"Second click\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(55))
                    .andExpect(jsonPath("$.data.isNew").value(false));
        }

        @Test
        @DisplayName("a client with no active primary contact cannot escalate")
        void noPrimaryContactIsUnprocessable() throws Exception {
            when(escalationSteps.stepContextFor(OB_CLIENT_ID, STEP_ID)).thenReturn(Optional.of(running()));
            when(clients.activePrimaryContactId(OB_CLIENT_ID)).thenReturn(Optional.empty());

            mvc.perform(post(url(STEP_ID)).with(authentication(client(OB_CLIENT_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"comment\":\"It's down\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
    }
}
