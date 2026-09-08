package com.edunext.edutrack.api.security.module;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * A-122 · applies {@link ObModuleRoleRules}, so §3's table is a rule rather
 * than a description.
 *
 * <h2>Two refusals, and the pair is the whole design</h2>
 *
 * <p>Generalised from {@code ObJourneyStepLifecycleService#requireModerator},
 * which is the one route that already did this and drew the line in the right
 * place:
 *
 * <ul>
 *   <li><b>No onboarding standing at all → 404.</b> Indistinguishable from a
 *       route that does not exist, on {@code ModuleAccessGuard}'s reasoning: a
 *       403 would tell somebody the onboarding module is deployed, which is a
 *       fact about what the organisation bought.</li>
 *   <li><b>An onboarding role, but the wrong one → 403.</b> This caller already
 *       knows the module exists — they hold a role in it — so a 404 would
 *       withhold nothing and would instead read as "that endpoint is gone",
 *       which is the answer that makes somebody file a bug rather than ask for
 *       a grant.</li>
 * </ul>
 *
 * <p>Getting this backwards in either direction is the failure worth naming.
 * All-404 leaks nothing and tells an OB Viewer their own module is broken;
 * all-403 is honest to the entitled and hands the module's existence to
 * everybody else.
 *
 * <h2>After the module gate, never before</h2>
 *
 * <p>{@code ModuleAccessFilter} answers 404 to a caller with no ONBOARDING
 * grant. Running this first would answer 403 to that same caller — leaking, in
 * a status code, exactly what the gate exists to withhold. So the order is:
 * authorization refuses the unauthenticated, the module gate refuses the
 * unentitled, and this refuses the wrongly-roled.
 *
 * <h2>A route with no rule passes</h2>
 *
 * <p>{@link ObModuleRoleRules#rolesFor} answering empty means "nobody has
 * decided", not "nobody may". Failing closed here would make adding an
 * onboarding endpoint silently 403 everybody, and the person who added it would
 * be looking at their controller rather than at a table two packages away.
 * {@code ObPermissionMatrixTest#everyRouteIsCovered} is what refuses an
 * unruled route — at build time, where it is cheap and obvious.
 *
 * <h2>Not a {@code @Component}</h2>
 *
 * <p>{@code ModuleAccessFilter}'s reason, learned by mutation: Spring Boot
 * auto-registers any {@code Filter} bean, which makes the explicit
 * registration redundant and the position an accident. {@code SecurityConfig}
 * constructs it, so deleting that line deletes the rule and the tests say so.
 */
public class ObModuleRoleFilter extends OncePerRequestFilter {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI FORBIDDEN = URI.create("https://edutrack/errors/forbidden");

    private final ObModuleRoleRules rules;
    private final ObjectMapper objectMapper;

    public ObModuleRoleFilter(ObModuleRoleRules rules, ObjectMapper objectMapper) {
        this.rules = rules;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rules.rolesFor(request.getMethod(), request.getRequestURI()).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Set<String> permitted = rules.rolesFor(request.getMethod(), request.getRequestURI())
                .orElse(null);
        if (permitted == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<String> moduleRole = CallerIdentity.of(SecurityContextHolder.getContext().getAuthentication())
                .flatMap(caller -> caller.moduleRole(ModuleAccessGuard.ONBOARDING))
                .filter(role -> !role.isBlank());

        if (moduleRole.isEmpty()) {
            // No standing in the module. The module gate would normally have
            // refused this caller already; reaching here means they hold the
            // ONBOARDING grant with no module_role recorded against it, which
            // is a data state rather than an entitlement — and it is answered
            // the same way, because the caller can do nothing either way.
            refuse(request, response, HttpStatus.NOT_FOUND, NOT_FOUND,
                    "Not found", "No resource was found at this path.");
            return;
        }

        if (!permitted.contains(moduleRole.get())) {
            refuse(request, response, HttpStatus.FORBIDDEN, FORBIDDEN,
                    "Not permitted",
                    // Names no role, neither the caller's nor the required one.
                    // "You are OB_VIEWER, this needs OB_ADMIN" is a map of the
                    // permission model handed out one refusal at a time.
                    "Your onboarding role does not permit this action.");
            return;
        }

        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response,
                        HttpStatus status, URI type, String title, String detail) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
