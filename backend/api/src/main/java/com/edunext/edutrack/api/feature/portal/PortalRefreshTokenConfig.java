package com.edunext.edutrack.api.feature.portal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * C-121 · enables {@link PortalRefreshTokenProperties} and enforces the one
 * setting in it that can weaken authentication — {@code RefreshTokenConfig}'s
 * own guard, mirrored for the portal's cookie rather than reusing that class,
 * which is package-private in {@code feature.auth}.
 */
@Configuration
@EnableConfigurationProperties(PortalRefreshTokenProperties.class)
class PortalRefreshTokenConfig {

    PortalRefreshTokenConfig(PortalRefreshTokenProperties properties, Environment environment) {
        if (!properties.secureCookie() && !environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "edutrack.auth.portal-refresh-token.secure-cookie=false sends the portal refresh "
                            + "cookie over plaintext and is permitted only under the 'local' profile. "
                            + "Active profiles: " + String.join(",", environment.getActiveProfiles())
                            + ". Refusing to start.");
        }
    }
}
