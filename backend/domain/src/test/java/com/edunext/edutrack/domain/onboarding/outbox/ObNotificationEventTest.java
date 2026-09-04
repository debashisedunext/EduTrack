package com.edunext.edutrack.domain.onboarding.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-111 · the onboarding event catalogue, checked against the column it is
 * stored in and against itself.
 *
 * <p>Every assertion here is about a failure that would otherwise surface as a
 * mail nobody receives: a key too long for {@code event_key VARCHAR(60)} is an
 * exception inside the enqueuing transaction, and a variable name spelled two
 * ways is a sentence with a hole in it.
 */
class ObNotificationEventTest {

    @Test
    @DisplayName("every key fits event_key VARCHAR(60)")
    void keysFitTheColumn() {
        for (ObNotificationEvent event : ObNotificationEvent.values()) {
            assertThat(event.key())
                    .as("%s is stored in event_key", event)
                    .hasSizeLessThanOrEqualTo(60);
        }
    }

    @Test
    @DisplayName("a key is accepted by ObNotification, which validates the same column")
    void keysAreAcceptedByTheRecord() {
        for (ObNotificationEvent event : ObNotificationEvent.values()) {
            ObNotification notification = ObNotification.aboutClient(
                    event.key(), ObChannel.EMAIL, new ObRecipient.Client(7), 42, Map.of());
            assertThat(notification.eventKey()).isEqualTo(event.key());
        }
    }

    @Test
    @DisplayName("of() reads a key back, case-insensitively and with whitespace")
    void ofIsTolerant() {
        assertThat(ObNotificationEvent.of("TAT_BREACHED")).contains(ObNotificationEvent.TAT_BREACHED);
        assertThat(ObNotificationEvent.of(" tat_breached ")).contains(ObNotificationEvent.TAT_BREACHED);
    }

    @Test
    @DisplayName("an event this build has never heard of is empty, not an exception")
    void ofToleratesTheUnknown() {
        // The deploy case: a queue filled by a newer api than the worker
        // draining it. ObChannel.of makes the same promise for the same reason.
        assertThat(ObNotificationEvent.of("SOMETHING_ADDED_LATER")).isEmpty();
        assertThat(ObNotificationEvent.of(null)).isEmpty();
        assertThat(ObNotificationEvent.of("  ")).isEmpty();
    }

    @Test
    @DisplayName("every event declares at least one required variable")
    void everyEventSaysWhatItNeeds() {
        for (ObNotificationEvent event : ObNotificationEvent.values()) {
            assertThat(event.requiredVariables())
                    .as("%s must tell an enqueuer what to send", event)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("no variable is both required and optional")
    void requiredAndOptionalDoNotOverlap() {
        for (ObNotificationEvent event : ObNotificationEvent.values()) {
            Set<String> overlap = new HashSet<>(event.requiredVariables());
            overlap.retainAll(event.optionalVariables());
            assertThat(overlap)
                    .as("%s cannot have it both ways", event)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("variable names are the {{tag}} dialect's — lower snake_case")
    void variableNamesAreSnakeCase() {
        // The renderer substitutes on MergeTag.PLACEHOLDER, which matches
        // [A-Za-z0-9_]+. A name with a hyphen or a capital would be declared
        // here, accepted by every test that only counts them, and silently
        // unreachable from a template.
        for (ObNotificationEvent event : ObNotificationEvent.values()) {
            for (String variable : event.variables()) {
                assertThat(variable)
                        .as("%s declares %s", event, variable)
                        .matches("[a-z][a-z0-9_]*");
            }
        }
    }

    @Test
    @DisplayName("the two events that cannot work without a link require one")
    void tokenBearingEventsRequireTheirLink() {
        // Both carry a one-time token that lives only in the payload — nothing
        // downstream can derive the URL, so a row without it is a mail the
        // recipient cannot act on at all.
        assertThat(ObNotificationEvent.SIGNOFF_REQUESTED.requiredVariables()).contains("action_url");
        assertThat(ObNotificationEvent.CLIENT_PASSWORD_RESET.requiredVariables()).contains("action_url");
    }

    @Test
    @DisplayName("the login mail carries no password variable")
    void noCredentialInThePayloadContract() {
        // The payload is stored as JSON on a row that outlives the send, so a
        // temporary password declared here would be a live credential sitting in
        // the database indefinitely. The mail carries a single-use link instead.
        assertThat(ObNotificationEvent.CLIENT_LOGIN_CREATED.variables())
                .noneMatch(variable -> variable.contains("password"));
    }

    @Test
    @DisplayName("all() is every constant, in declaration order")
    void allIsEveryConstant() {
        assertThat(ObNotificationEvent.all())
                .containsExactly(ObNotificationEvent.values());
    }
}
