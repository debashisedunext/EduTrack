package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Duration;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * A-032 · the real filter chain, replacing {@code ScaffoldSecurityConfig}'s
 * permit-all.
 *
 * <p>Everything A-020 through A-031 built was, until now, unenforced: a forged
 * token, an expired one and a logged-out one all reached every endpoint. This is
 * the class that makes those eleven tasks mean something.
 *
 * <p><b>The scaffold was deleted rather than left to bow out on its own.</b> Its
 * javadoc promised {@code @ConditionalOnMissingBean(SecurityFilterChain.class)}
 * would retire it the moment a real chain existed, so nobody would have to
 * remember. That is true of auto-configuration classes, which Spring processes
 * last, and not of an ordinary {@code @Configuration}: the condition is
 * evaluated in bean-registration order, component scan reaches {@code api.config}
 * before {@code api.security}, and the scaffold therefore always wins the race
 * against the very bean it was meant to defer to. Both chains registered, both
 * matched every request, and Spring Security refused to start —
 * {@code UnreachableFilterChainException}, which at least fails loudly. Left in
 * place with the condition "fixed" by ordering, it would be a permit-all chain
 * kept dormant by luck.
 *
 * <h2>What this task decides, and what it does not</h2>
 *
 * <p>This chain answers exactly one question: <b>is the caller who they say they
 * are, holding a token we issued that is still live?</b> What they are then
 * permitted to do is A-033's {@code @PreAuthorize}; which rows they may see is
 * A-034's {@code ScopeResolver}; and the requirement that an out-of-scope id
 * answers 404 rather than 403 is A-035. Deliberately not anticipated here —
 * a half-built authorisation rule is worse than an absent one, because it reads
 * as covered.
 *
 * <p><b>A-033 has since added one line and no route rules.</b> The permission
 * decision stayed out of this chain — it is per-handler and lives on the
 * handlers, enabled by {@code MethodSecurityConfig} — but authorities have to
 * reach it from somewhere, and that is
 * {@link com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter} wired
 * into {@code oauth2ResourceServer} below. Expressing the same rules a second
 * time as {@code .requestMatchers(…).hasAuthority(…)} here was declined: two
 * places to state one route's permission is two places for them to disagree,
 * and path patterns do not see the method the annotation sits on.
 *
 * <h2>The route rules</h2>
 *
 * <p><b>Only {@code /api/**} is protected.</b> Everything else is the SPA:
 * {@code SpaResourceConfig} registers a handler on {@code /**} and forwards
 * unknown paths to {@code index.html} so the browser can resolve client-side
 * routes. Requiring authentication there would mean a user navigating straight
 * to {@code /tickets} gets a 401 <i>instead of the application shell</i> — and
 * because the shell never loads, it presents as a blank page rather than an auth
 * error, which is the failure {@code ScaffoldSecurityConfig}'s own javadoc warns
 * A-032 about. Real protection belongs on the API; the SPA's routing is
 * convenience.
 *
 * <p>The public {@code /api} paths are exactly the six the contract marks
 * {@code security: []}, and each is public for a reason that is not "we forgot":
 *
 * <ul>
 *   <li><b>login, refresh, forgot-password, reset-password</b> — the caller has
 *       no access token by definition. Requiring one to obtain one is a closed
 *       loop.</li>
 *   <li><b>the two email webhooks</b> — called by the mail provider, which has
 *       no EduTrack account. They authenticate by HMAC signature in
 *       {@code WebhookSignatureVerifier}, which <i>rejects every request</i>
 *       when its secret is unset. Permitting them here is not leaving them
 *       open; it is declining to check the wrong credential.</li>
 * </ul>
 *
 * <p><b>{@code /ws} is permitted at the handshake.</b> D-013's channel
 * interceptor authorises each subscription with the same scope rules as REST, which
 * is where the decision belongs — a STOMP client proves itself on CONNECT, not
 * on the HTTP upgrade.
 *
 * <p><b>Actuator: health and info only.</b> {@code metrics} and
 * {@code prometheus} are authenticated, because between them they publish
 * request counts per endpoint and JVM internals to anyone who asks. Health is
 * already {@code show-details: when-authorized}, so an anonymous caller learns
 * only up or down.
 *
 * <p><b>springdoc is closed by default — A-074 resolved the temporary.</b> The
 * paragraph that stood here said {@code /v3/api-docs} publishes the entire API
 * surface, that it stayed open because {@code make api} advertises Swagger UI,
 * and that closing it was a deployment-shaped decision belonging to A-074. It is
 * now closed unless {@code API_DOCS_ENABLED} says otherwise, and the paths are
 * only permitted below when it does. Flipping the switch without also closing
 * the chain would leave springdoc unreachable but still listed as public, which
 * reads as an open door in the one file people audit for open doors.
 *
 * <p>It is switched on in the {@code local} profile, so {@code make api} is
 * unchanged, and in the api module's test-scope properties, because
 * {@code ContractConformanceTest} fetches {@code /v3/api-docs} from the running
 * application and D-005's client generation depends on it. <b>Closed by default,
 * opened explicitly where something needs it</b> — the direction
 * {@code WebhookSignatureVerifier} already takes, whose own comment is that an
 * open endpoint is a worse default than a broken one.
 *
 * <h2>CSRF: enabled on the cookie routes, off everywhere else</h2>
 *
 * <p><b>Not copied from the scaffold.</b> The access token travels in an
 * {@code Authorization} header put there by our own JavaScript, and a browser
 * never attaches headers automatically — so no bearer-authenticated route can be
 * forged from another origin, which is every route that does anything. Those
 * routes are still not CSRF-protected and that remains deliberate: a token on a
 * bearer route defends against nothing and costs every client a header.
 *
 * <p>What changed is the other half. {@code POST /auth/refresh} and
 * {@code POST /auth/logout} authenticate from a cookie the browser attaches by
 * itself, and A-032 deferred their tokens to A-074 on the grounds that
 * {@code SameSite=Strict} made the residual risk a forced sign-out rather than a
 * compromise. That was true and still is; the reason to close it anyway is that
 * {@code SameSite} is enforced entirely by the browser and treats a compromised
 * sibling subdomain as same-site. {@link CookieRouteCsrf} carries the full
 * reasoning and the mechanism.
 *
 * <h2>Security headers</h2>
 *
 * <p>The other half of A-074's line item. Spring already emitted
 * {@code X-Frame-Options}, {@code X-Content-Type-Options} and HSTS from its
 * defaults; what was missing is everything below them —
 * {@code Content-Security-Policy} above all, which is assembled per deployment
 * by {@link ContentSecurityPolicy} rather than written out as a constant.
 *
 * <h2>Stateless</h2>
 *
 * <p>{@code SessionCreationPolicy.STATELESS}: no {@code JSESSIONID}, no server
 * session. The session lives in the JWT and in Redis. A servlet session created
 * as a side effect would be a second, longer-lived way to be authenticated that
 * A-025's idle and absolute timeouts know nothing about.
 */
@Configuration
public class SecurityConfig {

    /** The six operations the contract marks {@code security: []}. */
    static final String[] PUBLIC_API_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/webhooks/email/**",
    };

    static final String[] PUBLIC_INFRA_PATHS = {
            "/ws/**",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
    };

    /**
     * Public only while springdoc is switched on — see the class javadoc.
     * Separated from {@link #PUBLIC_INFRA_PATHS} rather than filtered out of it,
     * so that "what is permanently public" and "what is public in a documented
     * environment" are two lists a reader can tell apart.
     */
    static final String[] API_DOCS_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
    };

    /**
     * Camera, microphone, geolocation and the rest: a ticketing tool asks for
     * none of them, so the honest policy is to surrender the capability rather
     * than to rely on never calling the API. It also means a compromised
     * dependency cannot prompt a user for their webcam under our origin's name.
     */
    private static final String PERMISSIONS_POLICY = String.join(", ",
            "accelerometer=()", "autoplay=()", "camera=()", "display-capture=()",
            "encrypted-media=()", "fullscreen=(self)", "geolocation=()", "gyroscope=()",
            "magnetometer=()", "microphone=()", "midi=()", "payment=()",
            "picture-in-picture=()", "usb=()", "xr-spatial-tracking=()");

    private final boolean apiDocsEnabled;

    public SecurityConfig(@Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) {
        this.apiDocsEnabled = apiDocsEnabled;
    }

    @Bean
    SecurityFilterChain apiSecurityChain(HttpSecurity http,
                                         ProblemErrorResponses problems,
                                         JwtAuthoritiesConverter authorities,
                                         ContentSecurityPolicy csp,
                                         CsrfTokenRepository csrfTokens,
                                         CsrfTokenRequestHandler csrfTokenRequests,
                                         RequestMatcher cookieAuthenticatedRoutes) throws Exception {
        return http
                // A-074. Enabled for the two cookie-authenticated routes and no
                // others — CookieRouteCsrf explains both halves of that.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfTokenRequests)
                        .requireCsrfProtectionMatcher(cookieAuthenticatedRoutes))

                // A-074. CSP is computed from what this deployment actually
                // serves; the rest are fixed. Spring's defaults already covered
                // frameOptions, contentTypeOptions and HSTS — restated here so
                // the whole header set is readable in one place rather than half
                // here and half in a framework default somebody has to know
                // about.
                .headers(headers -> headers
                        .contentSecurityPolicy(policy -> policy.policyDirectives(csp.policyDirectives()))
                        // Belt and braces with the CSP's frame-ancestors 'none':
                        // the header is what older browsers understand and costs
                        // nothing to keep.
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(withDefaults())
                        // Two years, subdomains included. Emitted only over
                        // HTTPS, so local development on http is unaffected;
                        // preload is deliberately NOT requested — it is a
                        // submission to a browser-vendor list that is slow and
                        // painful to undo, and that is A-075's call to make with
                        // a domain in hand.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(Duration.ofDays(730).toSeconds()))
                        // A ticket URL carries a ticket code, and a full Referer
                        // would leak it to any third-party host a user reaches
                        // from the app. Origin-only when crossing origins, full
                        // path within our own.
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                        // same-origin rather than same-site: EduTrack shares
                        // nothing with a sibling subdomain, which is the same
                        // assumption CookieRouteCsrf declines to make about
                        // SameSite.
                        .crossOriginOpenerPolicy(coop -> coop
                                .policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .crossOriginResourcePolicy(corp -> corp
                                .policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN)))

                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Spring's defaults answer 401 with an empty body; problemTypes.ts
                // branches on the `type` URI and would have nothing to read.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))

                .authorizeHttpRequests(auth -> {
                    // CORS preflight carries no credentials and must never be
                    // challenged; a 401 here fails the real request that follows
                    // with an error naming CORS rather than authentication.
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers(PUBLIC_API_PATHS).permitAll()
                            .requestMatchers(PUBLIC_INFRA_PATHS).permitAll();

                    // A-074. Permitted only where the documentation is switched
                    // on. Where it is not, these answer 404: they are not under
                    // /api/**, so the authenticated rule below never reaches
                    // them, and they fall to anyRequest().permitAll() and then
                    // find no handler, because springdoc registered none.
                    // SpaResourceConfig lists v3/api-docs and swagger-ui among
                    // its backend prefixes, so they are refused honestly rather
                    // than answered with the SPA shell — a 200 carrying
                    // index.html is what a scanner would read as "documentation
                    // present". ApiDocsClosedIT pins the 404 on all five paths.
                    //
                    // A statement rather than a ternary over an empty array:
                    // requestMatchers rejects an empty pattern list, so the
                    // "documentation off" case would fail at startup — the one
                    // configuration nobody runs locally.
                    if (apiDocsEnabled) {
                        auth.requestMatchers(API_DOCS_PATHS).permitAll();
                    }

                    // Ordered after the public list: first match wins, so this
                    // closes everything the lines above did not open. New
                    // endpoints are therefore protected by default rather than
                    // by somebody remembering to add them.
                    auth.requestMatchers("/api/**", "/actuator/**").authenticated()
                            // The SPA shell and its client-side routes.
                            .anyRequest().permitAll();
                })

                // Consumes the single JwtDecoder bean, which carries the signature,
                // issuer, expiry and A-025 revocation checks together.
                //
                // The entry point is set HERE as well as in exceptionHandling, and
                // both are needed. oauth2ResourceServer installs its own
                // BearerTokenAuthenticationEntryPoint which takes precedence for
                // any failure the bearer filter itself raises — so without this
                // line, "no token" produced problem+json while "bad token"
                // produced an empty body and a WWW-Authenticate header carrying
                // error_description. That is two refusal shapes instead of one,
                // and the second names which check failed, which is the
                // enumeration hint AccessTokenVerifier flattens precisely to
                // avoid. Caught by refusalsAreIndistinguishable.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        // A-033. Set explicitly rather than left to the default:
                        // the stock converter reads `scope`/`scp`, which an
                        // EduTrack token does not carry, so every principal
                        // reached the controller with no authorities at all and
                        // every @PreAuthorize would have denied all six roles.
                        // See JwtAuthoritiesConverter — the failure is silent in
                        // the dangerous direction.
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authorities)))
                .build();
    }
}
