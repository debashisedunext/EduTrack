package com.edunext.edutrack.domain.onboarding.outbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObOutboxJsonTest {

    @Test
    void roundTripsWhatATemplateNeeds() {
        Map<String, Object> payload = Map.of(
                "clientName", "Horizon Academy",
                "dueOn", "2026-09-10",
                "daysLate", 3,
                "mandatory", true,
                "items", List.of("SSO", "Data migration"),
                "owner", Map.of("name", "Asha", "email", "asha@example.com"));

        Map<String, Object> back = ObOutboxJson.read(ObOutboxJson.write(payload));

        assertThat(back).containsEntry("clientName", "Horizon Academy")
                .containsEntry("dueOn", "2026-09-10")
                .containsEntry("daysLate", 3)
                .containsEntry("mandatory", true)
                .containsEntry("items", List.of("SSO", "Data migration"))
                .containsEntry("owner", Map.of("name", "Asha", "email", "asha@example.com"));
    }

    @Test
    void nullAndBlankReadAsEmpty() {
        assertThat(ObOutboxJson.write(null)).isEqualTo("{}");
        assertThat(ObOutboxJson.read(null)).isEmpty();
        assertThat(ObOutboxJson.read("  ")).isEmpty();
        assertThat(ObOutboxJson.read("{}")).isEmpty();
    }

    @Test
    void aTopLevelArrayReadsAsEmptyRatherThanThrowing() {
        assertThat(ObOutboxJson.read("[1, 2]")).isEmpty();
    }

    @Test
    void textThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> ObOutboxJson.read("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
