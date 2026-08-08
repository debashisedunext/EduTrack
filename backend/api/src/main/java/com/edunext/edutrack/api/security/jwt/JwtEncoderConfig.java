package com.edunext.edutrack.api.security.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * A-022 · exposes the application's single {@link JwtEncoder} bean — HS256,
 * signed with {@link JwtProperties#secret()}.
 *
 * <p>Mirrors {@code PasswordEncoderConfig}: the algorithm and key live here as
 * pure Spring wiring, and {@code AccessTokenIssuer} (feature/auth) owns turning
 * a resolved identity into the §10.1 claim set. Neither class needs to know
 * the other's concerns.
 *
 * <p><b>HS256, not RS256.</b> One process signs and — from A-032 on — verifies;
 * nothing else in this system needs to check a signature without holding the
 * secret. A shared secret is the simpler key to manage until that stops being
 * true, and switching later does not touch {@code AccessTokenIssuer}.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtEncoderConfig {

    /** RFC 7518 §3.2 — an HMAC-SHA256 key must be at least as long as the hash. */
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        byte[] secret = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);

        // Checked here rather than left to Nimbus, which raises this only when
        // it is first asked to sign — so a short secret would present as a 500
        // on every login in a deployed environment instead of a failed startup
        // in front of whoever set it. Same posture as DevNoAuthConfig: a
        // misconfiguration that weakens auth refuses to boot.
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "edutrack.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256 (got " + secret.length + "). Set JWT_SIGNING_SECRET.");
        }

        return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret, "HmacSHA256")));
    }
}
