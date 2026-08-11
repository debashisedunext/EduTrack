package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A-023 · enables {@link RefreshTokenProperties} and enforces the one setting
 * in it that can weaken authentication.
 *
 * <p>Same posture as {@code DevNoAuthConfig} and {@code JwtEncoderConfig}: a
 * misconfiguration that makes auth less safe refuses to boot, rather than
 * running and being discovered later. Dropping {@code Secure} sends a
 * seven-day session credential over plaintext, where any intermediary on the
 * path can lift it — a defect that produces no error, no log line and no
 * failing request, and would therefore survive indefinitely.
 *
 * <p>Bean construction fails → context refresh fails → {@code
 * SpringApplication.run()} aborts, in front of whoever set the property.
 *
 * <p><b>Not {@code @Profile("!local")}.</b> The check has to be able to run
 * everywhere, because the case that matters is production booting with a
 * setting that only {@code local} is allowed to hold.
 */
@Configuration
@EnableConfigurationProperties({
        RefreshTokenProperties.class,
        SessionProperties.class,
        // A-027. Registered here rather than in a config class of its own —
        // there is nothing to validate at startup for the reset properties, and
        // a second @Configuration whose only job is one @EnableConfigurationProperties
        // is a file to maintain for no behaviour.
        PasswordResetProperties.class,
        // A-028. Same reasoning; its own invariants are enforced in its compact
        // constructor, which runs at binding time and so still fails startup
        // rather than the first password change.
        PasswordPolicyProperties.class})
class RefreshTokenConfig {

    RefreshTokenConfig(RefreshTokenProperties properties, Environment environment) {
        if (!properties.secureCookie() && !environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "edutrack.auth.refresh-token.secure-cookie=false sends the refresh cookie over "
                            + "plaintext and is permitted only under the 'local' profile. Active profiles: "
                            + String.join(",", environment.getActiveProfiles()) + ". Refusing to start.");
        }
    }
}
