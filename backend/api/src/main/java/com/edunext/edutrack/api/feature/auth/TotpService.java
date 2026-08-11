package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * A-029 · two-factor enrolment and verification. Blueprint §10.1 and screen
 * S-04.
 *
 * <h2>Enrolment is two steps, and that is the point</h2>
 *
 * <p>{@link #beginEnrolment} generates a secret and hands back an
 * {@code otpauth://} URI; it does <b>not</b> turn 2FA on.
 * {@link #confirmEnrolment} does, and only when the user has echoed back a code
 * their authenticator produced.
 *
 * <p>Collapsing these into one call is the obvious simplification and it locks
 * people out. A QR that did not scan, a phone with a wrong clock, a closed tab
 * — each leaves an account demanding codes that nothing can generate, and the
 * account is the one the user was in the middle of protecting. Requiring proof
 * before enabling costs one extra request and removes the entire failure mode.
 *
 * <h2>Verification is deliberately not just "does the code match"</h2>
 *
 * <p>{@link #verifyForLogin} runs three checks in order, and each exists for a
 * reason the others do not cover:
 *
 * <ol>
 *   <li><b>The TOTP code</b>, within the configured drift window.</li>
 *   <li><b>The replay guard</b> — a matching code is refused if its time step
 *       has already been spent. Without this a code observed in a phishing
 *       proxy or over a shoulder stays usable for the rest of its window, which
 *       is the attack a second factor is supposed to stop.</li>
 *   <li><b>A recovery code</b>, if the submitted value is not a six-digit
 *       code at all.</li>
 * </ol>
 */
@Service
class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);

    private final AuthUserRepository users;
    private final TotpGenerator generator;
    private final TotpSecretCipher cipher;
    private final TotpReplayGuard replayGuard;
    private final RecoveryCodeService recoveryCodes;
    private final TotpProperties properties;

    TotpService(AuthUserRepository users,
                TotpGenerator generator,
                TotpSecretCipher cipher,
                TotpReplayGuard replayGuard,
                RecoveryCodeService recoveryCodes,
                TotpProperties properties) {
        this.users = users;
        this.generator = generator;
        this.cipher = cipher;
        this.replayGuard = replayGuard;
        this.recoveryCodes = recoveryCodes;
        this.properties = properties;
    }

    /**
     * Step one — mints a secret, stores it encrypted, and returns what the user
     * needs to add it to an authenticator.
     *
     * <p><b>Overwrites any unconfirmed secret.</b> That is what makes "the QR
     * did not scan, let me try again" work without a cancel step. It cannot
     * disturb a working enrolment, because a confirmed account is refused
     * below — re-enrolling means disabling first, deliberately.
     *
     * @throws TwoFactorAlreadyEnabledException if 2FA is already on
     */
    @Transactional
    Enrolment beginEnrolment(long userId) {
        AuthUserRow user = requireUser(userId);

        if (user.totpEnabled()) {
            // Silently re-issuing here would let anyone holding a live access
            // token replace the second factor on the account it belongs to —
            // turning a stolen fifteen-minute token into a permanent foothold.
            // Disabling first requires the password, which that caller does not
            // have.
            throw new TwoFactorAlreadyEnabledException();
        }

        String secret = generator.newSecret();
        users.startTotpEnrolment(userId, cipher.encrypt(secret));

        log.info("auth: two-factor enrolment started for user {}", userId);
        return new Enrolment(secret, otpauthUri(user.username(), secret));
    }

    /**
     * Step two — the user proves they can read codes from the secret, and 2FA
     * turns on.
     *
     * <p>Recovery codes are issued <b>here</b> rather than at setup: codes handed
     * out for an enrolment that was never completed are a set of live credentials
     * for an account with no second factor, which is strictly worse than none.
     *
     * @return the recovery codes, in plaintext, for the one and only time they
     *         can be shown
     * @throws TwoFactorNotEnrolledException if no unconfirmed secret exists
     * @throws InvalidTotpCodeException      if the code does not verify
     */
    @Transactional
    List<String> confirmEnrolment(long userId, String submittedCode) {
        AuthUserRow user = requireUser(userId);

        if (user.totpEnabled()) {
            throw new TwoFactorAlreadyEnabledException();
        }
        if (user.totpSecret() == null) {
            throw new TwoFactorNotEnrolledException();
        }

        String secret = cipher.decrypt(user.totpSecret());
        if (generator.verify(secret, submittedCode, Instant.now(), properties.windowSteps()).isEmpty()) {
            log.info("auth: two-factor confirmation refused for user {} — code did not verify", userId);
            throw new InvalidTotpCodeException();
        }

        if (!users.confirmTotp(userId)) {
            // The secret was cleared between the read and this write — a disable
            // racing a confirm. Refused rather than retried: enabling 2FA against
            // a secret that no longer exists would demand codes nothing can
            // generate.
            throw new TwoFactorNotEnrolledException();
        }

        List<String> codes = recoveryCodes.regenerateFor(userId);
        log.info("auth: two-factor enabled for user {}", userId);
        return codes;
    }

    /**
     * Turns 2FA off and clears the secret.
     *
     * <p>The <b>caller must already have re-proved their password</b> — see
     * {@code MeController}. Disabling a second factor is exactly what someone
     * holding a stolen access token wants to do first, and a fifteen-minute
     * token must not be enough to strip the protection it was meant to be
     * layered under.
     *
     * <p>Recovery codes go with it. Leaving them would mean re-enabling with a
     * new authenticator silently resurrects a list printed for the old one.
     */
    @Transactional
    void disable(long userId) {
        users.disableTotp(userId);
        recoveryCodes.deleteAllFor(userId);
        log.info("auth: two-factor disabled for user {}", userId);
    }

    /**
     * The login-path check. Blueprint §10.1's {@code 2FA enabled? → TOTP
     * challenge}.
     *
     * <p>Called only once the password has verified — {@link AuthenticationService}
     * owns that ordering, and it is what stops this endpoint reporting whether an
     * account exists or is protected.
     *
     * @throws TwoFactorRequiredException if the account needs a code and none came
     * @throws InvalidTotpCodeException   if the code is wrong, replayed, or an
     *                                    unrecognised recovery code
     */
    void verifyForLogin(AuthUserRow user, String submittedCode) {
        if (!user.totpEnabled()) {
            return;
        }

        if (submittedCode == null || submittedCode.isBlank()) {
            throw new TwoFactorRequiredException();
        }

        String candidate = submittedCode.trim();

        // A six-digit value is a TOTP code; anything else can only be a recovery
        // code. Deciding by shape rather than trying both against both avoids
        // spending an Argon2id verification per recovery code on every ordinary
        // login.
        if (candidate.matches("\\d{" + TotpGenerator.DIGITS + "}")) {
            verifyTotpCode(user, candidate);
            return;
        }

        if (!recoveryCodes.redeem(user.id(), candidate)) {
            log.info("auth: two-factor refused for user {} — no matching recovery code", user.id());
            throw new InvalidTotpCodeException();
        }
    }

    private void verifyTotpCode(AuthUserRow user, String candidate) {
        String secret = cipher.decrypt(user.totpSecret());
        Instant now = Instant.now();

        OptionalLong matchedStep =
                generator.verify(secret, candidate, now, properties.windowSteps());
        if (matchedStep.isEmpty()) {
            log.info("auth: two-factor refused for user {} — code did not verify", user.id());
            throw new InvalidTotpCodeException();
        }

        // The entry must outlive the window the step could still be accepted in,
        // or a code becomes replayable again the moment the guard forgets it.
        Duration ttl = Duration.ofSeconds(
                (long) TotpGenerator.PERIOD_SECONDS * (properties.windowSteps() * 2L + 2));

        if (!replayGuard.claim(user.id(), matchedStep.getAsLong(), ttl)) {
            // Same refusal as a wrong code — telling a caller "that was correct
            // but already used" confirms they hold a real code and tells them
            // their clock is right.
            throw new InvalidTotpCodeException();
        }
    }

    private AuthUserRow requireUser(long userId) {
        AuthUserRow user = users.findById(userId).orElse(null);
        if (user == null || !user.active()) {
            throw new InvalidAccessTokenException();
        }
        return user;
    }

    /**
     * The {@code otpauth://} URI every authenticator app reads, either from a QR
     * code or pasted.
     *
     * <p><b>The label is {@code Issuer:account} and the issuer is also a query
     * parameter.</b> That duplication is not an oversight — it is what the Key
     * URI specification asks for, and apps disagree about which one they read.
     * Omitting either produces entries listed under the wrong name, or as a bare
     * username with no indication which system it belongs to.
     *
     * <p><b>No QR image is generated here.</b> Rendering one server-side would
     * mean a new imaging dependency and an endpoint returning a PNG containing a
     * live secret — cacheable, loggable, and awkward to expire. The URI is the
     * data; S-04 renders it in the browser, where it never leaves the page.
     */
    private String otpauthUri(String username, String secret) {
        String issuer = properties.issuer();
        String label = encode(issuer) + ":" + encode(username);
        return "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d"
                .formatted(label, secret, encode(issuer), TotpGenerator.DIGITS,
                        TotpGenerator.PERIOD_SECONDS);
    }

    private static String encode(String value) {
        // URLEncoder is form-encoding, which turns a space into '+' — wrong in a
        // URI path segment, where it must be %20. Authenticator apps show the
        // literal '+' otherwise, in the label the user reads.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * What the user needs to enrol: the secret to type if the QR will not scan,
     * and the URI to encode as that QR.
     */
    record Enrolment(String secret, String otpauthUri) {
    }
}
