package com.edunext.edutrack.api.security.module;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * A-111, second half · the thing that actually calls {@link ModuleAccessGuard}.
 *
 * <h2>The guard has been written and unenforced since 4 September</h2>
 *
 * <p>{@code ModuleAccessGuard}'s own class note says so plainly — "<b>Nothing
 * calls this yet</b> … the first half of a two-task change" — and named the
 * precondition: "there are no {@code /api/v1/onboarding/**} handlers to guard
 * until B and C build them, so a gate wired into the chain today would be a
 * filter with nothing behind it."
 *
 * <p>That precondition has expired. There are now onboarding controllers for
 * the dashboard, escalations, journey templates and step lifecycle, and
 * {@code PermissionMatrix} records the consequence in as many words: every one
 * of the six platform roles can reach those routes today, "which is the true
 * and complete answer until that wiring lands". This is that wiring.
 *
 * <h2>Position in the chain, and why it is after authorization</h2>
 *
 * <p>Registered <b>after</b> {@code AuthorizationFilter}, which is later than
 * the obvious reading of "before RolesGuard" would suggest. RolesGuard is
 * method security — {@code @PreAuthorize} on the controller — and that runs at
 * handler invocation, after every filter, so any position in the chain is
 * before it.
 *
 * <p>The position that matters is the one relative to <em>authentication</em>.
 * {@link ModuleAccessGuard#blocks} treats an absent caller as blocked, which is
 * the right default for a gate and the wrong status for an anonymous request:
 * running before authorization would answer <b>404</b> to somebody who simply
 * has not signed in, where the chain already has a considered <b>401</b> for
 * that. So authorization refuses the unauthenticated first, and this refuses
 * the unentitled second.
 *
 * <h2>404, and it writes its own</h2>
 *
 * <p>The status is the guard's argument, not this class's: a 403 on
 * {@code /api/v1/onboarding/clients} tells a ticketing-only user that the
 * onboarding module is deployed — a fact about what the organisation bought,
 * disclosed to somebody the organisation decided should not have it.
 *
 * <p>The response is built here rather than by delegating to
 * {@code ProblemErrorResponses}, which is package-private in
 * {@code api.security} and implements the two Spring SPIs for 401 and 403
 * rather than a general writer. The shape is copied from it deliberately —
 * bare {@code application/problem+json} with no charset, {@code instance} set
 * from the request URI — because a refusal that differs in content type or
 * envelope from the chain's others is a difference a client eventually
 * branches on.
 *
 * <h2>Not a {@code @Component}, and that is load-bearing</h2>
 *
 * <p>It was one, briefly. Spring Boot auto-registers <em>any</em> {@code Filter}
 * bean into the servlet chain, so the explicit {@code addFilterAfter} in
 * {@code SecurityConfig} was redundant — and <b>removing that line changed
 * nothing</b>, which a mutation check caught: all seven tests in
 * {@code ModuleAccessFilterTest} still passed with the registration deleted.
 * A gate whose position is an accident of Boot's default ordering, proved by
 * tests that cannot tell whether it is wired, is the same failure this task
 * exists to correct — one layer up.
 *
 * <p>So it is constructed by {@code SecurityConfig} and registered exactly
 * once, at exactly one place in the chain. Deleting that line now deletes the
 * gate, and the tests say so.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not gate on module <em>role</em>. A caller holding
 * {@code ONBOARDING} passes here and is then subject to the module's own six
 * roles and to {@code OnboardingScopeResolver}. One question, one refusal.
 *
 * <p>It also leaves {@code PasswordChangeGate} (A-026) alone, which is the
 * other gate in this codebase written, tested, exception-mapped and never
 * called. It belongs at this same point and is not switched on here: it
 * refuses <em>every</em> route but one for a whole class of users, so turning
 * it on deserves its own change and its own review rather than riding on this.
 */
public class ModuleAccessFilter extends OncePerRequestFilter {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");

    private final ModuleAccessGuard guard;
    private final ObjectMapper objectMapper;

    public ModuleAccessFilter(ModuleAccessGuard guard, ObjectMapper objectMapper) {
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    /**
     * Skips every path the guard has no opinion about, so the common request
     * pays one {@code startsWith} and nothing else.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !guard.guards(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // A-126 · a client principal is not a candidate for a module grant, and
        // this guard treats an absent CallerIdentity as blocked — so left to
        // itself it 404s every client off their own portal, which it guards on
        // purpose to keep unentitled staff out. PortalRouteFilter has already
        // decided that request; deferring here keeps one question with one gate
        // rather than two answering it in different vocabularies.
        if (ClientPrincipal.of(SecurityContextHolder.getContext().getAuthentication()).isPresent()) {
            chain.doFilter(request, response);
            return;
        }
        if (!guard.blocks(caller(), request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        refuse(request, response);
    }

    /**
     * The caller as both chains build it — a real JWT or {@code dev-noauth}'s
     * synthetic principal. {@code CallerIdentity.of} already knows the
     * difference, so this filter does not have to.
     */
    private Optional<CallerIdentity> caller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return CallerIdentity.of(authentication);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        // Says nothing about modules. A detail naming the entitlement would
        // hand back exactly the fact the 404 exists to withhold, and would do
        // it in the one place somebody reading the body would look.
        problem.setDetail("No resource was found at this path.");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
