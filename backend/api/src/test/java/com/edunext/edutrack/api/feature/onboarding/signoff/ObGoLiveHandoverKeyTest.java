package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * B-118 · {@code onboarding/go-live-handover/{obClientId}/handover.pdf} —
 * deterministic on purpose, unlike {@code ObSignoffCertificateKey}'s
 * unpredictable one. See the class javadoc for why.
 */
class ObGoLiveHandoverKeyTest {

    @Test
    @DisplayName("mint produces the deterministic path for the client")
    void mintProducesTheDeterministicPath() {
        ObGoLiveHandoverKey key = ObGoLiveHandoverKey.mint(42L);

        assertThat(key.value()).isEqualTo("onboarding/go-live-handover/42/handover.pdf");
    }

    @Test
    @DisplayName("two mints for the same client produce the exact same key — regeneration, not collision")
    void mintIsDeterministic() {
        assertThat(ObGoLiveHandoverKey.mint(42L)).isEqualTo(ObGoLiveHandoverKey.mint(42L));
        assertThat(ObGoLiveHandoverKey.mint(42L).value()).isEqualTo(ObGoLiveHandoverKey.mint(42L).value());
    }

    @Test
    @DisplayName("two different clients never share a key")
    void differentClientsDoNotCollide() {
        assertThat(ObGoLiveHandoverKey.mint(42L).value()).isNotEqualTo(ObGoLiveHandoverKey.mint(43L).value());
    }

    @Test
    @DisplayName("refuses a non-positive client id")
    void refusesNonPositiveId() {
        assertThatIllegalArgumentException().isThrownBy(() -> ObGoLiveHandoverKey.mint(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> ObGoLiveHandoverKey.mint(-1L));
    }
}
