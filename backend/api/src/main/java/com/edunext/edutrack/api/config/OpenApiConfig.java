package com.edunext.edutrack.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc configuration — the document served at {@code /v3/api-docs}. D-003.
 *
 * <p><b>The generated document must converge on {@code contracts/openapi.yaml}</b>,
 * the contract all four streams reviewed. Today that file is hand-authored,
 * because no controllers exist to generate from. As controllers land, this
 * emits the same shapes from the Java DTOs — and from that point the
 * <b>Bean Validation annotations on those DTOs are the single source of truth
 * for validation rules</b>. {@code @NotBlank}, {@code @Size} and
 * {@code @Pattern} become {@code required}, {@code minLength} and
 * {@code pattern} in the schema, orval turns those into Zod, and nobody writes
 * a validation rule twice. That is the whole mitigation for losing shared Zod
 * schemas when the backend stopped being TypeScript (PLAN.md §2.2, D-4).
 *
 * <p>What is set here rather than left to defaults is what a generated client
 * gets wrong when it is missing: the {@code /api/v1} server prefix, and the
 * bearer scheme applied globally so every operation is marked as requiring
 * auth rather than each controller remembering to say so.
 *
 * <p><b>Ownership note.</b> D-003 is Stream D's task, but {@code api/config/}
 * is Stream A territory (TEAM-PLAN §6) and this file sits beside
 * {@code ScaffoldSecurityConfig}. Flagged for Shivendra's sign-off on the pull
 * request rather than moved quietly. The one thing it needs from A-032: the
 * real {@link org.springframework.security.web.SecurityFilterChain} must permit
 * {@code /v3/api-docs/**} and {@code /swagger-ui/**}, or the contract check in
 * CI starts failing with a 401 that looks nothing like an auth problem.
 */
@Configuration
public class OpenApiConfig {

    /** Kept in step with {@code contracts/openapi.yaml}'s {@code info.version}. */
    private static final String API_VERSION = "1.0.0-draft";

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI eduTrackOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EduTrack API")
                        .version(API_VERSION)
                        .description("""
                                Organisation task and client ticketing platform.

                                Conventions are normative and enforced in CI — see \
                                `contracts/CONVENTIONS.md`. In short: every path under \
                                `/api/v1`; success responses wrapped in `{ data, meta }`; \
                                errors as RFC 9457 problem documents; `Idempotency-Key` on \
                                every create; `ETag`/`If-Match` on detail reads and risky \
                                writes; cursor pagination, never offset.

                                Two rules the shape itself enforces. Out-of-scope IDs return \
                                **404, not 403** — a 403 would confirm the row exists. And no \
                                mutation verb exists on `/history`, `/effort-logs` or \
                                `/audit-logs`: a correction is a new compensating entry, and \
                                the database rejects mutation independently.""")
                        .contact(new Contact().name("Stream D — Engines & Realtime"))
                        .license(new License().name("Proprietary")))
                // Relative, because the app ships as a single jar and the SPA is
                // served from the same origin. An absolute URL here would break
                // every generated client the moment the host changed.
                .servers(List.of(new Server()
                        .url("/api/v1")
                        .description("Same origin as the SPA.")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        15-minute access token. Claims: `sub`, `role`, \
                                        `permissions[]`, `projects[]`, `reportees[]`, `iat`, \
                                        `exp`, `jti`. The refresh token is a separate opaque \
                                        value in an HttpOnly cookie and is never sent here.""")))
                // Applied globally so every operation inherits it. Endpoints that
                // are genuinely public — login, refresh, the mail webhooks —
                // override with @SecurityRequirements(). Opting in per controller
                // instead means the one that forgets is silently documented as
                // public, which is the wrong way round for a security default.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * The group the TypeScript client is generated from.
     *
     * <p>Scoped to {@code /api/v1/**} so actuator, springdoc's own endpoints and
     * anything else on the classpath cannot leak into the contract and appear as
     * generated client functions nobody asked for.
     */
    @Bean
    GroupedOpenApi contractApi() {
        return GroupedOpenApi.builder()
                .group("edutrack")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
