package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.security.PrincipalType;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
