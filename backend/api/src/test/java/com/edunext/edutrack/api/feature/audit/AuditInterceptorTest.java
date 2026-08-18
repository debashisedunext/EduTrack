package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.domain.audit.AuditEntry;
import com.edunext.edutrack.domain.audit.AuditTrail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A-071 · what gets recorded, and — more importantly — what does not.
 *
 * <p>Every assertion here is a policy decision rather than a mechanism. The
 * interceptor is a dozen lines of plumbing; the value is entirely in the
 * predicate, and the two failures it can have are opposites. Recording too
 * little means an action nobody can prove happened. Recording too much means a
 * table so noisy that the row that mattered is unfindable — which is the same
 * outcome by a slower route, and the one nobody notices because the log looks
 * healthy.
 */
class AuditInterceptorTest {

    private AuditTrail trail;
    private AuditInterceptor interceptor;

    /**
     * Any controller method will do — only its being a {@link HandlerMethod}
     * matters, because that is the interceptor's test for "this request reached
     * the application" rather than a static resource.
     */
    private static final Object handler = handlerMethod();

    @BeforeEach
    void setUp() {
        trail = mock(AuditTrail.class);
        interceptor = new AuditInterceptor(trail);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("a successful mutation")
    class Mutations {

        @Test
        void isRecordedWithTheActorTheModuleAndTheSubject() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("PATCH", "/api/v1/masters/roles/{roleId}", Map.of("roleId", "4")),
                    response(200), handler, null);

            AuditEntry entry = recorded();
            assertThat(entry.actorId()).isEqualTo(7L);
            assertThat(entry.action()).isEqualTo("ROLES_UPDATED");
            assertThat(entry.entityType()).isEqualTo("masters");
            assertThat(entry.entityId()).isEqualTo(4L);
        }

