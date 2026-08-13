package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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
 * <p><b>springdoc is permitted, and that is a deliberate temporary.</b>
 * {@code /v3/api-docs} publishes the entire API surface, which is a gift to
 * someone mapping the system. It stays open because {@code make api} advertises
 * Swagger UI and every developer uses it, and because closing it is a
 * deployment-shaped decision — public in local, closed in production — which
 * belongs with A-074's hardening rather than being smuggled in here.
 *
 * <h2>CSRF is disabled, deliberately</h2>
 *
 * <p><b>Not copied from the scaffold.</b> The access token travels in an
 * {@code Authorization} header put there by our own JavaScript, and a browser
 * never attaches headers automatically — so no bearer-authenticated route can be
 * forged from another origin, which is every route that does anything.
 *
 * <p>The one cookie-authenticated route is {@code POST /auth/refresh}. Its
 * cookie is {@code SameSite=Strict}, so a browser will not send it on a
 * cross-site request at all; and the new access token comes back in a response
 * body the attacking page cannot read. The residual risk is that somebody could
 * force a session rotation — an unexpected sign-out, not a compromise.
 *
 * <p><b>The condition that ends this reasoning:</b> the moment any endpoint
 * authenticates from a cookie alone, this is no longer a nuisance and CSRF
 * tokens stop being deferrable. Also revisit if EduTrack is ever deployed
 * alongside other applications on a shared parent domain, since {@code
 * SameSite}'s protection then depends on every sibling subdomain staying
 * uncompromised. Full CSRF tokens are A-074's, with CSP and security headers.
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
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
    };

    @Bean
    SecurityFilterChain apiSecurityChain(HttpSecurity http,
                                         ProblemErrorResponses problems,
                                         JwtAuthoritiesConverter authorities) throws Exception {
        return http
                // See the class javadoc. Disabled on purpose, with the condition
                // that would reverse the decision written down beside it.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Spring's defaults answer 401 with an empty body; problemTypes.ts
                // branches on the `type` URI and would have nothing to read.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))

                .authorizeHttpRequests(auth -> auth
                        // CORS preflight carries no credentials and must never be
                        // challenged; a 401 here fails the real request that follows
                        // with an error naming CORS rather than authentication.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_API_PATHS).permitAll()
                        .requestMatchers(PUBLIC_INFRA_PATHS).permitAll()
                        // Ordered after the public list: first match wins, so this
                        // closes everything the lines above did not open. New
                        // endpoints are therefore protected by default rather than
                        // by somebody remembering to add them.
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        // The SPA shell and its client-side routes.
                        .anyRequest().permitAll())

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
