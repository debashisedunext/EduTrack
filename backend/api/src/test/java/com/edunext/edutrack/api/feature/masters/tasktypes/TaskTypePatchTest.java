package com.edunext.edutrack.api.feature.masters.tasktypes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-020 · absent is not null, and on this DTO the difference is destructive.
 *
 * <p>{@code icon} and {@code defaultSlaHrs} are the two clearable fields.
 * Written as a record first — {@code record TaskTypePatch(Optional<String> icon,
 * …)} compiles and reads better — and wrong for the reason B-017 documented on
 * {@code TeamMemberPatch}: Jackson binds a record through its canonical
 * constructor, and an <i>absent</i> {@code Optional} creator property is filled
 * with {@code Optional.empty()}, which is the same value an explicit JSON null
 * produces. A rename would have wiped the type's icon and its default SLA, and
 * the response would have looked right because it echoes what was saved.
 *
 * <p>Through a real Jackson, not by calling the setters: the claim is about how
 * the body binds, and a hand-built instance cannot be wrong about that.
 */
class TaskTypePatchTest {

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("an omitted clearable arrives as null — 'leave it alone'")
    void omittedIsNull() throws Exception {
        TaskTypeDtos.TaskTypePatch patch =
                json.readValue("{\"name\":\"Production Defect\"}", TaskTypeDtos.TaskTypePatch.class);

        assertThat(patch.name()).isEqualTo("Production Defect");
        assertThat(patch.icon()).as("omitted must not be Optional.empty()").isNull();
        assertThat(patch.defaultSlaHrs()).as("omitted must not be Optional.empty()").isNull();
    }

    @Test
    @DisplayName("an explicit null arrives as Optional.empty() — 'clear it'")
    void explicitNullIsEmpty() throws Exception {
        TaskTypeDtos.TaskTypePatch patch = json.readValue(
                "{\"icon\":null,\"defaultSlaHrs\":null}", TaskTypeDtos.TaskTypePatch.class);

        assertThat(patch.icon()).isNotNull().isEmpty();
        assertThat(patch.defaultSlaHrs()).isNotNull().isEmpty();
        assertThat(patch.name()).as("still absent").isNull();
    }

    @Test
    @DisplayName("a value arrives as a present Optional")
    void valueIsPresent() throws Exception {
        TaskTypeDtos.TaskTypePatch patch = json.readValue(
                "{\"icon\":\"flame\",\"defaultSlaHrs\":7.5}", TaskTypeDtos.TaskTypePatch.class);

        assertThat(patch.icon()).contains("flame");
        assertThat(patch.defaultSlaHrs()).contains(new BigDecimal("7.5"));
    }

    @Test
    @DisplayName("an empty body changes nothing")
    void emptyBodyChangesNothing() throws Exception {
        TaskTypeDtos.TaskTypePatch patch =
                json.readValue("{}", TaskTypeDtos.TaskTypePatch.class);

        assertThat(patch.code()).isNull();
        assertThat(patch.name()).isNull();
        assertThat(patch.icon()).isNull();
        assertThat(patch.colour()).isNull();
        assertThat(patch.defaultLevel()).isNull();
        assertThat(patch.defaultSlaHrs()).isNull();
        assertThat(patch.seq()).isNull();
        assertThat(patch.isActive()).isNull();
    }

    @Test
    @DisplayName("zero is a value, not an absence")
    void zeroIsAValue() throws Exception {
        // seq 0 puts the type first in every picker. If it collapsed into "not
        // stated" the one position an admin cannot assign would be the front.
        TaskTypeDtos.TaskTypePatch patch =
                json.readValue("{\"seq\":0}", TaskTypeDtos.TaskTypePatch.class);

        assertThat(patch.seq()).isZero();
    }

    @Test
    @DisplayName("false is a value, not an absence — this is the retire path")
    void falseIsAValue() throws Exception {
        TaskTypeDtos.TaskTypePatch patch =
                json.readValue("{\"isActive\":false}", TaskTypeDtos.TaskTypePatch.class);

        assertThat(patch.isActive()).isFalse();
    }
}
