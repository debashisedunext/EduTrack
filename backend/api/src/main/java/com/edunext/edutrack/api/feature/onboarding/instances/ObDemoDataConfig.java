package com.edunext.edutrack.api.feature.onboarding.instances;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link ObDemoDataProperties} and refuses to start if its switches
 * are on outside a development profile.
 *
 * <p>The same guard {@code PortalDevCredentialConfig} applies, written again
 * rather than shared because the two properties are unrelated and a common
 * "dev mode" flag is how one of them gets switched on by somebody who only
 * wanted the other.
 */
@Configuration
@EnableConfigurationProperties(ObDemoDataProperties.class)
class ObDemoDataConfig {

    private static final String DEVELOPMENT_PROFILES = "local | dev-noauth | fixtures";

    ObDemoDataConfig(ObDemoDataProperties properties, Environment environment) {
        if (properties.seedsStepDocuments() && !environment.matchesProfiles(DEVELOPMENT_PROFILES)) {
            throw new IllegalStateException(
                    "edutrack.onboarding.demo-data.pre-satisfy-step-documents is true, which attaches "
                            + "placeholder documents to every journey it instantiates — a compliance "
                            + "record asserting a client supplied paperwork nobody asked them for. It "
                            + "is permitted only under the " + DEVELOPMENT_PROFILES + " profiles. "
                            + "Active profiles: " + String.join(",", environment.getActiveProfiles())
                            + ". Unset the property or run a development profile. Refusing to start.");
        }
    }
}
