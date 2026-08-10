package com.edunext.edutrack.domain.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** D-031 · the subject line. */
class TicketMailSubjectTest {

    private static final String CODE = "CRM-26-00347";

    @Test
    @DisplayName("blueprint §4B.6's own example, exactly")
    void theTicketCodeComesFirst() {
        assertThat(TicketMailSubject.compose(CODE, "Handed to you at QA by Ravi Kumar"))
                .isEqualTo("[CRM-26-00347] Handed to you at QA by Ravi Kumar");
    }

    @Test
    @DisplayName("a caller that already prefixed does not get it twice")
    void anAlreadyPrefixedSubjectIsLeftAlone() {
        String composed = "[CRM-26-00347] Handed to you at QA";

        assertThat(TicketMailSubject.compose(CODE, composed)).isEqualTo(composed);
    }

    @Test
    @DisplayName("non-ticket mail has no code to lead with")
    void withoutATicketTheSubjectStandsAlone() {
        assertThat(TicketMailSubject.compose(null, "Your weekly summary"))
                .isEqualTo("Your weekly summary");
    }

    @Test
    void aMissingSubjectStillCarriesTheCode() {
        // Better a subject that only identifies the ticket than an empty one.
        assertThat(TicketMailSubject.compose(CODE, null)).isEqualTo("[CRM-26-00347]");
    }

    @Test
    void nothingAtAllIsEmptyRatherThanNull() {
        assertThat(TicketMailSubject.compose(null, null)).isEmpty();
    }

    @Test
    void surroundingSpaceIsNotPreserved() {
        assertThat(TicketMailSubject.compose("  " + CODE + " ", "  Handed to you  "))
                .isEqualTo("[CRM-26-00347] Handed to you");
    }

    @Test
    @DisplayName("a long subject is cut to fit the column, keeping the code")
    void anOverlongSubjectIsTruncatedFromTheRight() {
        String composed = TicketMailSubject.compose(CODE, "x".repeat(400));

        // The insert would otherwise throw and roll back the business
        // transaction that raised the mail.
        assertThat(composed).hasSize(TicketMailSubject.MAX_LENGTH);
        assertThat(composed).startsWith("[CRM-26-00347] ");
        assertThat(composed).endsWith("…");
    }

    @Test
    void aSubjectExactlyAtTheLimitIsUntouched() {
        String summary = "y".repeat(TicketMailSubject.MAX_LENGTH - "[CRM-26-00347] ".length());

        String composed = TicketMailSubject.compose(CODE, summary);

        assertThat(composed).hasSize(TicketMailSubject.MAX_LENGTH).doesNotEndWith("…");
    }
}
