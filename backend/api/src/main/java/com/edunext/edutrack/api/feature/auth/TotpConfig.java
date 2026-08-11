package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A-029 · registers {@link TotpProperties} and refuses to start outside
 * {@code local} with the committed encryption key still in place.
 *
 * <p>Modelled on {@link RefreshTokenConfig}, which does the same for
 * {@code secure-cookie=false}, and for the same reason: a setting that is
 * harmless in development and unacceptable in production will eventually be
 * shipped unless something stops it. A default encryption key is the more
 * dangerous of the two — it is in the repository, so anyone with the source and
 * a database dump holds every enrolled user's second factor, and nothing about
 * the running system looks wrong.
 *
 * <p><b>Not {@code @Profile("!local")}.</b> The check has to be able to run
 * everywhere, because the case that matters is production booting with a
 * setting only {@code local} is allowed to hold.
 */
@Configuration
@EnableConfigurationProperties(TotpProperties.class)
class TotpConfig {

    TotpConfig(TotpProperties properties, Environment environment) {
        if (properties.usesPlaceholderKey() && !environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "edutrack.auth.totp.encryption-key is still the committed development default, "
                            + "which is in the repository — every stored TOTP secret would be "
                            + "readable by anyone holding the source and a database dump. Set "
                            + "TOTP_ENCRYPTION_KEY. Active profiles: "
                            + String.join(",", environment.getActiveProfiles()) + ". Refusing to start.");
        }
    }
}
