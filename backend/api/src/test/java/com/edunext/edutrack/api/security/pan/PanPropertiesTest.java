package com.edunext.edutrack.api.security.pan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A-113 · the key validation, which exists so that a weak or duplicated key
 * fails at startup rather than producing a system that looks correct.
 */
class PanPropertiesTest {

    private static final String VALID_A =
            Base64.getEncoder().encodeToString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8));
    private static final String VALID_B =
            Base64.getEncoder().encodeToString("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("accepts two distinct base64 32-byte keys")
    void acceptsWellFormedKeys() {
        PanProperties properties = new PanProperties(VALID_A, VALID_B);

        assertThat(properties.encryptionKey()).isEqualTo(VALID_A);
        assertThat(properties.blindIndexKey()).isEqualTo(VALID_B);
        assertThat(properties.usesPlaceholderKey()).isFalse();
    }

    @Test
    @DisplayName("falls back to the committed development keys, and admits it")
    void defaultsToPlaceholders() {
        PanProperties properties = new PanProperties(null, null);

        assertThat(properties.usesPlaceholderKey()).isTrue();
        assertThat(properties.encryptionKey()).isNotEqualTo(properties.blindIndexKey());
    }

    @Test
    @DisplayName("the committed defaults are themselves valid keys")
    void placeholdersAreWellFormed() {
        // Otherwise `local` would not start, and the failure would look like a
        // bug in the validation rather than in the default.
        assertThat(Base64.getDecoder().decode(PanProperties.PLACEHOLDER_ENCRYPTION_KEY))
                .hasSize(PanProperties.KEY_BYTES);
        assertThat(Base64.getDecoder().decode(PanProperties.PLACEHOLDER_BLIND_INDEX_KEY))
                .hasSize(PanProperties.KEY_BYTES);
    }

    @Test
    @DisplayName("one placeholder among two real keys still counts as a placeholder")
    void detectsEitherPlaceholder() {
        assertThat(new PanProperties(VALID_A, null).usesPlaceholderKey()).isTrue();
        assertThat(new PanProperties(null, VALID_B).usesPlaceholderKey()).isTrue();
    }

    @Test
    @DisplayName("refuses a key that is not base64")
    void refusesNonBase64() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PanProperties("not base64 at all!", VALID_B))
                .withMessageContaining("not valid base64");
    }

    @Test
    @DisplayName("refuses a key of the wrong length, and says what it got")
    void refusesShortKey() {
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));

        // Accepting "any string" is how a four-character key produces a working
        // system indistinguishable from a correct one.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PanProperties(tooShort, VALID_B))
                .withMessageContaining("decodes to 5 bytes");
    }

    @Test
    @DisplayName("refuses one key used for both purposes")
    void refusesIdenticalKeys() {
        // The two have different lifecycles by design: the AES key may rotate
        // freely, the HMAC key cannot be rotated without invalidating every
        // stored blind index. One value in both places makes that distinction
        // unexpressible, and the damage silent — the duplicate guard stops
        // guarding and nothing errors.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PanProperties(VALID_A, VALID_A))
                .withMessageContaining("different lifecycles");
    }

    @Test
    @DisplayName("names the offending property, both times")
    void namesTheProperty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PanProperties("!!!", VALID_B))
                .withMessageContaining("edutrack.onboarding.pan.encryption-key");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PanProperties(VALID_A, "!!!"))
                .withMessageContaining("edutrack.onboarding.pan.blind-index-key");
    }
}
