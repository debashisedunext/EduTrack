package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * B-116 · {@code onboarding/signoff-certificates/{signoffId}/{uuid}} — mint,
 * parse and the membership check, on {@code ObAttachmentStorageKey}'s own
 * test shape one package over.
 */
class ObSignoffCertificateKeyTest {

    @Test
    @DisplayName("mint produces a key that parses back to the same signoff id")
    void mintRoundTrips() {
        ObSignoffCertificateKey key = ObSignoffCertificateKey.mint(77L);

        assertThat(key.value()).startsWith("onboarding/signoff-certificates/77/");
        assertThat(ObSignoffCertificateKey.parse(key.value())).isEqualTo(key);
    }

    @Test
    @DisplayName("two mints for the same sign-off never collide")
    void mintIsUnpredictable() {
        assertThat(ObSignoffCertificateKey.mint(77L).value())
                .isNotEqualTo(ObSignoffCertificateKey.mint(77L).value());
    }

    @Test
    @DisplayName("refuses a non-positive signoff id")
    void refusesNonPositiveId() {
        assertThatIllegalArgumentException().isThrownBy(() -> ObSignoffCertificateKey.mint(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> ObSignoffCertificateKey.mint(-1L));
    }

    @Test
    @DisplayName("parse refuses anything that is not this shape — never trusted, even our own output")
    void parseRefusesMalformedKeys() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObSignoffCertificateKey.parse("onboarding/signoffs/77/not-a-uuid"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObSignoffCertificateKey.parse("../../etc/passwd"));
        assertThatIllegalArgumentException().isThrownBy(() -> ObSignoffCertificateKey.parse(null));
    }

    @Test
    @DisplayName("belongsTo answers false rather than throwing for a key of the wrong sign-off")
    void belongsToChecksTheSignoffId() {
        ObSignoffCertificateKey key = ObSignoffCertificateKey.mint(77L);

        assertThat(ObSignoffCertificateKey.belongsTo(key.value(), 77L)).isTrue();
        assertThat(ObSignoffCertificateKey.belongsTo(key.value(), 78L)).isFalse();
        assertThat(ObSignoffCertificateKey.belongsTo("not a key at all", 77L)).isFalse();
    }
}
