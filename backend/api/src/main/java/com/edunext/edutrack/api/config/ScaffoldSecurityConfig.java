package com.edunext.edutrack.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security chain so the scaffold is runnable. <b>Stream A replaces
 * this in A-032/A-033.</b>
 *
 * <p>Why it exists: {@code spring-boot-starter-security} is on the classpath, and
 * with no {@link SecurityFilterChain} bean Spring Boot's default chain requires
 * authentication for every request — including {@code /} and {@code /assets/**}.
 * The packaged jar would serve 401 for its own UI, which makes the scaffold
 * impossible to run or demo.
 *
 * <p><b>This deliberately permits everything.</b> There is no authentication yet
 * — no users table, no login endpoint — so locking routes down before A-020 lands
 * would block all four developers without protecting anything.
 *
 * <p>{@link ConditionalOnMissingBean} means this bean vanishes the moment Stream A
 * defines a real {@code SecurityFilterChain}. Nobody has to remember to delete
 * it, and there is no window where two chains compete.
 *
 * <p>When A-032 writes the real chain, it must permit the static assets that
 * {@link SpaResourceConfig} serves — {@code /}, {@code /index.html},
 * {@code /assets/**}, {@code /favicon.ico} and the SPA's client-side routes —
 * or the UI will not load for authenticated users either.
 */
@Configuration
public class ScaffoldSecurityConfig {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain scaffoldChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }
}
