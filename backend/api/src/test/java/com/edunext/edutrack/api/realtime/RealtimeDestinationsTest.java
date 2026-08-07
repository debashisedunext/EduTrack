package com.edunext.edutrack.api.realtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-014 · the §9.3 destination map.
 *
 * <p>Pure string logic, so no container: the value is in pinning the exact
 * wire format, because these strings are a contract with the frontend's STOMP
 * client (D-015) and with D-013's authorisation. Changing one silently breaks
 * both, and only at runtime.
 */
class RealtimeDestinationsTest {

    // ------------------------------------------------------ exact wire format

    @Test
    void buildsEveryRoomFromBlueprint93() {
        assertThat(RealtimeDestinations.user(12)).isEqualTo("/user/12/queue/events");
        assertThat(RealtimeDestinations.ticket(4471)).isEqualTo("/topic/ticket.4471");
        assertThat(RealtimeDestinations.stage("QA", 7)).isEqualTo("/topic/stage.QA.7");
        assertThat(RealtimeDestinations.project(3)).isEqualTo("/topic/project.3");
        assertThat(RealtimeDestinations.manager(9)).isEqualTo("/topic/manager.9");
    }

    // ----------------------------------------------------------- round trips

    @Test
    void everyBuiltDestinationParsesBackToWhatBuiltIt() {
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.user(12)))
                .contains(new RealtimeDestination.UserQueue(12));
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.ticket(4471)))
                .contains(new RealtimeDestination.TicketTopic(4471));
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.stage("DEPLOY", 7)))
                .contains(new RealtimeDestination.StageTopic("DEPLOY", 7));
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.project(3)))
                .contains(new RealtimeDestination.ProjectTopic(3));
        assertThat(RealtimeDestinations.parse(RealtimeDestinations.manager(9)))
                .contains(new RealtimeDestination.ManagerTopic(9));
    }

    @Test
    void aStageCodeContainingAHyphenStillRoundTrips() {
        String destination = RealtimeDestinations.stage("CODE-REVIEW", 42);
        assertThat(destination).isEqualTo("/topic/stage.CODE-REVIEW.42");
        assertThat(RealtimeDestinations.parse(destination))
                .contains(new RealtimeDestination.StageTopic("CODE-REVIEW", 42));
    }

    // -------------------------------------------------------- rejected input

    /**
     * A dot in a stage code would re-shape the destination: "QA.1" on project 7
     * produces the same string as "QA" on project 1 would if the code were
     * allowed to carry a separator, so a subscriber could end up in a queue
     * that is not theirs.
     */
    @Test
    void aStageCodeCarryingTheSeparatorIsRejected() {
        assertThatThrownBy(() -> RealtimeDestinations.stage("QA.1", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"qa", "QA STAGE", "QA/1", "", "QA.", "../admin"})
    void anInvalidStageCodeIsRejectedRatherThanSanitised(String stageCode) {
        assertThatThrownBy(() -> RealtimeDestinations.stage(stageCode, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIdMustBePositive() {
        assertThatThrownBy(() -> RealtimeDestinations.ticket(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticketId");
        assertThatThrownBy(() -> RealtimeDestinations.user(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    // ------------------------------------------------------ unknown is empty

    /**
     * The security-relevant case. D-013 authorises from the parse result, so
     * anything unrecognised must come back empty and be denied — never fall
     * through to a default that lets the subscription past.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/ticket.notanumber",
            "/topic/ticket.",
            "/topic/ticket.-5",
            "/topic/unknown.1",
            "/topic/stage.QA",            // no project id
            "/topic/stage..7",            // no stage code
            "/user/12/queue/other",       // not the events queue
            "/user/abc/queue/events",
            "/queue/anything",
            "",
            "   "
    })
    void anUnrecognisedDestinationParsesToEmpty(String destination) {
        assertThat(RealtimeDestinations.parse(destination)).isEmpty();
    }

    @Test
    void aNullDestinationParsesToEmpty() {
        assertThat(RealtimeDestinations.parse(null)).isEqualTo(Optional.empty());
    }
}
