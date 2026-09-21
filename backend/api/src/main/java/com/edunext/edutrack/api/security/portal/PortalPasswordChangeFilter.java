package com.edunext.edutrack.api.security.portal;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Calls {@link PortalPasswordChangeGate}, immediately after
 * {@link PortalRouteFilter} has established that the caller is a client on the
 * client's own tree.
 *
 * <h2>Why after, and not merged into it</h2>
 *
 * <p>The two gates answer different questions and give different answers.
 * {@code PortalRouteFilter} asks "is this the right kind of caller for this
 * tree?" and answers 404, hiding the route. This asks "may this caller, who is
 * unquestionably in the right place, do anything yet?" and answers 403, naming
 * the reason. Folding them together would mean one method with two refusal
 * shapes, and the first time somebody edited it the wrong one would be returned.
 *
 * <p>Order matters in one direction only: the route filter must run first, so
 * that a staff token on a portal path is 404ed rather than being told about a
 * password policy that does not apply to it.
 *
 * <h2>Not a {@code @Component}, and that is load-bearing</h2>
 *
 * <p>{@code ModuleAccessFilter} records the reason and it applies here
 * unchanged: Spring Boot auto-registers any {@code Filter} bean into the
 * servlet chain, which makes the explicit registration redundant and the gate's
 * position an accident of Boot's default ordering. {@code SecurityConfig}
 * constructs it, so deleting that line deletes the gate and the tests say so.
 */
public class PortalPasswordChangeFilter extends OncePerRequestFilter {

    /**
     * Its own type, distinct from the staff {@code password-change-required}.
     *
     * <p>They mean the same thing about two different principals, and
     * {@code CONVENTIONS.md} §3 makes {@code type} what clients branch on. A
     * shared URI would be read by the staff shell's interceptor, which would
     * route a client to {@code /change-password} — a staff route the portal
     * does not have.
     */
    private static final URI PASSWORD_CHANGE_REQUIRED =
            URI.create("https://edutrack/errors/portal-password-change-required");

    private final PortalPasswordChangeGate gate;
    private final ObjectMapper objectMapper;

    public PortalPasswordChangeFilter(PortalPasswordChangeGate gate, ObjectMapper objectMapper) {
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    /** Everything outside the portal tree pays one {@code startsWith} and nothing else. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PortalRouteGuard.PORTAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        /*
          A client principal, or nothing to decide. An anonymous caller is on
          /portal/auth/login and has no token to read; a staff caller has
          already been 404ed by PortalRouteFilter. Checking for the principal
          rather than merely for a JWT keeps this gate from ever forming an
          opinion about a staff token, which is A-026's gate's business.
        */
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || ClientPrincipal.of(authentication).isEmpty()
                || !gate.blocks(jwtAuthentication.getToken(), request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        refuse(request, response);
    }

    /**
     * 403, not 401, and the distinction carries real behaviour.
     *
     * <p>A 401 means "your credentials are no good, go and sign in", and the
     * portal's interceptor answers it by clearing the session and redirecting
     * to sign-in. That would be an infinite loop here: the credentials are
     * perfectly good, and signing in again mints another token with the same
     * claim. 403 says "we know who you are and you may not do this yet", which
     * is exactly true, and the distinct {@code type} tells the portal shell to
     * route to the change-password form rather than to the login card.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(PASSWORD_CHANGE_REQUIRED);
        problem.setTitle("Password change required");
        problem.setDetail("Choose your own password before using the portal.");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
