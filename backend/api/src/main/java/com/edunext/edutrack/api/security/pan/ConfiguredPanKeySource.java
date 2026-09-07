package com.edunext.edutrack.api.security.pan;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * A-113 · the {@link PanKeySource} that reads configuration, which is what
 * exists until A-075's vault does.
 *
 * <p>Modelled on {@code TotpSecretCipher}'s arrangement — key in configuration,
 * supplied by the environment outside {@code local}, committed default refused
 * everywhere else by {@link PanConfig}. That precedent is load-bearing rather
 * than decorative: it is already the answer this codebase gives to "a secret
 * that must be reversible and must not be in the repository", and a second,
 * different answer for PAN would mean two key paths to audit instead of one.
 *
 * <p>The keys are decoded once at construction. {@link PanProperties} has
 * already refused anything that is not base64 of exactly 32 bytes, so there is
 * no per-call validation and no per-call decode.
 */
final class ConfiguredPanKeySource implements PanKeySource {

    private final SecretKey encryption;
    private final SecretKey blindIndex;

    ConfiguredPanKeySource(PanProperties properties) {
        this.encryption = key(properties.encryptionKey(), "AES");
        this.blindIndex = key(properties.blindIndexKey(), "HmacSHA256");
    }

    @Override
    public SecretKey encryptionKey() {
        return encryption;
    }

    @Override
    public SecretKey blindIndexKey() {
        return blindIndex;
    }

    private static SecretKey key(String base64, String algorithm) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64), algorithm);
    }
}
