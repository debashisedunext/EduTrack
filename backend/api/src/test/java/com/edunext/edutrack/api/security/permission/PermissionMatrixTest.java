package com.edunext.edutrack.api.security.permission;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.api.security.permission.PermissionMatrix.Entry;
import com.edunext.edutrack.api.security.permission.PermissionMatrix.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * A-036 · every role against every route, and no route without an entry.
 *
 * <h2>What this adds over A-033</h2>
 *
 * <p>{@code RouteAuthorizationTest} proves every route carries <em>a</em>
 * decision. It never reads the decision, so a route annotated with the wrong
 * capability satisfies it completely. This suite carries an independently
 * authored expectation per role per route — {@link PermissionMatrix}, written
 * from blueprint §2 rather than from the annotations — and drives a real request
 * for each, so the two have to agree.
 *
 * <p>The second half is {@link #everyRoutedEndpointHasAMatrixEntry()}: a new
 * route with no entry fails the build. That is the part that survives everybody
 * currently on the project, and it is why the inventory is shared with A-033
 * through {@link RouteInventory} rather than restated here.
 *
 * <h2>Why the row scope 404 is not in this class</h2>
 *
 * <p>The matrix has a third outcome — an out-of-scope id answering 404 rather
 * than 403 — and it cannot be asserted here, because "out of scope" is a fact
 * about rows and this context has no database. It is
 * {@code ScopeMatrixIT}'s subject, against real MySQL, for all six roles. The
 * two halves are split by what they need to run, not by what they are about:
 * this one runs in surefire on every build, which is the point of keeping it
 * infrastructure-free.
 *
 * <h2>No infrastructure, and deliberately no reachable one</h2>
 *
 * <p>The context is built the way {@code ApplicationSmokeTest} builds it, so
 * this runs wherever Maven does rather than only where Docker is installed. A
 * denied request stops before the handler body and touches nothing; an
 * <em>allowed</em> one reaches the handler and would happily talk to whatever
 * database the default configuration points at, which on a developer's machine
 * is their running dev instance. Several allowed rows are writes. So the
 * datasource and Redis are pointed at a closed port on purpose: every handler
 * that is reached fails immediately and locally, the assertion ("not 403") still
 * holds, and no suite in this repository can write to a real database by being
 * run in the wrong directory.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        // Port 1 is reserved and never listening: connections are refused in
        // microseconds rather than hanging for a connect timeout, which matters
        // when most of the several hundred cases below reach a handler.
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/edutrack_unreachable",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class PermissionMatrixTest {

    /**
     * Not an HTTP status. Stands for "the handler ran and threw", which only
     * happens to a request authorisation let through — see {@link #statusOf}.
     */
    private static final int HANDLER_WAS_REACHED = -1;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @Autowired
    MockMvc mvc;

    // ------------------------------------------------------------------
    // the matrix itself
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{2} · {1} · {0}")
    @MethodSource("everyRoleTimesEveryRoute")
    void theMatrixHolds(Entry entry, String role, Outcome expected) {
        int status = statusOf(entry, role);

        switch (expected) {
            case DENY -> assertThat(status)
                    .as("""
                            %s must be refused %s, with 403.

                            403 and not 401: the caller authenticated fine and lacks the \
                            capability, and the frontend's interceptor signs a user out on \
                            401 — a permission refusal that came back 401 would loop them \
                            through a login that keeps succeeding.

                            If this is 400, the fixture body in PermissionMatrix no longer \
                            satisfies the DTO's constraints. @Valid @RequestBody is resolved \
                            during argument resolution, which runs before @PreAuthorize, so \
                            the validator answers first and the row proves nothing. Fix the \
                            body, do not relax the assertion.

                            If this is %d, the handler ran and threw — which means \
                            authorisation did not refuse this caller at all.""",
                            role, entry.key(), HANDLER_WAS_REACHED)
                    .isEqualTo(403);

            case ALLOW -> assertThat(status)
                    .as("""
                            %s holds the capability for %s, so this must fail somewhere past \
                            authorisation — 403 is the one status it must not be.

                            Not asserted as 200: there is no reachable database here, so the \
                            handler body fails by design. The claim is about the guard.""",
                            role, entry.key())
                    .isNotEqualTo(403);
        }
    }

    /**
     * The response status, or {@link #HANDLER_WAS_REACHED} when the handler
     * threw.
     *
     * <p>MockMvc rethrows an exception that escapes a handler rather than
     * rendering it as a 500 — there is no servlet container to translate it.
     * Most allowed rows here do exactly that, because the handler body reaches
     * for a database that is deliberately unreachable. That is not a problem to
     * be caught and ignored: an exception from inside the handler is *proof*
     * the request got past authorisation, which is the whole claim an allow row
     * makes. It is deliberately mapped to a sentinel rather than to 403's
     * complement so that a deny row cannot be satisfied by it — a denied caller
     * must never reach a handler body at all.
     */
    private int statusOf(Entry entry, String role) {
        try {
            return mvc.perform(request(entry, role)).andReturn().getResponse().getStatus();
        } catch (Exception handlerThrew) {
            return HANDLER_WAS_REACHED;
        }
    }

    static Stream<Arguments> everyRoleTimesEveryRoute() {
        return PermissionMatrix.ENTRIES.stream().flatMap(entry ->
                new TreeSet<>(RolePermissions.ROLE_CODES).stream()
                        .map(role -> Arguments.of(entry, role, entry.expectedFor(role))));
    }

    // ------------------------------------------------------------------
    // a new route without an entry does not build
    // ------------------------------------------------------------------

    @Test
    void everyRoutedEndpointHasAMatrixEntry() {
        Set<String> missing = new TreeSet<>(RouteInventory.routeKeys(handlerMapping));
        missing.removeAll(matrixKeys());

        assertThat(missing)
                .as("""
                        A route with no permission-matrix entry. Every role's outcome on it \
                        is currently unasserted, and it reads in the source exactly like a \
                        route that was reasoned about — which is the whole failure this \
                        suite exists for.

                        Add one line to PermissionMatrix.ENTRIES naming the roles allowed to \
                        reach it, taken from blueprint §2 and not from the annotation you \
                        just wrote. If it is a write with a @Valid body and any role is \
                        denied, give it a fixture body that satisfies the DTO.""")
                .isEmpty();
    }

    @Test
    void everyMatrixEntryIsARoutedEndpoint() {
        Set<String> orphaned = new TreeSet<>(matrixKeys());
        orphaned.removeAll(RouteInventory.routeKeys(handlerMapping));

        assertThat(orphaned)
                .as("""
                        A matrix entry for a route this application no longer serves — a \
                        renamed path, a deleted handler, or a typo in the pattern. It has \
                        been passing without asserting anything, because a request to a \
                        path nothing is mapped to answers 404 for every role.

                        Asserted in both directions on purpose: coverage that only checks \
                        routes-have-entries lets stale rows accumulate and quietly stop \
                        meaning anything.""")
                .isEmpty();
    }

    @Test
    void theInventoryIsNotEmpty() {
        // Guards the way both coverage assertions fail open. Each is a set
        // difference against the handler mapping, so a context that built
        // without controllers — a component-scan change, a bean that failed
        // quietly — would satisfy the first by having nothing to check.
        assertThat(RouteInventory.routeKeys(handlerMapping))
                .as("no application routes found; the coverage assertions would be vacuous")
                .hasSizeGreaterThan(30);
    }

    // ------------------------------------------------------------------
    // the matrix itself has to be well-formed
    // ------------------------------------------------------------------

    @Test
    void everyEntryNamesRealRoles() {
        Set<String> unknown = new TreeSet<>();
        for (Entry entry : PermissionMatrix.ENTRIES) {
            entry.allowedRoles().stream()
                    .filter(role -> !RolePermissions.ROLE_CODES.contains(role))
                    .forEach(role -> unknown.add(role + "  —  " + entry.key()));
        }

        // A role code that is not one of the six can never match the role a
        // principal is built with, so the entry silently becomes deny-for-all —
        // and would keep passing for as long as the route really did deny
        // everybody. The specific accident is SUPPORT_DESK, the pre-correction
        // code V20260807_1030 renamed.
        assertThat(unknown)
                .as("allowed-role codes must be blueprint §2 codes, post-V20260807_1030")
                .isEmpty();
    }

    @Test
    void everyRouteWithARequiredBodyCarriesAFixture() {
        Map<String, HandlerMethod> byKey = new TreeMap<>();
        RouteInventory.handlers(handlerMapping)
                .forEach((info, handler) -> byKey.put(RouteInventory.describe(info, handler), handler));

        Set<String> bodyless = new TreeSet<>();
        for (Entry entry : PermissionMatrix.ENTRIES) {
            HandlerMethod handler = byKey.get(entry.key());
            if (handler != null && requiresBody(handler) && entry.body() == null) {
                bodyless.add(entry.key());
            }
        }

        assertThat(bodyless)
                .as("""
                        A matrix entry for a route whose handler requires a @RequestBody, with \
                        no fixture body. Every one of its six cases is passing on a 400 raised \
                        during argument resolution — before @PreAuthorize runs — so none of \
                        them asserts anything about authorisation at all.

                        This is not hypothetical: it was true of most of the allowed rows in \
                        this matrix's first draft, and was found by mis-stating a row on \
                        purpose and watching the suite stay green.

                        Add a body to PermissionMatrix that satisfies the DTO's constraints. \
                        It does not have to succeed — it has to be valid enough to reach the \
                        guard.""")
                .isEmpty();
    }

    /**
     * Read from the handler signature rather than from a list kept by hand,
     * so a DTO that gains {@code @RequestBody} tomorrow is covered without
     * anybody remembering this check exists.
     */
    private static boolean requiresBody(HandlerMethod handler) {
        return Arrays.stream(handler.getMethodParameters())
                .map(parameter -> parameter.getParameterAnnotation(RequestBody.class))
                .filter(Objects::nonNull)
                .anyMatch(RequestBody::required);
    }

    @Test
    void noRouteAppearsTwice() {
        Set<String> seen = new TreeSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (Entry entry : PermissionMatrix.ENTRIES) {
            if (!seen.add(entry.key())) {
                duplicated.add(entry.key());
            }
        }

        // Two rows for one route means one of them is being asserted and the
        // other is decoration — and the coverage checks above cannot see it,
        // because a set difference is satisfied by either.
        assertThat(duplicated).as("duplicate matrix entries").isEmpty();
    }

    @Test
    void theMatrixDeniesSomething() {
        // A matrix in which every role reaches every route would pass every
        // assertion in this class while asserting nothing about authorisation
        // at all — the failure mode of "allow" being the easy row to write.
        long denials = PermissionMatrix.ENTRIES.stream()
                .filter(entry -> entry.allowedRoles().size() < RolePermissions.ROLE_CODES.size())
                .count();

        assertThat(denials)
                .as("the matrix must contain restricted routes, or it is asserting nothing")
                .isGreaterThan(10);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder request(Entry entry, String role) {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(HttpMethod.valueOf(entry.method()), entry.requestPath())
                        .with(authentication(TestPrincipals.of(authorities, role)));

        if (entry.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(entry.body());
        }
        return request;
    }

    private static Set<String> matrixKeys() {
        Set<String> keys = new TreeSet<>();
        PermissionMatrix.ENTRIES.forEach(entry -> keys.add(entry.key()));
        return keys;
    }
}
