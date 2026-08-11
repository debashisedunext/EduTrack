package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * A-029 · the startup guard on the TOTP encryption key.
 *
 * <p><b>Tested directly because the test suite deliberately supplies its own
 * key.</b> {@code src/test/resources/application.properties} sets a real one so
 * every {@code @SpringBootTest} can build a context — which means no integration
 * test would ever exercise this refusal, and the guard could be weakened or
 * deleted without anything going red. These assertions are what keep it honest.
 *
 * <p>Modelled on {@code RefreshTokenConfigTest}, which does the same for
 * {@code secure-cookie=false}.
 */
class TotpConfigTest {

    private static TotpProperties withKey(String key) {
        return new TotpProperties(null, null, null, key);
    }

    /**
     * The case that matters. The committed key is in the repository, so shipping
     * it means anyone with the source and a database dump holds every enrolled
     * user's second factor — and nothing about the running system looks wrong.
     */
    @Test
    @DisplayName("the committed default key refuses to start outside local")
    void refusesThePlaceholderKeyOutsideLocal() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TotpConfig(withKey(TotpProperties.PLACEHOLDER_KEY), production))
                .withMessageContaining("TOTP_ENCRYPTION_KEY");
    }

    /**
     * A missing key binds to the placeholder — so "unset" and "left at the
     * default" have to fail identically, or forgetting the variable entirely
     * would be the way through.
     */
    @Test
    @DisplayName("an unset key is treated as the placeholder and refused too")
    void refusesAnUnsetKeyOutsideLocal() {
        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TotpConfig(withKey(null), staging));
    }

    /**
     * No profile at all is the shape a plain {@code java -jar} takes, and it must
     * not be a way around the check — it is the most likely production shape of
     * all.
     */
    @Test
    @DisplayName("no active profile does not excuse the placeholder key")
    void refusesThePlaceholderWithNoProfile() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TotpConfig(withKey(TotpProperties.PLACEHOLDER_KEY),
                        new MockEnvironment()));
    }

    @Test
    @DisplayName("local is allowed to hold the development key")
    void allowsThePlaceholderUnderLocal() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatNoException()
                .isThrownBy(() -> new TotpConfig(withKey(TotpProperties.PLACEHOLDER_KEY), local));
    }

    @Test
    @DisplayName("a real key starts anywhere")
    void allowsARealKeyEverywhere() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThatNoException()
                .isThrownBy(() -> new TotpConfig(withKey("a-genuinely-configured-key"), production));
    }

    // ── the properties' own invariants ──────────────────────────────────────

    @Test
    @DisplayName("defaults are §10.3's: 6 digits over 30 seconds, ±1 step, 10 recovery codes")
    void defaultsMatchTheBlueprint() {
        TotpProperties properties = new TotpProperties(null, null, null, null);

        assertThat(properties.issuer()).isEqualTo("EduTrack");
        assertThat(properties.windowSteps()).isEqualTo(1);
        assertThat(properties.recoveryCodes()).isEqualTo(10);
        assertThat(properties.usesPlaceholderKey()).isTrue();
    }

    /**
     * Beyond four steps the "drift tolerance" is accepting more than four and a
     * half minutes of codes at once, which is a materially weaker second factor
     * rather than a more forgiving one.
     */
    @Test
    @DisplayName("an absurd drift window fails startup rather than silently weakening the factor")
    void refusesAnAbsurdWindow() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TotpProperties(null, 10, null, "k"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TotpProperties(null, -1, null, "k"));
    }

    /**
     * Enrolling with no way back in makes a lost phone a permanently locked
     * account — which is the failure recovery codes exist to prevent.
     */
    @Test
    @DisplayName("zero recovery codes is refused")
    void refusesZeroRecoveryCodes() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TotpProperties(null, null, 0, "k"));
    }

    @Test
    @DisplayName("a blank issuer falls back rather than producing a nameless authenticator entry")
    void blankIssuerFallsBack() {
        assertThat(new TotpProperties("   ", null, null, "k").issuer()).isEqualTo("EduTrack");
    }
}
