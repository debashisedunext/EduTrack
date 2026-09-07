package com.edunext.edutrack.api.security.pan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A-113 · the cipher and the blind index, and the three properties the schema
 * actually rests on: randomised ciphertext, deterministic index, and a tampered
 * row that refuses rather than lying.
 */
class PanCryptoTest {

    private static final String PAN = "AAAPL1234C";
    private static final String OTHER_PAN = "BBBPL9876D";

    /** A second, independent pair — for the "wrong key" cases. */
    private static final String OTHER_AES = base64Of("a-different-aes-key--32-bytes-ok");
    private static final String OTHER_HMAC = base64Of("a-different-hmac-key-32-bytes-ok");

    private final PanKeySource keys = new ConfiguredPanKeySource(defaults());
    private final PanCipher cipher = new PanCipher(keys);
    private final PanBlindIndex blindIndex = new PanBlindIndex(keys);

    private static PanProperties defaults() {
        return new PanProperties(null, null);
    }

    private static String base64Of(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != PanProperties.KEY_BYTES) {
            throw new IllegalStateException("fixture key is " + bytes.length + " bytes, need 32");
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Nested
    @DisplayName("PanCipher")
    class Cipher {

        @Test
        @DisplayName("round-trips a PAN")
        void roundTrips() {
            assertThat(cipher.decrypt(cipher.encrypt(PAN))).isEqualTo(PAN);
        }

        @Test
        @DisplayName("is randomised — the same PAN encrypts differently every time")
        void isRandomised() {
            // This is why pan_ciphertext carries no UNIQUE index. A constraint
            // over these bytes would apply cleanly, look exactly like a working
            // duplicate guard, and never once fire.
            assertThat(cipher.encrypt(PAN)).isNotEqualTo(cipher.encrypt(PAN));
        }

        @Test
        @DisplayName("both encryptions still decrypt to the same PAN")
        void randomisedButRecoverable() {
            assertThat(cipher.decrypt(cipher.encrypt(PAN)))
                    .isEqualTo(cipher.decrypt(cipher.encrypt(PAN)))
                    .isEqualTo(PAN);
        }

        @Test
        @DisplayName("fits the column: VARBINARY(255)")
        void fitsTheColumn() {
            // 12-byte nonce + 10-byte PAN + 16-byte tag = 38.
            assertThat(cipher.encrypt(PAN)).hasSize(38).hasSizeLessThan(255);
        }

        @Test
        @DisplayName("a tampered row refuses rather than decrypting to something else")
        void detectsTampering() {
            byte[] stored = cipher.encrypt(PAN);
            stored[stored.length - 1] ^= 0x01;

            // GCM is authenticated, and that matters more here than for a TOTP
            // secret: a silently altered PAN is a wrong statutory identity
            // attached to a real company, and it would propagate into the
            // duplicate guard as a false negative.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> cipher.decrypt(stored))
                    .withMessageContaining("could not be decrypted");
        }

        @Test
        @DisplayName("a changed key refuses, and says which key")
        void refusesUnderAnotherKey() {
            byte[] stored = cipher.encrypt(PAN);
            PanCipher other = new PanCipher(
                    new ConfiguredPanKeySource(new PanProperties(OTHER_AES, null)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> other.decrypt(stored))
                    .withMessageContaining("encryption-key has changed");
        }

        @Test
        @DisplayName("a value shorter than its own nonce is corrupt, not merely undecryptable")
        void refusesTruncated() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> cipher.decrypt(new byte[]{1, 2, 3}))
                    .withMessageContaining("corrupt");
        }

        @Test
        @DisplayName("no PAN appears in the failure message")
        void doesNotLeakThePanOnFailure() {
            // An exception carrying the plaintext it was protecting ends up in
            // a log file, which is the one place this class exists to keep it
            // out of.
            byte[] stored = cipher.encrypt(PAN);
            stored[0] ^= 0x01;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> cipher.decrypt(stored))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(PAN));
        }
    }

    @Nested
    @DisplayName("PanBlindIndex")
    class BlindIndex {

        @Test
        @DisplayName("is deterministic — this is what the UNIQUE constraint rests on")
        void isDeterministic() {
            assertThat(blindIndex.of(PAN)).isEqualTo(blindIndex.of(PAN));
        }

        @Test
        @DisplayName("is exactly 32 bytes, for BINARY(32)")
        void isThirtyTwoBytes() {
            assertThat(blindIndex.of(PAN)).hasSize(32);
        }

        @Test
        @DisplayName("normalises first, so case and spacing cannot defeat the duplicate guard")
        void normalisesBeforeHashing() {
            // Without this, a second row typed in lower case hashes differently,
            // the UNIQUE index does not fire, and the client is onboarded twice
            // — the exact defect plan §1.1 item 6 exists to prevent.
            assertThat(blindIndex.of("  aaapl1234c  ")).isEqualTo(blindIndex.of(PAN));
        }

        @Test
        @DisplayName("different PANs give different indices")
        void separatesDistinctPans() {
            assertThat(blindIndex.of(PAN)).isNotEqualTo(blindIndex.of(OTHER_PAN));
        }

        @Test
        @DisplayName("stores none of the PAN it was derived from")
        void carriesNoPlaintext() {
            // Weak as a proof of one-wayness — that rests on HMAC-SHA256, not on
            // an assertion here — but it does pin the mistake worth pinning: a
            // "blind index" implemented as the PAN bytes, or as a reversible
            // encoding of them, would satisfy determinism and uniqueness and
            // fail only this.
            assertThat(blindIndex.of(PAN))
                    .isNotEqualTo(PAN.getBytes(StandardCharsets.UTF_8))
                    .isNotEqualTo(Base64.getEncoder().encode(PAN.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("a different HMAC key gives a different index — hence the rotation warning")
        void dependsOnItsOwnKey() {
            PanBlindIndex other = new PanBlindIndex(
                    new ConfiguredPanKeySource(new PanProperties(null, OTHER_HMAC)));

            // Rotating this key invalidates every stored index at once. The
            // assertion is here so the consequence is visible in a test rather
            // than only in a comment.
            assertThat(other.of(PAN)).isNotEqualTo(blindIndex.of(PAN));
        }

        @Test
        @DisplayName("the two keys are not interchangeable")
        void isNotTheEncryptionKey() {
            // Computing a blind index under the AES key would produce an index
            // that collides with nothing and silently disables the duplicate
            // guard, on a system that still runs.
            assertThat(keys.blindIndexKey().getEncoded())
                    .isNotEqualTo(keys.encryptionKey().getEncoded());
        }

        @Test
        @DisplayName("a blank PAN has no index")
        void refusesBlank() {
            assertThatIllegalArgumentException().isThrownBy(() -> blindIndex.of("  "));
            assertThatIllegalArgumentException().isThrownBy(() -> blindIndex.of(null));
        }
    }
}
