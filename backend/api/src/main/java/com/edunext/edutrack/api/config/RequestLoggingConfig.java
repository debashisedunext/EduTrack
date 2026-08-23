package com.edunext.edutrack.api.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestLoggingFilter} as a servlet filter, not a Spring
 * Security filter — {@code HIGHEST_PRECEDENCE} puts it outside and ahead of
 * {@code springSecurityFilterChain}, so it also sees requests the security
 * chain rejects before they ever reach a controller (401/403/404-by-scope),
 * not just the ones that make it through.
 */
@Configuration
public class RequestLoggingConfig {

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
