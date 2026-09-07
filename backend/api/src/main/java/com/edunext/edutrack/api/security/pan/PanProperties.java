package com.edunext.edutrack.api.security.pan;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

/**
 * A-113 · the two keys PAN needs, and why they are two.
 *
 * <h2>Not one key with two salts</h2>
 *
 * <p>{@code V20260903_1210__ob_client_capture.sql} states the rule this record
 * exists to hold: <b>the HMAC key is not the AES key and has a different
 * lifecycle.</b> The AES key may rotate freely — re-encrypting rows as it goes,
 * because every row is independently decryptable and rewritable. Rotating the
 * HMAC key invalidates every stored blind index at once, and takes the
 * uniqueness guarantee with it, because a blind index cannot be recomputed
 * without the plaintext it was derived from.
 *
 * <p>Deriving both from one configured secret would make that distinction
 * unexpressible: an operator rotating "the PAN key" to answer an AES exposure
 * would silently destroy the duplicate-client guard, and nothing would fail
 * loudly — {@code ob_clients} would simply start accepting a second row for a
 * legal entity it already held. Two keys, two config values, two decisions.
 *
 * <h2>Base64, 32 bytes, refused otherwise</h2>
 *
 * <p>Both are required to be base64 of exactly 32 bytes rather than a
 * passphrase run through a KDF. {@code TotpSecretCipher} takes a passphrase and
 * says so, and that is right for a value an operator types; these are machine
 * secrets that a vault will supply from A-075 onwards, and accepting "any
 * string" here would mean a four-character key produces a working system that
 * looks exactly like a correct one. AES-256 and HMAC-SHA256 both want 256 bits;
 * asking for them directly is the honest interface.
 *
 * @param encryptionKey base64 of 32 bytes — AES-256-GCM, {@code pan_ciphertext}
 * @param blindIndexKey base64 of 32 bytes — HMAC-SHA256, {@code pan_blind_index}
 */
@ConfigurationProperties(prefix = "edutrack.onboarding.pan")
record PanProperties(String encryptionKey, String blindIndexKey) {

    /**
     * The committed development values. In the repository, therefore public,
     * therefore refused outside {@code local} by {@link PanConfig} — the shape
     * {@code TotpConfig} established for exactly this hazard.
     */
    static final String PLACEHOLDER_ENCRYPTION_KEY = "bG9jYWwtZGV2LW9ubHktcGFuLWFlcy1rZXktMzJieXQ=";
    static final String PLACEHOLDER_BLIND_INDEX_KEY = "bG9jYWwtZGV2LW9ubHktcGFuLWhtYWMta2V5LTMyYnk=";

    /** AES-256 and HMAC-SHA256 both take 256 bits. */
    static final int KEY_BYTES = 32;

    PanProperties {
        if (encryptionKey == null || encryptionKey.isBlank()) encryptionKey = PLACEHOLDER_ENCRYPTION_KEY;
        if (blindIndexKey == null || blindIndexKey.isBlank()) blindIndexKey = PLACEHOLDER_BLIND_INDEX_KEY;
        requireKey(encryptionKey, "edutrack.onboarding.pan.encryption-key");
        requireKey(blindIndexKey, "edutrack.onboarding.pan.blind-index-key");
        if (encryptionKey.equals(blindIndexKey)) {
            // Not pedantry. One key in both places means rotating it to answer
            // an AES exposure also silently invalidates every blind index, and
            // the failure surfaces as a duplicate-client guard that has quietly
            // stopped guarding rather than as an error anybody sees.
            throw new IllegalArgumentException(
                    "edutrack.onboarding.pan.encryption-key and blind-index-key are the same value. "
                            + "They have different lifecycles by design — the AES key may rotate freely, "
                            + "the HMAC key cannot be rotated without invalidating every stored blind "
                            + "index and with it the duplicate-PAN guard. Generate them separately.");
        }
    }

    private static void requireKey(String value, String name) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    name + " is not valid base64. Expected base64 of exactly " + KEY_BYTES
                            + " bytes; generate one with: openssl rand -base64 " + KEY_BYTES, e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    name + " decodes to " + decoded.length + " bytes; exactly " + KEY_BYTES
                            + " are required. Generate one with: openssl rand -base64 " + KEY_BYTES);
        }
    }

    boolean usesPlaceholderKey() {
        return PLACEHOLDER_ENCRYPTION_KEY.equals(encryptionKey)
                || PLACEHOLDER_BLIND_INDEX_KEY.equals(blindIndexKey);
    }
}
