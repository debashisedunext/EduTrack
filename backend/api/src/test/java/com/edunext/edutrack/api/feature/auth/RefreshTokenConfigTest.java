package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-023 · the one setting that can weaken the refresh cookie must not be
 * settable outside {@code local}.
 *
 * <p>Dropping {@code Secure} produces no error, no log line and no failing
 * request — it just puts a seven-day session credential on the wire in
 * plaintext, where any intermediary can lift it. A defect with no symptom
 * survives indefinitely, so it is caught at boot instead. Same posture as
 * {@code DevNoAuthConfig} and {@code JwtEncoderConfig}.
 */
class RefreshTokenConfigTest {

    private static RefreshTokenProperties withSecure(boolean secure) {
        return new RefreshTokenProperties(null, null, null, secure);
    }

    @Test
    @DisplayName("secure-cookie=false refuses to start when 'local' is not active")
    void insecureCookieOutsideLocalRefusesToStart() {
        MockEnvironment production = new MockEnvironment().withProperty("x", "y");

        assertThatThrownBy(() -> new RefreshTokenConfig(withSecure(false), production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start");
    }

    @Test
    @DisplayName("secure-cookie=false is permitted under 'local'")
    void insecureCookieIsAllowedLocally() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatCode(() -> new RefreshTokenConfig(withSecure(false), local))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the default is secure, and starts anywhere")
    void theSecureDefaultStartsEverywhere() {
        assertThatCode(() -> new RefreshTokenConfig(withSecure(true), new MockEnvironment()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new RefreshTokenConfig(
                new RefreshTokenProperties(null, null, null, null), new MockEnvironment()))
                .as("an unset property must default to secure, not to convenient")
                .doesNotThrowAnyException();
    }
}
