package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-020 · the HTTP contract of {@code POST /api/v1/auth/login}.
 *
 * <p>{@code addFilters = false} removes Spring Security's chain from the slice.
 * The real chain is A-032 and does not exist yet — today
 * {@code ScaffoldSecurityConfig} permits everything, and it is not loaded by a
 * {@code @WebMvcTest}. Leaving the filters on would test Boot's lock-everything
 * default, which is neither what runs in production nor what this class is
 * about.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthenticationService authentication;

    private static final String VALID_BODY = """
            {"username":"asha.rao","password":"Correct-Horse-1!"}
            """;

    @Test
    @DisplayName("a valid login returns the session inside the { data } envelope")
    void returnsSessionEnvelope() throws Exception {
        when(authentication.authenticate("asha.rao", "Correct-Horse-1!"))
                .thenReturn(new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                        "DEVELOPER", "Asia/Kolkata", false,
                        List.of("ticket.read"), List.of(11L), List.of()));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(7))
                .andExpect(jsonPath("$.data.user.displayName").value("Asha Rao"))
                .andExpect(jsonPath("$.data.user.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.user.permissions[0]").value("ticket.read"))
                .andExpect(jsonPath("$.data.user.projectIds[0]").value(11))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("no token field is present until A-022 issues one")
    void omitsTheTokenFieldEntirely() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenReturn(new AuthenticatedUser(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                        "DEVELOPER", "Asia/Kolkata", false, List.of(), List.of(), List.of()));

        // Absent, not null. A client that sees `"accessToken": null` has to
        // special-case it; a client that sees no key at all fails at the point
        // it reads the field, which is where the missing task is visible.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.expiresIn").doesNotExist());
    }

    @Test
    @DisplayName("a refused login is a problem+json with a stable type URI")
    void refusalIsRfc9457() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // CONVENTIONS.md §3: the frontend branches on `type`, so this URI
                // is the part that must not drift. `title` and `detail` may be
                // reworded freely and are deliberately not asserted here.
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/invalid-credentials"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("the refusal body names neither the field that failed nor the user")
    void refusalLeaksNothing() throws Exception {
        when(authentication.authenticate(anyString(), anyString()))
                .thenThrow(new InvalidCredentialsException());

        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("a refusal must not echo the username, the password, or which of them was wrong")
                .doesNotContain("asha.rao")
                .doesNotContain("Correct-Horse-1!")
                .doesNotContainIgnoringCase("no such user")
                .doesNotContainIgnoringCase("not found")
                .doesNotContainIgnoringCase("disabled")
                .doesNotContainIgnoringCase("inactive");
    }

    // ── A-021 · account lockout ──────────────────────────────────────────────

    @Test
    @DisplayName("a locked account gets 423 with the lockedUntil timestamp")
    void lockedAccountGets423() throws Exception {
        Instant lockedUntil = Instant.parse("2026-08-07T16:05:00Z");
        when(authentication.authenticate(anyString(), anyString()))
                .thenThrow(new AccountLockedException(lockedUntil));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/account-locked"))
                .andExpect(jsonPath("$.lockedUntil").value(lockedUntil.toString()));
    }

    @Test
    @DisplayName("a blank username is rejected before any authentication work")
    void rejectsBlankUsername() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"  ","password":"Correct-Horse-1!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("a missing password is rejected")
    void rejectsMissingPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"asha.rao"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