        /**
         * The reason {@code entity_ref} exists. A ticket id in a path is a code,
         * not a number, and a row that dropped it would say only that a ticket
         * — some ticket — was commented on.
         */
        @Test
        @DisplayName("a non-numeric id lands in entityRef, not lost")
        void aTicketCodeIsKeptAsAReference() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("POST", "/api/v1/tickets/{ticketId}/comments",
                            Map.of("ticketId", "CRM-26-00347")),
                    response(201), handler, null);

            AuditEntry entry = recorded();
            assertThat(entry.entityRef()).isEqualTo("CRM-26-00347");
            assertThat(entry.entityId()).isNull();
            assertThat(entry.entityType()).isEqualTo("tickets");
            assertThat(entry.action()).isEqualTo("COMMENTS_CREATED");
        }

        @Test
        @DisplayName("the caller's address and agent are recorded")
        void theOriginIsKept() {
            signedInAs(7L);
            MockHttpServletRequest request =
                    request("DELETE", "/api/v1/masters/holidays/{holidayId}", Map.of("holidayId", "12"));
            request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
            request.addHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0");

            interceptor.afterCompletion(request, response(204), handler, null);

            AuditEntry entry = recorded();
            assertThat(entry.ipAddress()).isEqualTo("203.0.113.9");
            assertThat(entry.userAgent()).isEqualTo("Mozilla/5.0");
        }

        /**
         * A validation error changed nothing, and a log that records attempts
         * alongside events cannot be read as a record of what happened.
         */
        @Test
        void thatFailedValidationIsNotRecorded() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("POST", "/api/v1/tickets", Map.of()), response(400), handler, null);

            verifyNoInteractions(trail);
        }

        @Test
        void thatFailedOnTheServerIsNotRecordedEither() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("POST", "/api/v1/tickets", Map.of()), response(500), handler, null);

            verifyNoInteractions(trail);
        }
    }

    @Nested
    @DisplayName("a refusal")
    class Refusals {

        /**
         * The only failure recorded, and the only trace a refused <em>read</em>
         * leaves anywhere — nothing changed, so no other row is written.
         */
        @Test
        void of403OnAReadIsRecordedAsAccessDenied() {
            signedInAs(3L);

            interceptor.afterCompletion(
                    request("GET", "/api/v1/audit-logs", Map.of()), response(403), handler, null);

            AuditEntry entry = recorded();
            assertThat(entry.action()).isEqualTo(AuditActions.ACCESS_DENIED);
            assertThat(entry.actorId()).isEqualTo(3L);
            assertThat(entry.newValue()).isEqualTo("GET /api/v1/audit-logs");
        }

        /**
         * A refused DELETE must not read as a completed one. This is the single
         * most misleading row the table could hold, so the 403 branch is decided
         * before the verb is consulted.
         */
        @Test
        void of403OnAMutationIsNotFiledUnderTheMutationsOwnTerm() {
            signedInAs(3L);

            interceptor.afterCompletion(
                    request("DELETE", "/api/v1/masters/holidays/{holidayId}", Map.of("holidayId", "12")),
                    response(403), handler, null);

            assertThat(recorded().action()).isEqualTo(AuditActions.ACCESS_DENIED);
        }

        /**
         * An expired access token answers 401 on whatever the SPA was polling and
         * the client silently refreshes, so recording these writes several rows
         * per user per fifteen minutes that describe a token lifetime rather than
         * a person.
         */
        @Test
        void of401IsNotRecorded() {
            interceptor.afterCompletion(
                    request("GET", "/api/v1/tickets", Map.of()), response(401), handler, null);

            verifyNoInteractions(trail);
        }

        /**
         * The one that was actually broken, and the reason this test exists.
         *
         * <p>A {@code @PreAuthorize} refusal throws out of the dispatch and the
         * 403 is written by a filter <em>outside</em> the dispatcher, so at this
         * point the response still says 200. Deciding on the status alone
         * recorded nothing — the audit log silently missed the single event it
         * is most often opened to find. Found by the integration test; pinned
         * here so it cannot come back cheaply.
         */
        @Test
        @DisplayName("is recognised from the exception, before the status is written")
        void anUnwrittenForbiddenIsStillADenial() {
            signedInAs(3L);

            interceptor.afterCompletion(
                    request("GET", "/api/v1/audit-logs", Map.of()), response(200), handler,
                    new AccessDeniedException("Access Denied"));

            assertThat(recorded().action()).isEqualTo(AuditActions.ACCESS_DENIED);
        }

        /**
         * The mirror image, and the reason the exception is consulted at all
         * rather than only ORed with the status. A handler that threw leaves the
         * response at 200 for exactly the same reason a denial does — so
         * anything that fell through to the filter chain must record nothing,
         * or every 500 would be filed as a completed operation.
         */
        @Test
        void aHandlerThatThrewRecordsNothing() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("POST", "/api/v1/tickets", Map.of()), response(200), handler,
                    new IllegalStateException("boom"));

            verifyNoInteractions(trail);
        }
    }

    @Nested
    @DisplayName("skipped entirely")
    class Skipped {

        /**
         * Login records itself with the outcome, the identifier and the
         * distinction between refused, throttled and locked out. A derived row
         * would say {@code LOGIN_CREATED} for a successful sign-in and nothing
         * at all for a rejected one.
         */
        @Test
        void isTheLoginRouteWhichRecordsItself() {
            interceptor.afterCompletion(
                    request("POST", "/api/v1/auth/login", Map.of()), response(200), handler, null);

            verifyNoInteractions(trail);
        }

        @Test
        void isTheLogoutRouteForTheSameReason() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("POST", "/api/v1/auth/logout", Map.of()), response(204), handler, null);

            verifyNoInteractions(trail);
        }

        /**
         * Measured rather than argued: one idle browser wrote eight of these in
         * six minutes on the first run against a real database. A refresh is a
         * timer, not something somebody did, and the table it would fill has a
         * DELETE trigger — nothing can ever be pruned from it. The session's
         * start and end are recorded; its middle is machinery.
         */
        @Test
        @DisplayName("is the token refresh, which fires on a timer forever")
        void isTheRefreshRoute() {
            signedInAs(7L);

            interceptor.afterCompletion(
                    request("POST", "/api/v1/auth/refresh", Map.of()), response(200), handler, null);

            verifyNoInteractions(trail);
        }

        /**
         * The caller is a mail provider with no EduTrack account, so every row
         * would carry a null actor and read as SYSTEM — which is reserved for
         * our own scanners.
         */
        @Test
        void areMailWebhooks() {
            interceptor.afterCompletion(
                    request("POST", "/api/v1/webhooks/email/bounce", Map.of()),
                    response(202), handler, null);

            verifyNoInteractions(trail);
        }

        @Test
        @DisplayName("is anything that is not a controller method")
        void areStaticResources() {
            interceptor.afterCompletion(
                    request("POST", "/api/v1/tickets", Map.of()), response(200), "not a handler", null);

            verifyNoInteractions(trail);
        }

        @Test
        @DisplayName("is a request that matched no mapping pattern")
        void areUnmatchedRequests() {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tickets");

            interceptor.afterCompletion(request, response(200), handler, null);

            verify(trail, never()).record(any());
        }
    }

    @Nested
    class Actors {

        /**
         * Recorded rather than dropped. An action nobody can attribute is more
         * worth keeping than less, and {@code null} already means SYSTEM in this
         * column — {@code dev-noauth} and the scanners both land here.
         */
        @Test
        @DisplayName("an unreadable principal still writes the row, as SYSTEM")
        void anUnidentifiableCallerIsStillRecorded() {
            interceptor.afterCompletion(
                    request("POST", "/api/v1/tickets", Map.of()), response(201), handler, null);

            assertThat(recorded().actorId()).isNull();
        }
    }

    // --- helpers -----------------------------------------------------------

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(trail).record(captor.capture());
        return captor.getValue();
    }

    private static void signedInAs(long userId) {
        // A JWT-shaped principal is not needed: CallerIdentity reads the numeric
        // subject and the role claim, and TestingAuthenticationToken with a
        // DevPrincipal-shaped payload is the cheaper of the two ways to get one.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(
                        new DevPrincipal(userId, "tester", "Tester", "ADMIN", List.of(), List.of()),
                        "n/a", "ROLE_ADMIN"));
    }

    private static MockHttpServletRequest request(String method, String pattern,
                                                  Map<String, String> pathVariables) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, pattern.replaceAll("\\{[^}]+}", "1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVariables);
        request.setRemoteAddr("198.51.100.4");
        return request;
    }


    /** A named class rather than an anonymous one: {@code getMethod} on an
     *  anonymous class is a reflection edge case, and this test is not about
     *  reflection. */
    static class StubController {
        public void handle() {
        }
    }

    private static HandlerMethod handlerMethod() {
        try {
            return new HandlerMethod(new StubController(), "handle");
        } catch (NoSuchMethodException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static MockHttpServletResponse response(int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return response;
    }
}
