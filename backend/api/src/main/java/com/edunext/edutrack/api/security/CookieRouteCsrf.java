package com.edunext.edutrack.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Duration;

/**
 * A-074 · CSRF tokens for the two routes that authenticate from a cookie.
 *
 * <p>{@code SecurityConfig} has carried the reasoning for disabling CSRF since
 * A-032, together with the condition that reverses it. That reasoning was
 * correct and is not withdrawn here — it is <i>completed</i>. Restating it, so
 * that this class is readable without going back:
 *
 * <ul>
 *   <li>Every route that does anything authenticates from an {@code
 *       Authorization} header, which our own JavaScript sets and a browser never
 *       attaches by itself. Those routes cannot be forged and are <b>not</b>
 *       protected here — a CSRF token on a bearer route is pure ceremony.</li>
 *   <li>The cookie-authenticated routes are exactly two: {@code POST
 *       /auth/refresh} (A-024) and {@code POST /auth/logout} (A-025). Both read
 *       {@code refresh_token}, which the browser sends on its own.</li>
 * </ul>
 *
 * <h2>Why now, when {@code SameSite=Strict} already blocks this</h2>
 *
 * <p>It does, and that is why the residual risk was only a forced sign-out
 * rather than a compromise. Two things make the token worth having anyway, and
 * both are named in {@code SecurityConfig}'s own list of conditions:
 *
 * <ul>
 *   <li><b>{@code SameSite} is one mechanism, in one place.</b> It is enforced
 *       entirely by the browser, so it is exactly as good as the oldest browser
 *       that reaches this deployment and it fails silently and totally when
 *       that assumption breaks. A token is enforced by the server, which is the
 *       half we control.</li>
 *   <li><b>A shared parent domain defeats it.</b> {@code SameSite} treats every
 *       sibling subdomain as the same site, so one compromised neighbour on
 *       {@code *.example.com} can forge a request that {@code Strict} still
 *       considers same-site. A-075 decides where this deploys; this makes that
 *       decision not be a security one.</li>
 * </ul>
 *
 * <h2>Stateless double-submit, which is the only kind that fits</h2>
 *
 * <p>{@link CookieCsrfTokenRepository} keeps no server-side state: it writes the
 * token to a cookie the SPA can read and compares that cookie against the
 * {@code X-XSRF-TOKEN} header on the way in. A cross-origin page can do neither
 * — it cannot read our cookie and it cannot set one on our domain — which is
 * what makes the comparison meaningful.
 *
 * <p>A session-backed repository was not an option and it is worth saying why:
 * the chain is {@code STATELESS} deliberately (A-032), because a servlet session
 * would be a second, longer-lived way to be authenticated that A-025's idle and
 * absolute timeouts know nothing about. Storing a CSRF token in a session would
 * have created exactly that session on every request.
 *
 * <h2>The cookie mirrors {@code refresh_token}, including its lifetime</h2>
 *
 * <p>Same {@code Secure}, same {@code SameSite=Strict}, same seven days — and
 * the lifetime is the part that is easy to get wrong. A session cookie here
 * would look correct in every test and fail in one specific, common case:
 * <b>closing and reopening the browser.</b> {@code refresh_token} has a seven-day
 * {@code Max-Age} and survives that; a session-scoped CSRF cookie would not, so
 * the startup refresh in {@code AuthProvider} would arrive with a valid
 * credential and no token, be refused, and sign out every returning user. The
 * two cookies have to expire together or the shorter one silently shortens the
 * session.
 *
 * <p>Two differences from {@code refresh_token}, both required:
 *
 * <ul>
 *   <li><b>{@code HttpOnly} is false.</b> The SPA has to read this one — that is
 *       the whole mechanism. It is not a credential: on its own it authenticates
 *       nothing, and it is only meaningful when presented <i>with</i> the
 *       HttpOnly cookie an attacker still cannot read.</li>
 *   <li><b>{@code Path=/}, not {@code /api/v1/auth}.</b> A cookie scoped to the
 *       auth path is not visible to {@code document.cookie} on {@code /tickets},
 *       which is where the SPA is running when it needs to read it.</li>
 * </ul>
 *
 * <h2>Bearer-carrying requests are exempt, and Spring does that for us</h2>
 *
 * <p><b>Worth knowing before it surprises somebody.</b>
 * {@code oauth2ResourceServer} registers a CSRF override that ignores any
 * request carrying an {@code Authorization: Bearer} header, and it applies to
 * these two routes as much as to any other. The visible consequence is that
 * {@code POST /auth/logout} with a bearer token succeeds carrying no CSRF token
 * — measured, not assumed — which reads as a hole in exactly the place one was
 * just closed.
 *
 * <p>It is not one, because the question is not "is every request checked?" but
 * "is every request an <i>attacker</i> can produce checked?". A cross-origin
 * page cannot set an {@code Authorization} header; that is the same property the
 * bearer-routes-need-no-token argument above rests on. A forged logout therefore
 * arrives without a bearer, is not exempt, and is refused. The exemption only
 * reaches requests that have already demonstrated they were not sent ambiently.
 *
 * <p>Two practical consequences, both load-bearing: the SPA's logout needs no
 * change at all, and {@code POST /auth/refresh} — which the startup refresh
 * calls with no access token in hand — is the one route where the client must
 * actually send the header. {@code SecurityHardeningIT} pins both directions.
 *
 * <h2>One-time cost on the release that turns this on</h2>
 *
 * <p>A session that already exists when this ships has a {@code refresh_token}
 * and no {@code XSRF-TOKEN}, because the second cookie did not exist when the
 * first was issued. Its next startup refresh therefore arrives with a valid
 * credential and no token, is refused, and that user signs in again once.
 *
 * <p>Named rather than engineered around: the alternatives all amount to
 * accepting a refresh without a token for some window, which is the property
 * being added. In practice it costs nothing here — EduTrack has no deployment
 * yet, and A-075 is the task that gives it one — but if that ordering ever
 * changes, this is a line in a release note rather than a surprise.
 *
 * <h2>Deferred loading is switched off, deliberately</h2>
 *
 * <p>Spring Security 6 defers token generation until something asks for it, so
 * the cookie would only ever be written on a request that already needed it —
 * which, for a client that must hold the token <i>before</i> its first protected
 * call, is never. Setting the request-attribute name to null opts out, so any
 * response can carry the cookie and the SPA has it from its first call onwards.
 * This is the documented Spring Security pattern for a JavaScript client, not a
 * trick.
 */
