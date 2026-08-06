package com.edunext.edutrack.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

/**
 * Serves the built React app from inside the jar, so EduTrack deploys as a
 * single artifact with no separate web server.
 *
 * <p>The frontend is compiled by {@code frontend-maven-plugin} during the Maven
 * build and copied to {@code classpath:/static/}. React Router owns client-side
 * routes, so a deep link such as {@code /tickets/CRM-26-00347} must return
 * {@code index.html} rather than 404 — the browser then resolves the route.
 *
 * <p>Backend paths are excluded explicitly. Without that, a typo'd API call
 * would return the HTML shell with status 200 instead of a 404, and the caller
 * would try to parse HTML as JSON — a genuinely confusing failure to debug.
 *
 * <p>Owned by Stream A.
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    /** Paths that belong to the backend and must never fall through to the SPA. */
    private static final List<String> BACKEND_PREFIXES =
            List.of("api/", "actuator/", "v3/api-docs", "swagger-ui", "ws/");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource requested = location.createRelative(path);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;                       // a real asset
                        }
                        if (BACKEND_PREFIXES.stream().anyMatch(path::startsWith)) {
                            return null;                            // let the backend 404 honestly
                        }
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;       // SPA route, or no frontend built
                    }
                });
    }
}
