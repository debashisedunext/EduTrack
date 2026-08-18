package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.ClientAddress;
import com.edunext.edutrack.domain.audit.AuditEntry;
import com.edunext.edutrack.domain.audit.AuditTrail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A-071 · every mutating request, recorded, without anyone having to remember.
 *
 * <p>This is where "every login, permission change, master change and ticket
 * action" is actually met. The four modules belong to four developers; a
 * {@code record(...)} call at the end of each service method would be an edit
 * in three other people's directories and a guarantee no stronger than the last
 * person who remembered to add one. Here, a route written next month is audited
 * the day it is registered.
 *
 * <h2>Why a HandlerInterceptor and not a Filter</h2>
 *
 * <p>Both were tried; only one works. A servlet {@code Filter} bean is
 * registered outside Spring Security's {@code FilterChainProxy}, so on the way
 * in nobody is authenticated yet, and on the way out
 * {@code SecurityContextHolderFilter} has already cleared the context — the
 * actor is unreadable in both directions, which is the one field that makes a
 * row worth keeping. An interceptor runs inside {@code DispatcherServlet},
 * inside the security chain: the {@code SecurityContext} is still populated and
 * the route pattern and path variables have been resolved and parked on the
 * request. {@code afterCompletion} is therefore the only place where the actor,
 * the route, the subject and the outcome are all simultaneously in hand.
 *
 * <h2>What is recorded, and what is deliberately not</h2>
 *
 * <ul>
 *   <li><b>A mutating request that succeeded.</b> POST, PUT, PATCH or DELETE
 *       answering 2xx. This is the audit log.</li>
 *   <li><b>Any request refused by {@code @PreAuthorize}</b>, including reads,
 *       as {@link AuditActions#ACCESS_DENIED}. A non-Admin asking for
 *       {@code GET /audit-logs} changes nothing and so leaves no other trace,
 *       and it is exactly what this screen exists to surface. Detected from the
 *       <em>exception</em> rather than from the status — see {@link #actionFor},
 *       where getting this wrong silently lost every denial.</li>
 *   <li><b>Not 401.</b> An expired access token answers 401 on whatever route
 *       the SPA happened to be polling, and the client silently refreshes and
 *       retries — so recording those would write several rows per user per
 *       fifteen minutes, all of them describing a token lifetime rather than a
 *       person. The signal-to-noise is the whole argument: 403 means an
 *       authenticated human was told no.</li>
 *   <li><b>Not a failed mutation</b> — 4xx other than 403, 5xx, or a handler
 *       that threw. A validation error changed nothing; recording it fills the
 *       log with typing.</li>
 *   <li><b>Not the routes in {@link #SELF_RECORDED}.</b> Login and logout
 *       record themselves in {@code AuthController}, where the outcome, the
 *       submitted identifier and the distinction between refused, throttled and
 *       locked out are all visible. Deriving {@code LOGIN_CREATED} from the
 *       route here would say less and would also record a successful login and
 *       a refused one identically.</li>
 *   <li><b>Not {@code /webhooks/**}.</b> The caller is a mail provider with no
 *       EduTrack account, so every row would carry a null actor and mean
 *       "SYSTEM" — which is reserved for our own scanners. Bounces already have
 *       their own log in {@code email_log}.</li>
 * </ul>
 *
 * <h2>The honest limit</h2>
 *
 * <p>This runs after the response has been sent, so it cannot refuse an
 * operation it failed to record, and {@link AuditTrail} swallows a write
 * failure rather than turning a succeeded request into a 500. That is coverage
 * bought at the price of non-repudiation, argued in full in {@code README.md}.
 * It is also why a service wanting the stronger guarantee for one operation
 * calls {@code AuditTrail.record} inside its own transaction instead — which
 * composes with this rather than conflicting, at the cost of two rows.
 */
class AuditInterceptor implements HandlerInterceptor {

    /**
     * Routes that write their own richer row and must not also get a derived
     * one. Matched on the mapping pattern, so a path variable cannot smuggle
     * a request past the check.
     */
    private static final Set<String> SELF_RECORDED = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/logout");

    /**
     * Mutating routes that are machinery rather than something somebody did.
     *
     * <p>{@code POST /auth/refresh} is the whole of it, and it earns the
     * exclusion by measurement rather than by argument: one idle browser wrote
     * <b>eight rows in six minutes</b> on the first run against a real
     * database. It fires on a timer, per session, for as long as anybody is
     * signed in — into a table whose DELETE trigger means nothing can ever be
     * pruned from it.
     *
     * <p>Nothing is lost by dropping it. A session's start is
     * {@code LOGIN_SUCCESS} and its end is {@code LOGOUT}; the refreshes in
     * between say only that the session had not ended yet, which those two
     * rows already establish between them. Token *reuse* — the event that
     * actually matters on this route — is A-024's alarm and revokes the whole
     * family, which is a security event with its own path and not a line in a
     * scroll of routine activity.
     *
     * <p>This is the same judgement the 401 exclusion makes one paragraph up:
     * a row describing a token lifetime rather than a person is noise, and
     * noise on an unprunable table is how the entries that matter become
     * unfindable.
     */
    private static final Set<String> NOT_AN_ACTION = Set.of("/api/v1/auth/refresh");

    /** Prefixes skipped entirely — see the class javadoc. */
    private static final Set<String> IGNORED_MODULES = Set.of("webhooks");

    private final AuditTrail audit;

    AuditInterceptor(AuditTrail audit) {
        this.audit = audit;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod)) {
            // Static resources, the SPA shell, springdoc's own UI. Nothing that
            // reaches here is an application operation.
            return;
        }
        String pattern = patternOf(request);
        if (pattern == null || SELF_RECORDED.contains(pattern)
                || NOT_AN_ACTION.contains(pattern)) {
            return;
        }
        Optional<String> module = AuditActions.moduleFor(pattern);
        if (module.isEmpty() || IGNORED_MODULES.contains(module.get())) {
            return;
        }

        Optional<String> action = actionFor(request.getMethod(), pattern, response.getStatus(), ex);
        if (action.isEmpty()) {
            return;
        }
        boolean denied = AuditActions.ACCESS_DENIED.equals(action.get());

        audit.record(subject(pattern, request, new AuditEntry(
                actorId(),
                action.get(),
                module.get(),
                null,
                null,
                null,
                // The refused route is worth naming: "denied" alone does not say
                // what was reached for, and the pattern is not user input.
                denied ? request.getMethod() + " " + pattern : null,
                ClientAddress.of(request),
                request.getHeader(HttpHeaders.USER_AGENT))));
    }

    /**
     * The three cases, in the order they are decided.
     *
     * <p><b>Why the exception is consulted and not only the status.</b> A
     * {@code @PreAuthorize} refusal throws out of the dispatch: no MVC resolver
     * handles it, so Spring Security's {@code ExceptionTranslationFilter} — one
     * layer <em>outside</em> the dispatcher — writes the 403 after this method
     * has already run. Reading {@code response.getStatus()} alone therefore sees
     * 200 and records nothing, which is how the very denial this screen exists
     * to show became the one event it missed. Caught by
     * {@code AuditLogViewerIT.aDeniedReadIsAudited}, over real HTTP, because no
     * unit test reproduces that ordering.
     *
     * <p>403 is decided first and independently of the verb, because a refused
     * <em>read</em> has no other record. A refused mutation is recorded as
     * {@code ACCESS_DENIED} too rather than under its own term — it did not
     * happen, and a row reading {@code TICKETS_DELETED} for a deletion that was
     * refused would be the most misleading entry this table could hold.
     */
    private static Optional<String> actionFor(String method, String pattern, int status, Exception ex) {
        if (ex instanceof AccessDeniedException || status == HttpServletResponse.SC_FORBIDDEN) {
            return Optional.of(AuditActions.ACCESS_DENIED);
        }
        if (ex != null) {
            // The handler threw and nothing turned it into a status, so the
            // status is still to be written — 500, once the filter chain gets
            // there. The 2xx test below would otherwise read the untouched 200
            // and record the operation as having succeeded, which is the same
            // off-by-one-layer the denial above works around and a far worse
            // row to write.
            return Optional.empty();
        }
        if (!AuditActions.isMutating(method) || status < 200 || status >= 300) {
            return Optional.empty();
        }
        return AuditActions.actionFor(method, pattern);
    }

    /**
     * Fill in whichever of the two subject columns the path can support.
     *
     * <p>A numeric variable is the id; anything else — a ticket code — is the
     * reference. Done here rather than in {@code AuditEntry} because it is the
     * request that knows, and the record refuses to hold both.
     */
    private static AuditEntry subject(String pattern, HttpServletRequest request, AuditEntry entry) {
        String value = AuditActions.subjectVariable(pattern)
                .map(name -> pathVariables(request).get(name))
                .orElse(null);
        if (value == null || value.isBlank()) {
            return entry;
        }
        try {
            return new AuditEntry(entry.actorId(), entry.action(), entry.entityType(),
                    Long.valueOf(value), null,
                    entry.oldValue(), entry.newValue(), entry.ipAddress(), entry.userAgent());
        } catch (NumberFormatException notAnId) {
            return new AuditEntry(entry.actorId(), entry.action(), entry.entityType(),
                    null, value,
                    entry.oldValue(), entry.newValue(), entry.ipAddress(), entry.userAgent());
        }
    }

    /**
     * Null for SYSTEM, which is also what an unreadable principal produces.
     *
     * <p>{@link CallerIdentity} returns empty rather than a partial identity by
     * design, and the safe reading of empty here is "nobody we can name" — the
     * row is still written, because an action nobody can attribute is more
     * worth recording than less.
     */
    private static Long actorId() {
        return CallerIdentity.of(SecurityContextHolder.getContext().getAuthentication())
                .map(CallerIdentity::userId)
                .orElse(null);
    }

    private static String patternOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
