package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-111 · that the item route's refusals reach the wire as problem documents.
 *
 * <h2>The bug this exists for</h2>
 *
 * <p>{@link ObJourneyStepLifecycleExceptionHandler} is bound by {@code
 * assignableTypes}, and for as long as that list named only {@link
 * ObJourneyStepLifecycleController}, <b>every refusal from {@code PATCH
 * /journey-step-items/{itemId}} answered 500</b> — a closed service, an unknown
 * item, a caller who is not the owner, all of them. {@link
 * ObJourneyStepItemController}'s own javadoc asserted the opposite, that the
 * advice was "package-scoped and therefore already covers this class", which is
 * what {@code assignableTypes} is not.
 *
 * <p>It survived because a 500 still refuses the caller. The screen shows an
 * error either way, the row does not change either way, and nothing short of
 * reading the server log tells the two apart — which is exactly how it was
 * found, on 16 Sep 2026, by a reader asking why a checklist would not save.
 *
 * <p>So this asserts the wiring rather than the handler methods: the same
 * reasoning {@code ObModuleAccessExceptionHandlerTest} gives one package over,
 * with the difference that there the advice covered its controller and here it
 * did not. A route added to this package and left out of that list fails here.
 *
 * <p>{@code standaloneSetup} honours {@code assignableTypes} — {@code
 * ExceptionHandlerExceptionResolver} asks {@code
 * ControllerAdviceBean#isApplicableToBeanType} for the handler's own type — so
 * this catches the omission without a Spring context.
 */
class ObJourneyStepItemExceptionHandlerTest {

    private static final String ITEM = "/api/v1/onboarding/journey-step-items/112";
    private static final String BODY = "{\"answer\":false,\"remark\":null}";

    /**
     * A staff token. The controller resolves {@code CallerIdentity} before it
     * reaches the service, and an unresolvable one is its own 500 — so without
     * a principal every assertion below would be about the wrong failure.
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

    private ObJourneyStepLifecycleService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ObJourneyStepLifecycleService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ObJourneyStepItemController(service))
                .setControllerAdvice(new ObJourneyStepLifecycleExceptionHandler())
                .build();
    }

    /**
     * The one a reader actually hits: a closed service's checklist is the
     * record of how it closed, so answering an item on it is refused. 422 with
     * {@code ob-step-terminal} is what the route's own OpenAPI description
     * promises, and what lets the screen say <em>this service is already
     * closed</em> rather than <em>something went wrong</em>.
     */
    @Test
    @DisplayName("answering an item on a closed service is 422 ob-step-terminal, not 500")
    void closedServiceIs422() throws Exception {
        when(service.answerItem(anyLong(), anyLong(), any(), any()))
                .thenThrow(new StepAlreadyTerminalException(83L, ObJourneyStepStatus.DONE));

        mockMvc.perform(patch(ITEM).principal(staff()).contentType("application/json").content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/ob-step-terminal"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("an unknown item is 404, not 500")
    void unknownItemIs404() throws Exception {
        when(service.answerItem(anyLong(), anyLong(), any(), any()))
                .thenThrow(new JourneyStepItemNotFoundException(112L));

        mockMvc.perform(patch(ITEM).principal(staff()).contentType("application/json").content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * An item is only ever as writable as the service it belongs to, and the
     * refusal is the step's own — 422 {@code step-owner-required}, the same
     * document the sibling controller's transitions answer with.
     */
    @Test
    @DisplayName("a caller who is not the step's owner is 422 step-owner-required, not 500")
    void notTheOwnerIs422() throws Exception {
        when(service.answerItem(anyLong(), anyLong(), any(), any()))
                .thenThrow(new NotStepOwnerException(83L, 9L, null));

        mockMvc.perform(patch(ITEM).principal(staff()).contentType("application/json").content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://edutrack/errors/step-owner-required"));
    }
}
