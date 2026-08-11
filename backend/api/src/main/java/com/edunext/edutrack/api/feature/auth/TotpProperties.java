package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A-029 · binds {@code edutrack.auth.totp.*}. Blueprint §10.1, screen S-04.
 *
 * <p>Defaults repeated in the compact constructor so a
 * {@code new TotpProperties(null, null, null, null)} in a test gets the
 * production shape, matching {@link RefreshTokenProperties} and
 * {@link PasswordPolicyProperties}.
 *
 * @param issuer        the label an authenticator app shows above the code —
 *                      the {@code issuer} parameter of the {@code otpauth://}
 *                      URI. Configurable because it is the organisation's name
 *                      rather than ours, and because a user with accounts on two
 *                      EduTrack installations needs to tell the entries apart.
 * @param windowSteps   how many 30-second steps either side of now are accepted.
 *                      <p>One, and the value is a real trade rather than a
 *                      default nobody thought about. Zero is technically correct
 *                      and rejects a meaningful share of honest attempts — the
 *                      user's phone clock drifts, and there are seconds between
 *                      reading a code and pressing submit. Each extra step
 *                      multiplies how many codes are simultaneously valid, so
 *                      three would treble the guessing surface to buy tolerance
 *                      nobody needs. One covers ±30 seconds, which is what an
 *                      unsynchronised phone actually exhibits.
 * @param recoveryCodes how many single-use codes are issued at enrolment.
 *                      <p>Ten is the industry norm and the number is not
 *                      arbitrary: few enough that a user might plausibly print
 *                      them, many enough that losing a phone twice does not
 *                      exhaust them.
 * @param encryptionKey the key {@link TotpSecretCipher} uses to encrypt secrets
 *                      at rest.
 *                      <p>Follows the {@code JWT_SIGNING_SECRET} pattern
 *                      exactly: a committed placeholder that is fine for
 *                      {@code local} and CI, and an environment override that is
 *                      mandatory everywhere else — {@link TotpConfig} refuses to
 *                      start outside {@code local} if the placeholder is still
 *                      in place. Without that guard someone eventually ships the
 *                      default, and a default key is the same as no encryption:
 *                      anyone with the source and a database dump holds every
 *                      user's second factor.
 */
@ConfigurationProperties(prefix = "edutrack.auth.totp")
record TotpProperties(
        String issuer,
        Integer windowSteps,
        Integer recoveryCodes,
        String encryptionKey
) {

    /**
     * The committed default. Recognised by {@link TotpConfig} so that shipping it
     * outside {@code local} fails startup rather than silently encrypting every
     * secret under a key that is in the repository.
     */
    static final String PLACEHOLDER_KEY = "local-dev-only-totp-encryption-key-change-me-in-every-other-environment";

    TotpProperties {
        if (issuer == null || issuer.isBlank()) issuer = "EduTrack";
        if (windowSteps == null) windowSteps = 1;
        if (recoveryCodes == null) recoveryCodes = 10;
        if (encryptionKey == null || encryptionKey.isBlank()) encryptionKey = PLACEHOLDER_KEY;

        if (windowSteps < 0) {
            throw new IllegalArgumentException(
                    "edutrack.auth.totp.window-steps cannot be negative");
        }
        if (windowSteps > 4) {
            // Beyond this the drift tolerance stops being a tolerance: nine or
            // more codes valid at once is a materially weaker second factor, and
            // a clock that far out is a problem to fix rather than absorb.
            throw new IllegalArgumentException(
                    "edutrack.auth.totp.window-steps above 4 accepts more than 4.5 minutes of codes "
                            + "at once, which weakens the second factor rather than tolerating drift");
        }
        if (recoveryCodes < 1) {
            throw new IllegalArgumentException(
                    "edutrack.auth.totp.recovery-codes must be at least 1 — enrolling with no way "
                            + "back in makes a lost phone a permanently locked account");
        }
    }

    boolean usesPlaceholderKey() {
        return PLACEHOLDER_KEY.equals(encryptionKey);
    }
}
