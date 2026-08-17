package com.edunext.edutrack.api.security.permission;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-033 · the rule that a route without an authorisation decision does not
 * build.
 *
 * <h2>Why coverage is the assertion, and not a list of expectations</h2>
 *
 * <p>Every other check in this codebase asks "is this route's rule correct?".
 * That question can only be asked about a route somebody remembered to think
 * about, which is precisely the route that is never the problem. The failure
 * this class exists for is the route nobody thought about — added on a Tuesday
 * under {@code /api/**}, inheriting "any authenticated caller" from the filter
 * chain, and looking exactly like every protected route around it. CLAUDE.md
 * makes a permission-matrix entry part of the Definition of Done for all four
 * streams; this is what turns that from an intention into a build failure, and
 * it is the foundation A-036's role × route matrix is enumerated on top of.
 *
 * <p>{@code @EnableMethodSecurity} has a matching silent failure of its own:
 * without it Spring registers no advisor and every {@code @PreAuthorize} in the
 * application is a comment, with nothing logged and nothing to notice. So this
 * class does not take the annotations' word for it — {@link
 * #methodSecurityIsActuallyEnforced()} drives a real request through the real
 * chain and requires a real 403.
 *
 * <p>No infrastructure: the context is built the way {@code ApplicationSmokeTest}
 * builds it, so this runs in surefire on every build rather than only where
 * Docker is installed. A denial happens before the handler body, so the refused
 * requests below never reach a service or a datasource.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class RouteAuthorizationTest {

    private static final Pattern AUTHORITY_LITERAL =
            Pattern.compile("hasAnyAuthority\\(([^)]*)\\)|hasAuthority\\(\\s*'([^']*)'\\s*\\)");

    private static final Pattern ROLE_LITERAL =
            Pattern.compile("hasAnyRole\\(([^)]*)\\)|hasRole\\(\\s*'([^']*)'\\s*\\)");

    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    /**
     * The routes allowed to answer an unauthenticated caller, and the whole
     * list of them.
     *
     * <p>Written out rather than derived, because the derivation would be
     * "whatever is currently marked {@code permitAll()}", which cannot fail.
     * A new public route has to be added here by hand, in a diff, where a
     * reviewer sees it — the six {@code security: []} operations
     * {@code SecurityConfig} enumerates, and no seventh by accident.
     */
    private static final Set<String> EXPECTED_PUBLIC_ROUTES = Set.of(
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/forgot-password",
            "POST /api/v1/auth/reset-password",
            "POST /api/v1/webhooks/email/bounce",
            "POST /api/v1/webhooks/email/inbound");

    /**
     * By name: actuator contributes a second
     * {@code RequestMappingHandlerMapping} ({@code controllerEndpointHandlerMapping}),
     * and by type this is ambiguous. The MVC one is what serves {@code /api/**}.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @Autowired
    MockMvc mvc;

    @Test
    void everyRouteDeclaresAnAuthorisationDecision() {
        Set<String> undeclared = new TreeSet<>();

        applicationHandlers().forEach((info, handler) -> {
            if (preAuthorizeOn(handler) == null) {
                undeclared.add(describe(info, handler));
            }
        });

        assertThat(undeclared)
                .as("""
                        Every request-mapped handler must carry @PreAuthorize, on the method or \
                        on its controller. Without one the route inherits "any authenticated \
                        caller" from SecurityConfig and reads, in the source, exactly like a \
                        route that was reasoned about.

                        If the answer really is "anyone signed in", say so: \
                        @PreAuthorize("isAuthenticated()"). If it is public, \
                        @PreAuthorize("permitAll()") and a line in EXPECTED_PUBLIC_ROUTES. \
                        Otherwise name the capability from blueprint §2: \
                        @PreAuthorize("hasAuthority('master.write')").""")
                .isEmpty();
    }

    @Test
    void theHandlerMappingIsNotEmpty() {
        // Guards the way this whole class fails open. Every assertion here walks
        // getHandlerMethods(), so a context that built without controllers — a
        // component-scan change, a bean that failed quietly — would make all of
        // them pass by having nothing to check.
        assertThat(applicationHandlers())
                .as("no application handlers found; the coverage assertions above would be vacuous")
                .hasSizeGreaterThan(30);
    }

    @Test
    void everyAuthorityLiteralNamesARealPermission() {
        Set<String> unknown = new TreeSet<>();

        applicationHandlers().forEach((info, handler) -> {
            PreAuthorize annotation = preAuthorizeOn(handler);
            if (annotation == null) {
                return;
            }
            for (String code : literals(AUTHORITY_LITERAL, annotation.value())) {
                if (!Permissions.ALL.contains(code)) {
                    unknown.add(code + "  —  " + describe(info, handler));
                }
            }
        });

        assertThat(unknown)
                .as("""
                        A hasAuthority literal that is not a code in the permissions table can \
                        never be satisfied by any token, so the route denies all six roles \
                        for ever — and reads, in review and in an Admin's smoke test, like a \
                        working check. This is the compile-time safety the annotations gave up \
                        by using string literals instead of Permissions constants; see that \
                        class's javadoc for the trade.""")
                .isEmpty();
    }

    @Test
    void everyRoleLiteralNamesARealRole() {
        Set<String> unknown = new TreeSet<>();

        applicationHandlers().forEach((info, handler) -> {
            PreAuthorize annotation = preAuthorizeOn(handler);
            if (annotation == null) {
                return;
            }
            for (String role : literals(ROLE_LITERAL, annotation.value())) {
                if (!RolePermissions.ROLE_CODES.contains(role)) {
                    unknown.add(role + "  —  " + describe(info, handler));
                }
            }
        });

        // The specific accident this catches is SUPPORT_DESK, the pre-correction
        // code that V20260807_1030 renamed and whose header warned that "A-033's
        // @PreAuthorize role checks would silently deny".
        assertThat(unknown)
                .as("hasRole literals must be one of the six §2 role codes, post-V20260807_1030")
                .isEmpty();
    }

    @Test
    void onlyTheContractsPublicOperationsArePublic() {
        Set<String> actuallyPublic = new TreeSet<>();

        applicationHandlers().forEach((info, handler) -> {
            PreAuthorize annotation = preAuthorizeOn(handler);
            if (annotation != null && annotation.value().contains("permitAll")) {
                actuallyPublic.add(describe(info, handler));
            }
        });

        assertThat(actuallyPublic)
                .as("""
                        The set of routes reachable without authentication, which should change \
                        only deliberately. A new entry here is a new unauthenticated surface: \
                        confirm the contract marks the operation `security: []`, add it to \
                        SecurityConfig's path list too, and add it here.""")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC_ROUTES);
    }

    // ------------------------------------------------------------------
    // The annotations are not trusted to be enforced merely because they exist
    // ------------------------------------------------------------------

    @Test
    void methodSecurityIsActuallyEnforced() throws Exception {
        // A Developer holds ticket.* and reports.view, and no master.write or
        // resource.manage. Each of these was a route they could reach before
        // this task: a Developer could delete an org holiday, and every SLA
        // figure computed afterwards would move.
        var developer = principal(RolePermissions.DEVELOPER);

        mvc.perform(delete("/api/v1/masters/holidays/1").with(authentication(developer)))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/masters/leaves/1").with(authentication(developer)))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/users/1/status").with(authentication(developer))
                        .contentType("application/json").content("{\"isActive\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRoleThatHoldsThePermissionIsNotRefused() {
        // The other direction, and it needs asserting separately: a chain that
        // refused everybody would satisfy the test above completely, and a
        // typo'd authority literal is exactly the mistake that produces one.
        //
        // Not an assertion of success — there is no database here, so the
        // handler body will fail. The claim is narrower and is the one that
        // matters: authorisation let it through.
        //
        // ---------------------------------------------------------------
        // Carried on a Stream C branch (C-033) because it failed that
        // branch's CI run, and flagged for Shivendra rather than done
        // quietly — CLAUDE.md, code ownership. It is NOT a C-033 regression:
        // `docs/platform/ci-is-the-authority-again` failed identically three
        // minutes earlier with none of C-033 in it (2273 tests, same single
        // error), and no test C-033 added failed.
        //
        // WHAT WAS WRONG, because the original reads as though it should work.
        //
        // It asserted on a *status*, which assumes the request completes and
        // produces one. `CalendarService.deleteHoliday` is @Transactional
        // (B-023), so with no datasource in this context Spring throws
        // CannotCreateTransactionException while opening the EntityManager —
        // before the handler body, and with no @RestControllerAdvice able to
        // catch it. MockMvc rethrows an unhandled exception out of `perform`,
        // so `andReturn()` was never reached and the assertion never ran. The
        // failure surfaces as an ERROR rather than a FAILURE, which is the tell.
        //
        // This has been red on `develop` since A-033 met B-023 and went unseen
        // because Actions was billing-blocked from 11 Aug. C-025's note
        // recorded the same test failing locally and put it down to this
        // machine having no MySQL; that diagnosis was wrong — it fails on CI
        // too, where MySQL *is* running, because this context excludes it on
        // purpose (see the class javadoc).
        //
        // WHY THE FIX TAKES BOTH SHAPES, which is the part worth reading.
        //
        // The first attempt asserted that `perform` throws. That is green on CI
        // and RED on a developer machine, because *how* the handler body fails
        // without a usable schema depends on the environment: on CI the
        // datasource connects and the EntityManager cannot open, so an exception
        // escapes; locally there is nothing to connect to and the request comes
        // back with a status instead. Pinning either shape swaps one
        // environment-dependent test for another, which is what this one already
        // was.
        //
        // So both are accepted, and the assertion is the same claim either way:
        // NOT REFUSED. That is sound rather than lax, because a refusal has only
        // one shape. Spring Security's ExceptionTranslationFilter catches
        // AccessDeniedException inside the chain and renders 403 — it never
        // propagates out of `perform`. `refusalIsForbiddenAndNotUnauthorised`
        // below proves it, asserting a 403 *status* for a QA caller rather than
        // an exception. So an exception escaping is positive evidence that
        // authorisation passed and the request died in the handler body, and a
        // status is checked directly.
        //
        // The regression this exists to catch — a route wrongly restricted, or a
        // typo'd authority literal — still fails, through the status branch.
        // Mutation-checked: replacing this route's @PreAuthorize with
        // hasAuthority('no.such.capability') turns it red.
        //
        // Worth recording that the FIRST mutation attempted proved nothing.
        // Swapping master.write for ticket.close left the test green, because an
        // Admin holds ticket.close as well — Admin holds every capability, so no
        // real permission can be used to make this route refuse one. A mutation
        // has to name a capability that exists nowhere. Anyone re-checking this
        // guard will otherwise conclude it is toothless when it is not.
        //
        // Deliberately NOT "pick a route with no @Transactional service": that
        // makes this test's health depend on nobody ever adding one to whichever
        // route it names, which is precisely how it broke.
        // ---------------------------------------------------------------
        AtomicInteger status = new AtomicInteger(-1);

        Throwable thrown = catchThrowable(() ->
                status.set(mvc.perform(delete("/api/v1/masters/holidays/1")
                                .with(authentication(principal(RolePermissions.ADMIN))))
                        .andReturn().getResponse().getStatus()));

        if (thrown == null) {
            assertThat(status.get())
                    .as("an Admin holds master.write, so this must fail somewhere past authorisation")
                    .isNotEqualTo(403);
        } else {
            // Belt and braces. Unreachable as argued above, and cheap enough
            // that it need not be argued again by whoever changes the chain.
            assertThat(thrown)
                    .as("the request failed in the handler body, which means authorisation let it through")
                    .rootCause()
                    .isNotInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void refusalIsForbiddenAndNotUnauthorised() throws Exception {
        // 403, not 401 — the caller authenticated fine and lacks the capability,
        // and the frontend's interceptor redirects to login on 401. A permission
        // refusal that came back 401 would sign the user out and send them
        // through a login that keeps succeeding, which is the loop A-026's
        // PasswordChangeRequiredException chose 403 to avoid.
        //
        // Note this is a *permission* refusal. A-035's rule that an out-of-scope
        // ticket id answers 404 rather than 403 is a different question — row
        // scope, not capability — and is not decided here.
        mvc.perform(delete("/api/v1/masters/holidays/1")
                        .with(authentication(principal(RolePermissions.QA))))
                .andExpect(status().isForbidden());
    }

    @Test
    void bodyValidationRunsBeforeThePermissionCheck() {
        // Found by this test class failing, and pinned rather than worked
        // around, because it contradicts the natural reading of "PreAuthorize".
        //
        // @Valid @RequestBody is resolved during handler-argument resolution,
        // which happens before the handler method is invoked — and @PreAuthorize
        // is method-invocation advice. So a Developer posting an invalid body to
        // an Admin-only route gets 400, not 403: the validator answers first.
        //
        // Not a bypass. The handler body never runs, nothing is read or written,
        // and the caller learns only that the body they sent was malformed. What
        // it does mean is that an unauthorised caller can probe a route's
        // validation rules, and — the practical trap — that a permission test
        // asserting 403 will pass or fail depending on whether its fixture body
        // happens to be valid. Assert deny on a route with no validated body,
        // or send a valid one.
        //
        // If that probing ever matters for a specific route, the fix is a filter
        // or an AuthorizationManager on the chain, which runs before argument
        // resolution — not a change to the annotation.
        assertThatCode(() -> mvc.perform(post("/api/v1/masters/holidays")
                        .with(authentication(principal(RolePermissions.DEVELOPER)))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Built through the production converter rather than by handing MockMvc a
     * list of authorities, so this exercises the claim-to-authority mapping too.
     * A converter that stopped emitting permissions would fail here as well as
     * in its own unit test.
     */
    private AbstractAuthenticationToken principal(String roleCode) {
        return TestPrincipals.of(authorities, roleCode);
    }

    /**
     * Shared with A-036's matrix through {@link RouteInventory} — one definition
     * of which routes exist, or the coverage check built on each would drift
     * from the other.
     */
    private Map<RequestMappingInfo, HandlerMethod> applicationHandlers() {
        return RouteInventory.handlers(handlerMapping);
    }

    /**
     * Method annotation first, then the controller's — the same precedence
     * Spring Security applies, so what this reads is what will be enforced.
     */
    private static PreAuthorize preAuthorizeOn(HandlerMethod handler) {
        PreAuthorize onMethod =
                AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
    }

    /** Both the single-argument and the {@code hasAny…} forms. */
    private static Set<String> literals(Pattern pattern, String expression) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(expression);
        while (matcher.find()) {
            if (matcher.group(2) != null) {
                found.add(matcher.group(2));
                continue;
            }
            Matcher quoted = QUOTED.matcher(matcher.group(1));
            while (quoted.find()) {
                found.add(quoted.group(1));
            }
        }
        return found;
    }

    /** {@code POST /api/v1/masters/holidays} — the failure message has to be actionable. */
    private static String describe(RequestMappingInfo info, HandlerMethod handler) {
        return RouteInventory.describe(info, handler);
    }
}
