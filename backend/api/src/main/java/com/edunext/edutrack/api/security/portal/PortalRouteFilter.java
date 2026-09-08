package com.edunext.edutrack.api.security.portal;

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

/**
 * A-126 · calls {@link PortalRouteGuard}, at the same chain position A-111's
 * module gate occupies.
 *
 * <p>Not a {@code @Component}, for the reason {@code ModuleAccessFilter}
 * records: Spring Boot auto-registers any {@code Filter} bean into the servlet
 * chain, which makes an explicit {@code addFilterAfter} redundant and the
 * filter's position an accident of Boot's default ordering. {@code
 * SecurityConfig} constructs it, so deleting that line deletes the gate and the
 * tests say so.
 *
 * <h2>Before the module gate, and that ordering is the whole interaction</h2>
 *
 * <p>{@code ModuleAccessGuard.blocks} treats an absent {@link CallerIdentity}
 * as blocked — right for a staff gate — and a client token produces no
 * {@code CallerIdentity} at all by A-125's construction. So the module gate,
 * left to itself, <b>404s every client off their own portal</b>: it guards
 * {@code /api/v1/portal/} deliberately, to keep staff without the ONBOARDING
 * module out, and cannot tell that a client was never a candidate for a module
 * grant.
 *
 * <p>This filter runs first and, on the portal tree, decides the request
 * outright — refusing anyone who is not a client. {@code ModuleAccessFilter}
 * then skips any request carrying a client principal, so the two gates do not
 * both answer the same question with different vocabularies.
 */
public class PortalRouteFilter extends OncePerRequestFilter {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");

    private final PortalRouteGuard guard;
    private final ObjectMapper objectMapper;

    public PortalRouteFilter(PortalRouteGuard guard, ObjectMapper objectMapper) {
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    /** Everything outside {@code /api/v1/} pays one {@code startsWith} and nothing else. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !guard.guards(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!guard.blocks(CallerIdentity.of(authentication), ClientPrincipal.of(authentication),
                request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        refuse(request, response);
    }

    /**
     * The same shape the chain's other refusals take — bare
     * {@code application/problem+json}, {@code instance} from the request URI —
     * and the same body {@code ModuleAccessFilter} writes.
     *
     * <p>Byte-identical to that one on purpose. A client who reaches a staff
     * route and a staff user who reaches the portal must not be able to tell
     * which gate refused them, or the pair of messages becomes a way to map the
     * boundary from either side.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("No resource was found at this path.");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
