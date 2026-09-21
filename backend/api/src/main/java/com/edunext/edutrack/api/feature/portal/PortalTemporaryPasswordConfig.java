package com.edunext.edutrack.api.feature.portal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link PortalTemporaryPasswordProperties} and refuses to start if a
 * <b>shared</b> temporary password is configured anywhere it should not be.
 *
 * <h2>The gate moved rather than went away</h2>
 *
 * <p>Its predecessor refused to start if readable passwords were enabled at all
 * outside a development profile. Issuing one is now the product's own flow, so
 * that check would refuse every deployment. What is still development-only is
 * the {@code fixed} value — one password shared by every account.
 *
 * <p>That is the part worth a startup failure, and the reason is arithmetic
 * rather than taste: with a shared value, anybody who has ever been issued a
 * portal login knows the initial password of <b>every client created after
 * them</b>, and the only thing standing between that knowledge and a session is
 * guessing a username derived from the client's own code. A per-account random
 * password has no such property, which is why it is the default and why a
 * deployment has to say the word to give it up.
 *
 * <p>Modelled on {@code TotpConfig}, and for its exact reason: "a setting that
 * is harmless in development and unacceptable in production will eventually be
 * shipped unless something stops it". The way it reaches production is not a
 * decision — it is an environment file copied from a demo box by somebody who
 * did not read it.
 *
 * <p><b>Not {@code @Profile}.</b> The check has to run everywhere; the case
 * that matters is production booting with a setting only a dev box may hold.
 */
@Configuration
@EnableConfigurationProperties(PortalTemporaryPasswordProperties.class)
class PortalTemporaryPasswordConfig {

    /** Where every client is invented and a shared password discloses nothing. */
    private static final String DEVELOPMENT_PROFILES = "local | dev-noauth | fixtures";

    PortalTemporaryPasswordConfig(PortalTemporaryPasswordProperties properties, Environment environment) {
        if (properties.hasFixedPassword() && !environment.matchesProfiles(DEVELOPMENT_PROFILES)) {
            throw new IllegalStateException(
                    "edutrack.portal.temporary-password.fixed is set, which gives every client "
                            + "the same initial portal password — so one issued login discloses "
                            + "the way into every client created after it. It is permitted only "
                            + "under the " + DEVELOPMENT_PROFILES + " profiles. Active profiles: "
                            + String.join(",", environment.getActiveProfiles())
                            + ". Unset the property to generate one password per account, which is "
                            + "the default. Refusing to start.");
        }
    }
}
