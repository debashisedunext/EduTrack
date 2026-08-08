package com.edunext.edutrack.api.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A-022 · binds {@code edutrack.jwt.*}. Defaults live in application.yml; the
 * secret is the one value every non-local environment must override, the same
 * way {@code DB_PASSWORD} and {@code EMAIL_WEBHOOK_SECRET} are.
 *
 * <p>The compact constructor only fills in {@code issuer} and
 * {@code accessTokenTtl} — {@code secret} has no safe default to fall back to
 * here, that decision belongs to application.yml (or a real deployment's
 * environment), not to code.
 *
 * @param issuer the {@code iss} claim. A URL, because Spring's
 *               {@code Jwt.getIssuer()} parses it as one — a bare word is
 *               written happily and then throws on read.
 */
@ConfigurationProperties(prefix = "edutrack.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {
    public JwtProperties {
        if (issuer == null) issuer = "https://edutrack";
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
    }
}
