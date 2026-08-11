package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A-028 · binds {@code edutrack.auth.password-policy.*}. Blueprint §10.3.
 *
 * <p>Defaults repeated in the compact constructor so a
 * {@code new PasswordPolicyProperties(null, null, null)} in a test gets the
 * production shape, matching {@link RefreshTokenProperties} and
 * {@link PasswordResetProperties}.
 *
 * <h2>What is configurable here, and what deliberately is not</h2>
 *
 * <p><b>The composition rules are not.</b> Upper, lower, digit and symbol are
 * fixed in {@link ValidPassword}, for the reason {@code PasswordHashing} fixes
 * the Argon2id parameters: a knob marked "require a symbol" is a knob somebody
 * turns off during an incident caused by it being on, and the incident is
 * always "a user cannot pick the password they wanted". The length bounds are
 * likewise fixed, because they are the contract's {@code Password} schema and
 * changing them silently desynchronises the generated client.
 *
 * <p><b>The two things here are configurable because the blueprint says they
 * are</b> — §10.3 calls the expiry "optional" — and because both are policies an
 * organisation legitimately differs on, rather than security floors.
 *
 * @param historyDepth how many previous passwords may not be reused. §10.3 says
 *                     three. Configurable because the <i>cost</i> is linear in
 *                     this number — each one is an Argon2id verification at
 *                     64 MB — so an installation that wants ten should be able
 *                     to have ten and pay for it knowingly. Zero disables the
 *                     check entirely, which is the honest way to express "we do
 *                     not want this" rather than leaving dead configuration.
 * @param expiryEnabled whether a password goes stale at all.
 *                      <p><b>Defaults to false, and that is a deliberate
 *                      recommendation rather than laziness.</b> The blueprint
 *                      lists expiry as optional, and current guidance — NIST
 *                      SP 800-63B §5.1.1.2 — advises against scheduled rotation
 *                      precisely because it does not work: people respond to a
 *                      forced change by incrementing a digit, so the
 *                      organisation ends up with predictable passwords and a
 *                      quarterly support spike, having traded real entropy for
 *                      the appearance of hygiene. Rotation earns its place when
 *                      there is evidence of compromise, and for that case
 *                      A-026's {@code must_change_password} already exists and
 *                      is set deliberately rather than by a calendar.
 *                      <p>Built rather than skipped because the blueprint asks
 *                      for it and some clients' compliance regimes require it;
 *                      switched off because nothing should acquire the
 *                      behaviour by accident.
 * @param maxAge       how long a password stays fresh once {@code expiryEnabled}
 *                     is true. §10.3's ninety days.
 */
@ConfigurationProperties(prefix = "edutrack.auth.password-policy")
record PasswordPolicyProperties(
        Integer historyDepth,
        Boolean expiryEnabled,
        Duration maxAge
) {
    PasswordPolicyProperties {
        if (historyDepth == null) historyDepth = 3;
        if (expiryEnabled == null) expiryEnabled = false;
        if (maxAge == null) maxAge = Duration.ofDays(90);

        if (historyDepth < 0) {
            throw new IllegalArgumentException(
                    "edutrack.auth.password-policy.history-depth cannot be negative; "
                            + "use 0 to disable the no-reuse check");
        }
        if (!maxAge.isPositive()) {
            throw new IllegalArgumentException(
                    "edutrack.auth.password-policy.max-age must be positive — a zero or negative "
                            + "age expires every password the moment it is set, locking every user "
                            + "into an unbreakable change-password loop");
        }
    }

    /** True when the no-reuse rule is switched on at all. */
    boolean historyEnforced() {
        return historyDepth > 0;
    }
}
