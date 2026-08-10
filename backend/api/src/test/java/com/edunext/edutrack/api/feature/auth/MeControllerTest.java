package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-026 · the HTTP contract of {@code PATCH /api/v1/me/password}.
 *
 * <p>{@code addFilters = false} for the reason {@code AuthControllerTest} gives:
 * the real chain is A-032 and does not exist, so leaving Spring Security's
 * default in place would test Boot's lock-everything behaviour rather than this
 * endpoint.
 *
 * <p>{@link AuthExceptionHandler} is imported explicitly. A {@code @WebMvcTest}
 * picks up {@code @RestControllerAdvice} beans, but this one takes
 * {@link RefreshTokenIssuer} as a constructor argument — so it needs mocking, and
 * without it every refusal below would arrive as a bare 500 and the {@code type}
 * URIs would go unasserted.
 */
@WebMvcTest(controllers = MeController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeControllerTest {

    private static final String BEARER = "Bearer header.payload.signature";

    private static final String VALID_BODY = """
            {"currentPassword":"Temp-Password-1!","newPassword":"Chosen-By-The-User-9!"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PasswordChangeService passwordChange;

    @MockitoBean
    RefreshTokenIssuer refreshTokens;

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful change returns 204 with no body")
    void returnsNoContent() throws Exception {
        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("the Authorization header and both passwords reach the service")
    void forwardsTheHeaderAndTheBody() throws Exception {
        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        verify(passwordChange).change(BEARER,
                new ChangePasswordRequest("Temp-Password-1!", "Chosen-By-The-User-9!"));
    }

    // ── validation, which is the schema ─────────────────────────────────────

    /**
     * The bounds come from the contract's {@code Password} schema, and per
     * deviation D-4 the annotation on {@link ChangePasswordRequest} <i>is</i> that
     * schema — so a rejection here is the same rule the generated Zod client
     * applies in the browser.
     */
    @Test
    @DisplayName("a new password under 8 characters is rejected before any work")
    void rejectsAShortNewPassword() throws Exception {
        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Temp-Password-1!","newPassword":"short1!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verify(passwordChange, never()).change(any(), any());
    }

    /**
     * Argon2id hashes whatever it is given, so an unbounded field is a CPU and
     * memory amplifier — one request costs the server far more than the client.
     */
    @Test
    @DisplayName("a new password over 128 characters is rejected")
    void rejectsAnEnormousNewPassword() throws Exception {
        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Temp-Password-1!","newPassword":"%s"}
                                """.formatted("A1!a".repeat(40))))
                .andExpect(status().isBadRequest());

        verify(passwordChange, never()).change(any(), any());
    }

    @Test
    @DisplayName("a missing currentPassword is rejected")
    void rejectsAMissingCurrentPassword() throws Exception {
        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"Chosen-By-The-User-9!"}
                                """))
                .andExpect(status().isBadRequest());

        verify(passwordChange, never()).change(any(), any());
    }

    /**
     * No length rule on the way in. Applying today's bounds to a password that
     * already exists would tell users whose password predates a policy change
     * that their real password is invalid.
     */
    @Test
    @DisplayName("a short current password is accepted — policy applies to the replacement only")
    void acceptsAShortCurrentPassword() throws Exception {
        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old","newPassword":"Chosen-By-The-User-9!"}
                                """))
                .andExpect(status().isNoContent());
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no access token is a 401 with the stable type URI")
    void missingTokenIs401() throws Exception {
        doThrow(new InvalidAccessTokenException()).when(passwordChange).change(any(), any());

        mvc.perform(patch("/api/v1/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-access-token"))
                .andExpect(jsonPath("$.status").value(401));
    }

    /**
     * The same {@code type} as a refused login, because it is the same class of
     * failure and a second URI meaning the same thing is one more branch for S-03
     * to get wrong. The prose differs — a form with one password field must not
     * suggest the username might be at fault.
     */
    @Test
    @DisplayName("a wrong current password is 401 invalid-credentials, worded for this form")
    void wrongCurrentPasswordIs401() throws Exception {
        doThrow(new InvalidCurrentPasswordException()).when(passwordChange).change(any(), any());

        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-credentials"))
                .andExpect(jsonPath("$.detail").value("The current password is incorrect."));
    }

    /**
     * 400, not 401 — the caller authenticated fine and knew their own password;
     * the request is what does not make sense.
     */
    @Test
    @DisplayName("resubmitting the current password is 400 with its own type URI")
    void unchangedPasswordIs400() throws Exception {
        doThrow(new PasswordUnchangedException()).when(passwordChange).change(any(), any());

        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/password-unchanged"))
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * 403 rather than 401 — {@link PasswordChangeRequiredException} explains why a
     * 401 would make the frontend's interceptor loop through a login that keeps
     * succeeding.
     */
    @Test
    @DisplayName("the gate's refusal is a 403 with its own type URI")
    void passwordChangeRequiredIs403() throws Exception {
        doThrow(new PasswordChangeRequiredException()).when(passwordChange).change(any(), any());

        mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/password-change-required"))
                .andExpect(jsonPath("$.status").value(403));
    }

    /**
     * Passwords are the one thing that must never be echoed. A validation error
     * that quotes the rejected value writes it into every log and error tracker
     * the response passes through.
     */
    @Test
    @DisplayName("no response ever echoes either password")
    void neverEchoesAPassword() throws Exception {
        String body = mvc.perform(patch("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Temp-Password-1!","newPassword":"tiny"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("Temp-Password-1!")
                .doesNotContain("tiny");
    }
}
