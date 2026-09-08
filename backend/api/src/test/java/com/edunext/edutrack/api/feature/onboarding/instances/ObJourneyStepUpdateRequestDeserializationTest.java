package com.edunext.edutrack.api.feature.onboarding.instances;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-108 · {@link com.fasterxml.jackson.databind.JsonNode}-free proof that
 * {@code ObJourneyStepUpdateRequest} — a record — actually gets
 * {@code JsonNullable}'s absent-vs-explicit-null behaviour once {@link
 * JsonNullableModule} is registered, exactly as {@link
 * com.edunext.edutrack.api.config.JacksonNullableConfig} wires it in the
 * real application. Records use constructor-based deserialization rather
 * than field/setter injection, which is a different path through Jackson
 * than most of this module's worked examples — worth proving rather than
 * assuming it "just works" the way it does for a plain POJO.
 */
class ObJourneyStepUpdateRequestDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JsonNullableModule());

    @Test
    void anAbsentFieldDeserializesAsUndefined() throws Exception {
        ObJourneyStepLifecycleDtos.ObJourneyStepUpdateRequest request =
                mapper.readValue("{}", ObJourneyStepLifecycleDtos.ObJourneyStepUpdateRequest.class);

        assertThat(request.ownerUserId().isPresent()).isFalse();
        assertThat(request.backupOwnerUserId().isPresent()).isFalse();
        assertThat(request.dueAt().isPresent()).isFalse();
        assertThat(request.tatDays()).isNull();
    }

    @Test
    void anExplicitNullFieldDeserializesAsPresentWithNoValue() throws Exception {
        ObJourneyStepLifecycleDtos.ObJourneyStepUpdateRequest request = mapper.readValue(
                "{\"backupOwnerUserId\": null}", ObJourneyStepLifecycleDtos.ObJourneyStepUpdateRequest.class);

        assertThat(request.backupOwnerUserId().isPresent()).isTrue();
        assertThat(request.backupOwnerUserId().get()).isNull();
        assertThat(request.ownerUserId().isPresent()).isFalse();
    }

    @Test
    void aPresentValueDeserializesNormally() throws Exception {
        ObJourneyStepLifecycleDtos.ObJourneyStepUpdateRequest request = mapper.readValue(
                "{\"ownerUserId\": 42, \"tatDays\": 3}",
                ObJourneyStepLifecycleDtos.ObJourneyStepUpdateRequest.class);

        assertThat(request.ownerUserId().isPresent()).isTrue();
        assertThat(request.ownerUserId().get()).isEqualTo(42L);
        assertThat(request.tatDays()).isEqualTo(3);
        assertThat(request.backupOwnerUserId().isPresent()).isFalse();
    }
}
