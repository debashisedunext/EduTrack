package com.edunext.edutrack.api.feature.portal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link PortalDevCredentialProperties} and refuses to start if the
 * switch is on anywhere it should not be.
 *
 * <p>Modelled on {@code TotpConfig}, deliberately and for its exact reason: "a
 * setting that is harmless in development and unacceptable in production will
 * eventually be shipped unless something stops it". A property that puts a
 * working client password on a staff response is that kind of setting, and the
 * way it reaches production is not a decision — it is an environment file
 * copied from a demo box by somebody who did not read it.
 *
 * <p>So the property alone is not enough to turn this on: the deployment must
 * also be running one of the development profiles. Both, or the application
 * does not start — and it says which two things disagree rather than silently
 * choosing the safe one, because a demo box that quietly refuses to hand over
 * passwords is a demo that fails in front of an audience with no explanation.
 *
 * <p><b>Not {@code @Profile}.</b> The check has to run everywhere; the case
 * that matters is production booting with a setting only a dev box may hold.
 */
@Configuration
@EnableConfigurationProperties(PortalDevCredentialProperties.class)
class PortalDevCredentialConfig {

    /** Where a readable client credential is a risk about nothing. */
    private static final String DEVELOPMENT_PROFILES = "local | dev-noauth | fixtures";

    PortalDevCredentialConfig(PortalDevCredentialProperties properties, Environment environment) {
        if (properties.issuesReadablePassword() && !environment.matchesProfiles(DEVELOPMENT_PROFILES)) {
            throw new IllegalStateException(
                    "edutrack.portal.dev-credentials.enabled is true, which puts a working client "
                            + "portal password on a staff API response — see ClientAccountAdminDtos "
                            + "for why that is normally refused. It is permitted only under the "
                            + DEVELOPMENT_PROFILES + " profiles. Active profiles: "
                            + String.join(",", environment.getActiveProfiles())
                            + ". Unset the property or run a development profile. Refusing to start.");
        }
    }
}
