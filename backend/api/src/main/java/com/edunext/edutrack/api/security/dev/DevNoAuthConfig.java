package com.edunext.edutrack.api.security.dev;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * A-012 · the {@code dev-noauth} profile.
 *
 * <p>Activate BOTH profiles together — {@code local,dev-noauth}:
 *
 * <pre>./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local,dev-noauth</pre>
 *
 * <p>{@code local} alone injects nothing. Adding {@code dev-noauth} on top
 * injects the configurable fake principal from {@link DevNoAuthProperties},
 * so code that reads {@code Authentication.getPrincipal()} has something
 * realistic to work against before A-020's real login exists.
 *
 * <p><b>Safety.</b> The constructor refuses to complete — and therefore
 * refuses to let the application start — unless {@code local} is also
 * active. An unauthenticated fake principal reaching a real environment is
 * exactly the hole TEAM-PLAN.md §4 warns against ("the profile is rejected
 * at startup if the environment is not local"), so the rule is enforced by
 * code, not deployment discipline: bean construction fails → context
 * refresh fails → {@code SpringApplication.run()} aborts.
 *
 * <p><b>CI.</b> {@code @Profile("dev-noauth")} keeps this class — and every
 * bean it defines — out of any run that does not explicitly opt in. CI
 * activates no profiles, so none of this exists there.
 *
 * <p><b>Scope, honestly stated:</b> this provides <i>identity</i> ("who am
 * I?"), not <i>access control</i> ("what may I do?"). ScaffoldSecurityConfig
 * still permits every request until A-032's real chain lands. That is the
 * intended Sprint 0 state — B, C and D need the identity half.
 */
@Configuration
@Profile("dev-noauth")
@EnableConfigurationProperties(DevNoAuthProperties.class)
public class DevNoAuthConfig {

    public DevNoAuthConfig(Environment environment) {
        if (!environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "The 'dev-noauth' profile injects an unauthenticated fake principal and must never "
                            + "run outside 'local'. Activate it as: local,dev-noauth. Refusing to start.");
        }
    }

    @Bean
    public FilterRegistrationBean<DevNoAuthFilter> devNoAuthFilter(DevNoAuthProperties properties) {
        var registration = new FilterRegistrationBean<>(new DevNoAuthFilter(properties));
        // Early: before Spring Security's chain evaluates the request, so the
        // fake Authentication is already in place when anything asks for it.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
