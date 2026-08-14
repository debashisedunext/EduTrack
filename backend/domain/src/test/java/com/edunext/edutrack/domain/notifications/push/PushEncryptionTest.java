package com.edunext.edutrack.domain.notifications.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-045 · the encryption, against RFC 8291's own worked example.
 *
 * <p><strong>This test is the reason the encryption is written rather than
 * imported.</strong> Composing ECDH, HKDF and AES-GCM by hand is only
 * defensible if the result is checked against vectors somebody else published;
 * without that, "it produced bytes" is all anybody would know, and a push that
 * never arrives looks exactly like a browser that went away.
 *
 * <p>Vectors are RFC 8291 §5, transcribed verbatim. A match is strong evidence
 * in both directions: an implementation with a wrong info string cannot
 * accidentally reproduce 155 specific bytes.
 */
class PushEncryptionTest {

    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";

    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";

    private static final String EXPECTED_BODY =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                    + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
                    + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private final PushEncryption encryption = new PushEncryption();

    @Test
    @DisplayName("reproduces RFC 8291 §5 byte for byte")
    void matchesTheRfcVector() {
        byte[] body = encryption.encrypt(
                PLAINTEXT, decode(UA_PUBLIC), decode(AUTH_SECRET),
                decode(SALT), new BigInteger(1, decode(AS_PRIVATE)), decode(AS_PUBLIC));

        assertThat(encode(body)).isEqualTo(EXPECTED_BODY);
    }

    @Test
    @DisplayName("the header is salt, record size, key length, sender key — RFC 8188 §2.1")
    void theHeaderIsLaidOutAsTheCodingRequires() {
        byte[] body = encryption.encrypt(
                PLAINTEXT, decode(UA_PUBLIC), decode(AUTH_SECRET),
                decode(SALT), new BigInteger(1, decode(AS_PRIVATE)), decode(AS_PUBLIC));

        assertThat(java.util.Arrays.copyOfRange(body, 0, 16)).isEqualTo(decode(SALT));
        assertThat(java.nio.ByteBuffer.wrap(body, 16, 4).getInt()).isEqualTo(PushEncryption.RECORD_SIZE);
        assertThat(body[20]).as("key id length").isEqualTo((byte) 65);
        assertThat(java.util.Arrays.copyOfRange(body, 21, 86)).isEqualTo(decode(AS_PUBLIC));
    }

    @Test
    @DisplayName("a fresh ephemeral key per message, so two identical payloads differ")
    void everyMessageGetsItsOwnKeyAndSalt() {
        byte[] first = encryption.encrypt(PLAINTEXT, decode(UA_PUBLIC), decode(AUTH_SECRET));
        byte[] second = encryption.encrypt(PLAINTEXT, decode(UA_PUBLIC), decode(AUTH_SECRET));

        // Reusing a salt or an ephemeral key across messages to the same
        // browser would throw away the forward secrecy the ECDH exists for,
        // and repeat an AES-GCM nonce under one key — which leaks the XOR of
        // two plaintexts outright.
        assertThat(first).isNotEqualTo(second);
        assertThat(java.util.Arrays.copyOfRange(first, 0, 16))
                .isNotEqualTo(java.util.Arrays.copyOfRange(second, 0, 16));
    }

    @Test
    @DisplayName("an off-curve public key is refused, not multiplied")
    void anInvalidCurvePointIsRejected() {
        // The invalid-curve attack: a subscription registering a point that is
        // not on P-256 would otherwise leak bits of our ephemeral private key
        // through the resulting shared secret. BouncyCastle's decodePoint
        // validates, which is why the agreement goes through it.
        byte[] offCurve = decode(UA_PUBLIC);
        offCurve[40] ^= 0x01;

        assertThatThrownBy(() -> encryption.encrypt(PLAINTEXT, offCurve, decode(AUTH_SECRET)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a payload too large for one record is refused rather than truncated")
    void anOversizedPayloadIsRefused() {
        String huge = "x".repeat(PushEncryption.RECORD_SIZE);

        // A notification cut in half is worse than one that failed on the
        // server, where somebody can see it.
        assertThatThrownBy(() -> encryption.encrypt(huge, decode(UA_PUBLIC), decode(AUTH_SECRET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds one record");
    }

    @Test
    @DisplayName("a p256dh that is not 65 bytes never reaches the curve")
    void aWronglySizedKeyIsRefused() {
        assertThatThrownBy(() ->
                encryption.encrypt(PLAINTEXT, new byte[64], decode(AUTH_SECRET)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] decode(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
