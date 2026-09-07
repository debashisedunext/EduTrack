package com.edunext.edutrack.api.security.pan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * A-113 · the startup guard on the two PAN keys.
 *
 * <p><b>Tested directly because the test suite deliberately supplies its own
 * keys.</b> {@code src/test/resources/application.properties} sets real ones so
 * every {@code @SpringBootTest} can build a context — which means no integration
 * test would ever exercise this refusal, and the guard could be weakened or
 * deleted without anything going red. These assertions are what keep it honest.
 *
 * <p>Modelled on {@code TotpConfigTest}, which does the same for the TOTP key,
 * and guards a worse case: a TOTP secret is a second factor behind a password,
 * a PAN is statutory identity for a real company and {@code ob_clients} holds
 * one per client.
 */
class PanConfigTest {

    private static final String REAL_A =
            Base64.getEncoder().encodeToString("a-real-aes-key-of-32-bytes-here!".getBytes(StandardCharsets.UTF_8));
    private static final String REAL_B =
            Base64.getEncoder().encodeToString("a-real-hmac-key-of-32-bytes-here".getBytes(StandardCharsets.UTF_8));

    private static MockEnvironment profile(String activeProfile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfile);
        return environment;
    }

    @Test
    @DisplayName("the committed default keys refuse to start outside local")
    void refusesPlaceholdersOutsideLocal() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PanConfig(new PanProperties(null, null), profile("production")))
                .withMessageContaining("PAN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("one real key is not enough — a placeholder in either position is refused")
    void refusesEitherPlaceholder() {
        // The likely half-done deployment: somebody sets the encryption key,
        // reads "key" as singular, and ships with a public HMAC key. That
        // leaves the duplicate guard forgeable by anyone holding the source.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PanConfig(new PanProperties(REAL_A, null), profile("production")));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PanConfig(new PanProperties(null, REAL_B), profile("production")));
    }

    @Test
    @DisplayName("the refusal names both variables, because both have to be set")
    void namesBothVariables() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PanConfig(new PanProperties(null, null), profile("staging")))
                .withMessageContaining("PAN_ENCRYPTION_KEY")
                .withMessageContaining("PAN_BLIND_INDEX_KEY");
    }

    @Test
    @DisplayName("the refusal reports the active profiles, so the message is actionable")
    void reportsActiveProfiles() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PanConfig(new PanProperties(null, null), profile("staging")))
                .withMessageContaining("staging");
    }

    @Test
    @DisplayName("local may hold the committed defaults — that is what they are for")
    void allowsPlaceholdersInLocal() {
        assertThatNoException()
                .isThrownBy(() -> new PanConfig(new PanProperties(null, null), profile("local")));
    }

    @Test
    @DisplayName("real keys start anywhere")
    void allowsRealKeysAnywhere() {
        assertThatNoException()
                .isThrownBy(() -> new PanConfig(new PanProperties(REAL_A, REAL_B), profile("production")));
    }

    @Test
    @DisplayName("no active profile is not local, so the defaults are still refused")
    void refusesWithNoProfileSet() {
        // The case a bare `java -jar` produces. "No profile" must not read as
        // "development" — that is the deployment most likely to happen by
        // accident and least likely to be noticed.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new PanConfig(new PanProperties(null, null), new MockEnvironment()));
    }
}
