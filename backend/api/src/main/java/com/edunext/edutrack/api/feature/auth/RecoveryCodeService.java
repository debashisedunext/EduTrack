package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A-029 · single-use recovery codes — the way back in when the authenticator is
 * gone.
 *
 * <h2>Why these exist when the blueprint does not mention them</h2>
 *
 * <p>§10.3 and S-04 describe TOTP and stop there. Shipping only that means a
 * lost, wiped or replaced phone is a <b>permanently locked account</b> whose
 * only exit is an administrator with database access — and the accounts most
 * likely to have 2FA enabled are the ones where that is most disruptive. Every
 * real TOTP deployment carries recovery codes for this reason, and adding them
 * later would mean a second migration plus a re-enrolment for everyone who
 * signed up before it.
 *
 * <h2>Hashed with Argon2id, not SHA-256</h2>
 *
 * <p>{@code Digests} hashes reset and refresh tokens with SHA-256 because those
 * are 256 bits of {@code SecureRandom} — there is no dictionary to run, so a
 * work factor would buy nothing. A recovery code is deliberately shorter: it has
 * to be readable off paper and typed by a person under stress, which caps its
 * entropy at something an offline attacker could plausibly grind. So these get
 * the password treatment.
 *
 * <p>The cost is paid once per redemption, which by definition is rare —
 * and, unlike a login, is not on anybody's hot path.
 */
@Component
class RecoveryCodeService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryCodeService.class);

    /**
     * Crockford-style base32 without {@code I}, {@code L}, {@code O} or
     * {@code U}. The first three are excluded because a code read off paper and
     * typed by hand confuses them with {@code 1} and {@code 0}; {@code U} because
     * excluding it makes accidental words far less likely.
     */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /**
     * Ten characters at 32 possibilities each is a little over 50 bits — well
     * beyond guessing online, and grouped 5-5 with a hyphen so a person can read
     * it back without losing their place.
     */
    private static final int CODE_LENGTH = 10;
    private static final int GROUP_SIZE = 5;

    private final RecoveryCodeRepository codes;
    private final PasswordEncoder passwordEncoder;
    private final TotpProperties properties;
    private final SecureRandom random = new SecureRandom();

    RecoveryCodeService(RecoveryCodeRepository codes,
                        PasswordEncoder passwordEncoder,
                        TotpProperties properties) {
        this.codes = codes;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * Replaces this user's entire set and returns the new codes in plaintext.
     *
     * <p><b>The only moment the plaintext exists.</b> The caller shows them once
     * and they are unrecoverable afterwards — which is the property that makes
     * the stored hashes worth having, and the reason the API response is the
     * only place they appear.
     *
     * <p>The previous set is deleted rather than retained. Keeping old codes
     * alive after a re-enrolment would mean a user who regenerated because they
     * believed the old list was exposed still has the exposed list working.
     */
    List<String> regenerateFor(long userId) {
        codes.deleteAllFor(userId);

        List<String> plaintext = new ArrayList<>(properties.recoveryCodes());
        for (int i = 0; i < properties.recoveryCodes(); i++) {
            String code = mint();
            plaintext.add(code);
            // Stored normalised, so redemption can compare against what the user
            // types rather than how it was displayed — see normalise().
            codes.insert(userId, passwordEncoder.encode(normalise(code)));
        }

        log.info("auth: issued {} recovery codes for user {}", plaintext.size(), userId);
        return plaintext;
    }

    /**
     * Spends one code if it matches an unused one.
     *
     * <p><b>Every stored hash is verified even after a match.</b> Returning on
     * the first hit would let a caller learn roughly where in the list their code
     * sat from how long the answer took — the same reasoning
     * {@link PasswordPolicy#enforceNotReused} applies to password history, and it
     * matters more here because this path is reachable by anyone with a stolen
     * password.
     *
     * <p>The {@code UPDATE … WHERE used_at IS NULL} that follows is what actually
     * makes a code single-use; this method only decides <i>which</i> row to try
     * to spend.
     *
     * <h2>Why this needs its own transaction</h2>
     *
     * <p>{@code REQUIRES_NEW} is load-bearing, for the two reasons
     * {@link LoginAttemptRecorder} documents and one of its own.
     *
     * <p>First, <b>the caller is read-only.</b>
     * {@code AuthenticationService.authenticate} is
     * {@code @Transactional(readOnly = true)} — A-020 made it so deliberately —
     * and MySQL refuses an {@code UPDATE} on a read-only connection. Without a
     * new transaction this method throws
     * {@code Connection is read-only} and every recovery-code login returns 500.
     * That is not hypothetical: {@code TwoFactorIT} found it exactly that way.
     *
     * <p>Second, <b>spending must survive a later failure.</b> If the login
     * proceeded to fail after this point, joining the caller's transaction would
     * roll the redemption back and leave the code usable — a code that has been
     * transmitted and may have been observed. Committing independently fails
     * closed: the worst case is a user burning one of ten codes, which is
     * recoverable, rather than an observed code staying live, which is not.
     *
     * <p>Third, {@code @Transactional} is proxy-based, so this has to be a call
     * across a bean boundary to take effect at all — which it is, from
     * {@code TotpService}.
     *
     * @return true if a code was matched and spent by this call
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean redeem(long userId, String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return false;
        }

        String candidate = normalise(submitted);
        List<RecoveryCodeRepository.StoredRecoveryCode> unused = codes.findUnused(userId);

        Long matchedId = null;
        for (RecoveryCodeRepository.StoredRecoveryCode stored : unused) {
            if (passwordEncoder.matches(candidate, stored.codeHash()) && matchedId == null) {
                matchedId = stored.id();
            }
        }

        if (matchedId == null) {
            return false;
        }

        // The row-level arbiter. A concurrent redemption of the same code that
        // already won returns false here, so exactly one caller ever spends it.
        boolean spent = codes.markUsed(matchedId, Instant.now());
        if (spent) {
            int remaining = codes.countUnused(userId);
            // Worth an INFO line: a recovery code being used means somebody has
            // lost their second factor, and a run of them means something else.
            // The code is not logged.
            log.info("auth: user {} signed in with a recovery code; {} remaining", userId, remaining);
            if (remaining == 0) {
                log.warn("auth: user {} has exhausted their recovery codes — a lost authenticator "
                        + "now means an administrator is the only way back in", userId);
            }
        }
        return spent;
    }

    int remainingFor(long userId) {
        return codes.countUnused(userId);
    }

    /**
     * Discards every code, used or not — for {@link TotpService#disable}.
     *
     * <p>Leaving them behind would mean a user who turned 2FA off because their
     * authenticator was compromised still has the recovery codes printed
     * alongside it working the moment they re-enrol.
     */
    void deleteAllFor(long userId) {
        codes.deleteAllFor(userId);
    }

    /**
     * Upper-cased with separators stripped, so {@code "a1b2c-d3e4f"},
     * {@code "A1B2CD3E4F"} and {@code "a1b2c d3e4f"} are one code.
     *
     * <p>Applied identically when storing and when redeeming — if the two ever
     * diverge, every code silently stops working, and the failure looks like the
     * user mistyping rather than like a bug.
     */
    private static String normalise(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** Grouped {@code XXXXX-XXXXX} for display; stored without the hyphen. */
    private String mint() {
        StringBuilder code = new StringBuilder(CODE_LENGTH + 1);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0 && i % GROUP_SIZE == 0) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
