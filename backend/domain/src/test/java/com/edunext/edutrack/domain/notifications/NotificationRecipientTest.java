package com.edunext.edutrack.domain.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-022 · the recipient vocabulary and the column it is stored in.
 *
 * <p>The parsing half matters more than it looks: {@code worker} resolves
 * addresses from this, so a token it dropped silently is a mail one person
 * short, with nothing anywhere saying so.
 */
class NotificationRecipientTest {

    @Test
    @DisplayName("blueprint §11's To column is covered")
    void blueprintRecipientsAreAllPresent() {
        assertThat(List.of("ASSIGNEE", "STAGE_OWNER", "PREVIOUS_ASSIGNEE", "REPORTER",
                        "PROJECT_MANAGER", "REPORTING_MANAGER", "SUPPORT_DESK", "WATCHERS",
                        "MENTIONED_USER", "CLIENT_CONTACT"))
                .allSatisfy(code -> assertThat(NotificationRecipient.of(code)).isPresent());
    }

    @Test
    @DisplayName("the column form round-trips")
    void joinAndParseRoundTrip() {
        List<NotificationRecipient> recipients = List.of(
                NotificationRecipient.ASSIGNEE, NotificationRecipient.PROJECT_MANAGER);

        String stored = NotificationRecipient.join(recipients);

        assertThat(stored).isEqualTo("ASSIGNEE,PROJECT_MANAGER");
        assertThat(NotificationRecipient.parse(stored)).isEqualTo(recipients);
    }

    /** One recipient, one mail — a duplicated token is not two sends. */
    @Test
    @DisplayName("duplicates collapse in both directions")
    void duplicatesCollapse() {
        assertThat(NotificationRecipient.parse("ASSIGNEE,ASSIGNEE"))
                .containsExactly(NotificationRecipient.ASSIGNEE);
        assertThat(NotificationRecipient.join(
                List.of(NotificationRecipient.ADMIN, NotificationRecipient.ADMIN)))
                .isEqualTo("ADMIN");
    }

    /**
     * Tolerant on read: a template written by a newer deploy still sends to the
     * recipients this build <em>does</em> understand, rather than failing the
     * whole send. The write side refuses the unknown token in the first place,
     * where the person who typed it can still fix it.
     */
    @Test
    @DisplayName("a token this build does not know is skipped, not fatal")
    void unknownTokenIsSkipped() {
        assertThat(NotificationRecipient.parse("ASSIGNEE,SOMETHING_NEWER,ADMIN"))
                .containsExactly(NotificationRecipient.ASSIGNEE, NotificationRecipient.ADMIN);
    }

    @Test
    @DisplayName("an empty column is an empty list rather than a null element")
    void emptyColumnIsAnEmptyList() {
        assertThat(NotificationRecipient.parse(null)).isEmpty();
        assertThat(NotificationRecipient.parse("")).isEmpty();
        assertThat(NotificationRecipient.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("surrounding whitespace in a stored token is tolerated")
    void whitespaceIsTolerated() {
        assertThat(NotificationRecipient.parse("ASSIGNEE, PROJECT_MANAGER "))
                .containsExactly(NotificationRecipient.ASSIGNEE,
                        NotificationRecipient.PROJECT_MANAGER);
    }
}