@Configuration
public class CookieRouteCsrf {

    /** What the SPA reads. Spring's default name, and what every client expects. */
    static final String COOKIE_NAME = "XSRF-TOKEN";

    /** What the SPA sends back. Spring's default header name. */
    static final String HEADER_NAME = "X-XSRF-TOKEN";

    private final boolean secureCookie;
    private final Duration lifetime;

    /**
     * Bound from {@code RefreshTokenProperties}' keys rather than from the record
     * itself, which is package-private in {@code feature.auth} — importing it
     * here would mean widening a feature's internals for a header. The defaults
     * repeat that record's, and {@code cookieOutlivesTheRefreshToken} in
     * {@code CookieRouteCsrfTest} fails if the two ever drift apart.
     */
    public CookieRouteCsrf(
            @Value("${edutrack.auth.refresh-token.secure-cookie:true}") boolean secureCookie,
            @Value("${edutrack.auth.refresh-token.ttl:P7D}") Duration lifetime) {
        this.secureCookie = secureCookie;
        this.lifetime = lifetime;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(COOKIE_NAME);
        repository.setHeaderName(HEADER_NAME);
        repository.setCookieCustomizer(cookie -> cookie
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/")
                .maxAge(lifetime));
        return repository;
    }

    /**
     * Eager, for the reason in the class javadoc: a deferred token is written on
     * the response that consumed it, which is one request too late for a client
     * that has to present it.
     */
    @Bean
    CsrfTokenRequestHandler csrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * The two cookie-authenticated routes, and nothing else.
     *
     * <p>Matched on method as well as path: {@code CsrfFilter} already exempts
     * GET, HEAD, OPTIONS and TRACE, but stating {@code POST} keeps this list a
     * description of what it protects rather than something that depends on a
     * default elsewhere.
     *
     * <p><b>Every other route is deliberately unprotected</b> — see the class
     * javadoc. {@code csrfIsNotRequiredOnBearerRoutes} pins that, so widening
     * this matcher to everything (the reflex when a CSRF failure appears
     * somewhere unexpected) fails a test that says why instead of quietly
     * costing every client a header it does not need.
     */
    @Bean
    RequestMatcher cookieAuthenticatedRoutes() {
        PathPatternRequestMatcher.Builder route = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                route.matcher(HttpMethod.POST, "/api/v1/auth/refresh"),
                route.matcher(HttpMethod.POST, "/api/v1/auth/logout"));
    }
}
