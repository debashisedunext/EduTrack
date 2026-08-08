package com.edunext.edutrack.api.security.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-022 · a secret too short for HS256 must stop the application, not the
 * first login.
 */
class JwtEncoderConfigTest {

    private final JwtEncoderConfig config = new JwtEncoderConfig();

    @Test
    @DisplayName("a secret under 32 bytes refuses to build the encoder")
    void refusesAShortSecret() {
        JwtProperties tooShort = new JwtProperties("only-16-bytes-ok", null, Duration.ofMinutes(15));

        assertThatThrownBy(() -> config.jwtEncoder(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SIGNING_SECRET");
    }

    @Test
    @DisplayName("the committed local default is long enough to sign with")
    void acceptsASecretOfSufficientLength() {
        JwtProperties valid = new JwtProperties(
                "local-dev-only-jwt-signing-secret-change-me", null, Duration.ofMinutes(15));

        assertThat(config.jwtEncoder(valid)).isNotNull();
    }
}
