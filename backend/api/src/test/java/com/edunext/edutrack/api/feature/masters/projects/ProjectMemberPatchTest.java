package com.edunext.edutrack.api.feature.masters.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-017 · the patch's absent-versus-null distinction, pinned at the layer that
 * actually decides it.
 *
 * <p>{@link ProjectMemberService} branches on {@code patch.projectRole() == null}
 * meaning "omitted" and {@code Optional.empty()} meaning "explicitly cleared".
 * That branch is only correct if Jackson really does deserialise the two cases
 * differently, and {@code ProjectMemberServiceTest} cannot prove it — it
 * constructs the record directly and would keep passing if every request on the
 * wire arrived as one case or the other.
 *
 * <p><b>The failure mode if this ever stopped holding is silent and total.</b>
 * If an absent property started deserialising to {@code Optional.empty()}, every
 * inline edit of an allocation would also clear the member's project role, and
 * the response would look correct because it echoes what was saved. Nothing else
 * in the suite would notice.
 *
 * <p>The mapper is built the way Spring Boot builds the application's, rather
 * than with {@code new ObjectMapper()}: the behaviour under test comes from
 * {@code Jdk8Module} being registered, so a bare mapper would test a
 * configuration the server does not run.
 */
class ProjectMemberPatchTest {

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("an omitted property arrives as null — 'leave it alone'")
    void omittedIsNull() throws Exception {
        ProjectMemberDtos.TeamMemberPatch patch =
                json.readValue("{\"allocationPct\":60}", ProjectMemberDtos.TeamMemberPatch.class);

        assertThat(patch.projectRole()).as("omitted must not be Optional.empty()").isNull();
        assertThat(patch.allocationPct()).contains(60);
    }

    @Test
    @DisplayName("an explicit null arrives as Optional.empty() — 'clear it'")
    void explicitNullIsEmpty() throws Exception {
        ProjectMemberDtos.TeamMemberPatch patch =
                json.readValue("{\"projectRole\":null}", ProjectMemberDtos.TeamMemberPatch.class);

        assertThat(patch.projectRole()).isNotNull().isEmpty();
        assertThat(patch.allocationPct()).as("still absent").isNull();
    }

    @Test
    @DisplayName("an empty body leaves both alone")
    void emptyBodyChangesNothing() throws Exception {
        ProjectMemberDtos.TeamMemberPatch patch =
                json.readValue("{}", ProjectMemberDtos.TeamMemberPatch.class);

        assertThat(patch.projectRole()).isNull();
        assertThat(patch.allocationPct()).isNull();
    }

    @Test
    @DisplayName("zero is a value, not an absence")
    void zeroIsAValue() throws Exception {
        // "No capacity committed" and "not stated" are different facts. If this
        // ever collapsed, the tab could record one and never the other.
        ProjectMemberDtos.TeamMemberPatch patch =
                json.readValue("{\"allocationPct\":0}", ProjectMemberDtos.TeamMemberPatch.class);

        assertThat(patch.allocationPct()).contains(0);
    }
}
