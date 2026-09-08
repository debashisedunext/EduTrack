package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObClientPrereqsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B-125 · registers the fallback {@link ObPrereqGate}.
 *
 * <p>{@code @ConditionalOnMissingBean} belongs on an {@code @Bean} method,
 * not on a {@code @Component} class: on a component it is evaluated during
 * scanning, before the beans it asks about are necessarily known, and the
 * result is order-dependent. Here the condition runs after user beans are
 * defined, which is what makes "C-118 adds a bean and this backs off" a
 * reliable statement rather than a hopeful one.
 */
@Configuration
class ObPrereqGateConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObPrereqGate.class)
    ObPrereqGate obPrereqGate(ObClientPrereqsRepository headers, ObJourneyGateReader journeys) {
        return new ObPrereqGateReadOnly(headers, journeys);
    }
}
