package com.edunext.edutrack.api.security.pan;

import com.edunext.edutrack.api.feature.audit.PanRevealAudit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A-113 · wires the PAN primitives, and refuses to start outside {@code local}
 * with the committed development keys still in place.
 *
 * <h2>The guard is the same one TotpConfig makes, for a worse case</h2>
 *
 * <p>{@code TotpConfig} refuses to boot on the committed TOTP key, reasoning
 * that "a setting that is harmless in development and unacceptable in
 * production will eventually be shipped unless something stops it". A PAN key
 * is the more damaging of the two: a TOTP secret is a second factor behind a
 * password, whereas a PAN is statutory identity for a real company, and
 * {@code ob_clients} holds one per client.
 *
 * <p><b>Not {@code @Profile("!local")}.</b> The check has to run everywhere,
 * because the case that matters is production booting with a setting only
 * {@code local} is allowed to hold.
 *
 * <h2>The bean is where A-075 changes one line</h2>
 *
 * <p>{@link #panKeySource} returns {@link ConfiguredPanKeySource} today. When
 * the vault is up, a vault-backed implementation is returned from here instead
 * and nothing else in the system changes — no column, no stored byte, no
 * re-encryption. That is the entire reason {@link PanKeySource} is an interface
 * rather than a constructor argument, and it is worth not collapsing later.
 */
@Configuration
@EnableConfigurationProperties(PanProperties.class)
class PanConfig {

    PanConfig(PanProperties properties, Environment environment) {
        if (properties.usesPlaceholderKey() && !environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "edutrack.onboarding.pan.encryption-key or blind-index-key is still the committed "
                            + "development default, which is in the repository — every client's PAN "
                            + "would be readable by anyone holding the source and a database dump, and "
                            + "the duplicate guard forgeable. Set PAN_ENCRYPTION_KEY and "
                            + "PAN_BLIND_INDEX_KEY. Active profiles: "
                            + String.join(",", environment.getActiveProfiles()) + ". Refusing to start.");
        }
    }

    /** Swap this for the vault-backed source in A-075. Nothing else moves. */
    @Bean
    PanKeySource panKeySource(PanProperties properties) {
        return new ConfiguredPanKeySource(properties);
    }

    @Bean
    PanBlindIndex panBlindIndex(PanKeySource keys) {
        return new PanBlindIndex(keys);
    }

    @Bean
    PanCipher panCipher(PanKeySource keys) {
        return new PanCipher(keys);
    }

    @Bean
    PanService panService(PanCipher cipher, PanBlindIndex blindIndex, PanRevealAudit audit) {
        return new PanService(cipher, blindIndex, audit);
    }
}
