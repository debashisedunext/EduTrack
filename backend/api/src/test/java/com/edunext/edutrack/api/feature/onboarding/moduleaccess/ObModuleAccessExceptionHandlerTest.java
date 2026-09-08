package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.AlreadyRevokedException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.DuplicateGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantNotFoundException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantValidationException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.LastAdminGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.NotAnOnboardingAdminException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-117 · what each refusal looks like <b>on the wire</b>.
 *
 * <p>The gap this fills: {@code ObModuleAccessServiceTest} proves which
 * exception the service throws, and nothing between them asserted that
 * {@link ObModuleAccessExceptionHandler} turns those into the status and
 * document the contract promises. The advice is bound by
 * {@code assignableTypes}, so it is registered by a configuration detail a
 * rename or a package move can silently break — and a 403 that quietly became a
 * 500 still refuses the caller, which is why nobody would notice from the
 * screen.
 *
 * <p>The two 422s are the reason this file is not just a status check. They
 * share a status and want different screens — one is "you clicked twice", the
 * other is "grant somebody else first" — so a client can only tell them apart
 * by {@code type}, and CONVENTIONS.md §3 is explicit that clients branch on
 * {@code type} and never on prose. Asserting the URI is asserting the API.
 *
 * <p>{@code standaloneSetup} with the advice attached: no Spring context, so
 * this stays a unit test that runs in milliseconds.
 */
class ObModuleAccessExceptionHandlerTest {

    private static final String LIST = "/api/v1/onboarding/module-access";
    private static final String REVOKE = "/api/v1/onboarding/module-access/5/revoke";
    private static final String GRANT_BODY =
            "{\"userId\":42,\"module\":\"ONBOARDING\",\"moduleRole\":\"OB_VIEWER\"}";

    /**
     * A staff token carrying an onboarding role. Needed because the controller
     * resolves {@code CallerIdentity} before it calls the service, and an
     * unresolvable one is a 500 — so without a principal every test below would
     * assert the wrong failure.
     */
    private static JwtAuthenticationToken staff() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("7")
                .claim("role", "ADMIN")
                .claim("modules", List.of("ONBOARDING"))
                .claim("moduleRoles", Map.of("ONBOARDING", "OB_ADMIN"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private ObModuleAccessService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ObModuleAccessService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ObModuleAccessController(service))
                .setControllerAdvice(new ObModuleAccessExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a non-Admin reading the list gets 403, not 404 and not 500")
    void notAnAdminIs403() throws Exception {
        // The status that departs from the module's usual 404, and the one a
        // later reader is most likely to "correct" back. See the controller's
        // javadoc: there is no row here whose existence a 404 would protect.
        when(service.list(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenThrow(new NotAnOnboardingAdminException());

        mockMvc.perform(get(LIST).principal(staff()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/forbidden"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("an unknown grantId is 404")
    void unknownGrantIs404() throws Exception {
        when(service.revoke(any(), anyLong())).thenThrow(new GrantNotFoundException(5L));

        mockMvc.perform(post(REVOKE).principal(staff()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/not-found"));
    }

    @Test
    @DisplayName("a duplicate live grant is 409 with its own type")
    void duplicateIs409() throws Exception {
        when(service.grant(any(), any())).thenThrow(new DuplicateGrantException("ONBOARDING"));

        mockMvc.perform(post(LIST).principal(staff()).contentType("application/json").content(GRANT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/ob-module-access-duplicate"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("revoke the existing grant first")));
    }

    @Test
    @DisplayName("an unknown module or role is 400")
    void badVocabularyIs400() throws Exception {
        when(service.grant(any(), any())).thenThrow(new GrantValidationException("module must be one of [..]"));

        mockMvc.perform(post(LIST).principal(staff()).contentType("application/json").content(GRANT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/validation-failed"));
    }

    @Test
    @DisplayName("the two 422s share a status and are told apart by type")
    void theTwo422sAreDistinguishable() throws Exception {
        // doThrow rather than when(...): re-stubbing a method already stubbed
        // to throw would invoke it here, in the arrange step, and the test
        // would fail on its own setup.
        doThrow(new AlreadyRevokedException()).when(service).revoke(any(), anyLong());
        mockMvc.perform(post(REVOKE).principal(staff()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/ob-module-access-already-revoked"));

        doThrow(new LastAdminGrantException()).when(service).revoke(any(), anyLong());
        mockMvc.perform(post(REVOKE).principal(staff()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://edutrack/errors/ob-module-access-last-admin"))
                // The message names the way out. A refusal that does not is
                // indistinguishable from a bug.
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Grant OB_ADMIN to somebody else")));
    }

    @Test
    @DisplayName("the controller registers no DELETE, PUT or PATCH on a grant")
    void revokingIsNotDeleting() {
        // Layer 2 of the discipline the append-only tables use: the verb is not
        // registered, so it cannot be called by mistake. Asserted structurally
        // rather than by probing for a 405 — an unmapped path answers 404 and
        // would pass whether or not a DELETE existed one segment away.
        //
        // A grant is revoked and replaced, never edited, so PUT and PATCH are
        // wrong here for the same reason: the row does not change except to be
        // stamped, and that is what the revoke route is.
        for (Method method : ObModuleAccessController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotationsByType(
                    org.springframework.web.bind.annotation.DeleteMapping.class))
                    .as("DELETE mapping on " + method.getName()).isEmpty();
            assertThat(method.getAnnotationsByType(
                    org.springframework.web.bind.annotation.PutMapping.class))
                    .as("PUT mapping on " + method.getName()).isEmpty();
            assertThat(method.getAnnotationsByType(
                    org.springframework.web.bind.annotation.PatchMapping.class))
                    .as("PATCH mapping on " + method.getName()).isEmpty();
        }
    }
}
