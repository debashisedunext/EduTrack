package com.edunext.edutrack.worker.onboarding.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObOutboxPropertiesTest {

    private static ObOutboxProperties props(int maxAttempts, Duration base, Duration cap) {
        return new ObOutboxProperties(true, Duration.ofSeconds(5), 10, Duration.ofMinutes(2),
                maxAttempts, base, cap);
    }

    @Test
    void backoffDoublesFromTheBaseAndIsCapped() {
        ObOutboxProperties p = props(4, Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThat(p.backoffFor(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.backoffFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.backoffFor(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(p.backoffFor(4)).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.backoffFor(40)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void zeroOrNegativeAttemptsCountAsTheFirst() {
        ObOutboxProperties p = props(4, Duration.ofMinutes(1), Duration.ofHours(1));

        assertThat(p.backoffFor(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.backoffFor(-3)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void refusesAConfigurationThatCannotWork() {
        assertThatThrownBy(() -> new ObOutboxProperties(true, Duration.ofSeconds(5), 0,
                Duration.ofMinutes(2), 4, Duration.ofMinutes(1), Duration.ofHours(1)))
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new ObOutboxProperties(true, Duration.ofSeconds(5), 10,
                Duration.ofMinutes(2), 0, Duration.ofMinutes(1), Duration.ofHours(1)))
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new ObOutboxProperties(true, Duration.ofSeconds(5), 10,
                Duration.ZERO, 4, Duration.ofMinutes(1), Duration.ofHours(1)))
                .hasMessageContaining("lease");
        assertThatThrownBy(() -> new ObOutboxProperties(true, Duration.ofSeconds(5), 10,
                Duration.ofMinutes(2), 4, Duration.ofMinutes(-1), Duration.ofHours(1)))
                .hasMessageContaining("backoff-base");
        assertThatThrownBy(() -> new ObOutboxProperties(true, Duration.ofSeconds(5), 10,
                Duration.ofMinutes(2), 4, Duration.ofMinutes(10), Duration.ofMinutes(1)))
                .hasMessageContaining("backoff-cap");
    }
}
