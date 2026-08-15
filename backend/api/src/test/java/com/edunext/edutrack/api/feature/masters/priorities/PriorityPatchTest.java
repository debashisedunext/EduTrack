package com.edunext.edutrack.api.feature.masters.priorities;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-021 · absent is not null, and on this DTO the difference is destructive.
 *
 * <p>{@code defaultSlaHrs} is the one clearable field, and clearing it is a real
 * state: the level stops answering rung 4 of the §6 ladder and resolution falls
 * through to the task type's default. Written as a record first — it compiles
 * and reads better — and wrong for the reason B-017 documented on
 * {@code TeamMemberPatch} and B-020 repeated on {@code TaskTypePatch}: Jackson
 * binds a record through its canonical constructor, and an <i>absent</i>
 * {@code Optional} creator property is filled with {@code Optional.empty()},
 * the same value an explicit JSON null produces. A recolour would have silently
 * dropped the level out of the SLA ladder, and the response would have looked
 * right because it echoes what was saved.
 *
 * <p>Through a real Jackson, not by calling the setters: the claim is about how
 * the body binds, and a hand-built instance cannot be wrong about that.
 */
class PriorityPatchTest {

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("an omitted clearable arrives as null — 'leave it alone'")
    void omittedIsNull() throws Exception {
        PriorityDtos.PriorityPatch patch =
                json.readValue("{\"name\":\"Urgent\"}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.name()).isEqualTo("Urgent");
        assertThat(patch.defaultSlaHrs()).as("omitted must not be Optional.empty()").isNull();
    }

    @Test
    @DisplayName("an explicit null arrives as Optional.empty() — 'clear it'")
    void explicitNullIsEmpty() throws Exception {
        PriorityDtos.PriorityPatch patch =
                json.readValue("{\"defaultSlaHrs\":null}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.defaultSlaHrs()).isNotNull().isEmpty();
        assertThat(patch.name()).as("still absent").isNull();
    }

    @Test
    @DisplayName("a value arrives as a present Optional")
    void valueIsPresent() throws Exception {
        PriorityDtos.PriorityPatch patch =
                json.readValue("{\"defaultSlaHrs\":7.5}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.defaultSlaHrs()).contains(new BigDecimal("7.5"));
    }

    @Test
    @DisplayName("an empty body changes nothing")
    void emptyBodyChangesNothing() throws Exception {
        PriorityDtos.PriorityPatch patch =
                json.readValue("{}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.level()).isNull();
        assertThat(patch.name()).isNull();
        assertThat(patch.colour()).isNull();
        assertThat(patch.defaultSlaHrs()).isNull();
        assertThat(patch.autoEscalates()).isNull();
        assertThat(patch.seq()).isNull();
        assertThat(patch.isActive()).isNull();
    }

    @Test
    @DisplayName("seq zero is a value, not an absence")
    void zeroIsAValue() throws Exception {
        // seq 0 puts the level first in every picker and first in every SLA
        // matrix's columns. If it collapsed into "not stated", the one position
        // an admin cannot assign would be the front.
        PriorityDtos.PriorityPatch patch =
                json.readValue("{\"seq\":0}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.seq()).isZero();
    }

    @Test
    @DisplayName("isActive false is a value, not an absence — this is the retire path")
    void falseIsAValue() throws Exception {
        PriorityDtos.PriorityPatch patch =
                json.readValue("{\"isActive\":false}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.isActive()).isFalse();
    }

    @Test
    @DisplayName("autoEscalates false is a value, not an absence — it is the refused path")
    void escalationFalseIsAValue() throws Exception {
        // This one matters more than the others. If `false` collapsed into
        // absent, clearing the escalation flag would be a silent no-op rather
        // than the 409 PriorityService raises — the admin would be told the
        // save succeeded and §6 would go on escalating to the level they
        // believed they had unset.
        PriorityDtos.PriorityPatch patch =
                json.readValue("{\"autoEscalates\":false}", PriorityDtos.PriorityPatch.class);

        assertThat(patch.autoEscalates()).isFalse();
    }
}
