package com.edunext.edutrack.domain.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-039 · what the sender typed, separated from what they were replying to.
 *
 * <p>The bodies here are real client output rather than invented shapes — the
 * whole difficulty of this task is that every mail client marks a quote
 * differently, so a test suite made of one imagined format would prove nothing
 * about the mail this endpoint actually receives.
 */
class QuotedReplyStripperTest {

    @Nested
    @DisplayName("the formats clients actually send")
    class ClientFormats {

        @Test
        @DisplayName("Gmail — 'On <date>, <name> wrote:'")
        void gmail() {
            String body = """
                    Fixed in the latest build, please retest.

                    On Tue, 11 Aug 2026 at 14:02, EduTrack <no-reply@edutrack.local> wrote:

                    > [CRM-26-00347] Handed to you at QA by Ravi Kumar
                    > Open ticket: https://edutrack.local/tickets/347
                    """;

            assertThat(QuotedReplyStripper.strip(body))
                    .isEqualTo("Fixed in the latest build, please retest.");
        }

        @Test
        @DisplayName("Outlook — the '-----Original Message-----' rule")
        void outlookOriginalMessage() {
            String body = """
                    Approved from our side.

                    -----Original Message-----
                    From: EduTrack <no-reply@edutrack.local>
                    Sent: 11 August 2026 14:02
                    To: Priya Nair
                    Subject: [CRM-26-00347] Status
                    """;

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Approved from our side.");
        }

        @Test
        @DisplayName("Outlook — the underscore rule with no 'Original Message' line")
        void outlookUnderscoreRule() {
            String body = """
                    Please hold this until Monday.

                    ________________________________
                    From: EduTrack <no-reply@edutrack.local>
                    Sent: 11 August 2026 14:02
                    """;

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Please hold this until Monday.");
        }

        @Test
        @DisplayName("Outlook — a bare header block with no rule above it")
        void outlookBareHeaderBlock() {
            String body = """
                    Reassigning to Sneha.

                    From: EduTrack <no-reply@edutrack.local>
                    Sent: 11 August 2026 14:02
                    To: Karthik Subramaniam
                    Subject: [CRM-26-00347] Handed to you
                    """;

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Reassigning to Sneha.");
        }

        @Test
        @DisplayName("a bare '>' quote with no attribution line at all")
        void bareAngleQuote() {
            String body = """
                    Confirmed, closing.

                    > The deployment window is 22:00 IST.
                    > Please confirm.
                    """;

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Confirmed, closing.");
        }

        @Test
        @DisplayName("CRLF line endings, which is what actually arrives over SMTP")
        void crlf() {
            String body = "Done.\r\n\r\nOn Tue, 11 Aug 2026, EduTrack wrote:\r\n\r\n> anything\r\n";

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Done.");
        }
    }

    @Nested
    @DisplayName("what it must not do")
    class FalsePositives {

        @Test
        @DisplayName("'wrote:' in ordinary prose is not a quote marker")
        void wroteMidSentence() {
            // The reason every pattern is anchored to the start of a line. An
            // unanchored search for "wrote:" would truncate this to one word.
            String body = "I asked Priya and she wrote: the fix is on staging. Retesting now.";

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo(body);
        }

        @Test
        @DisplayName("'on … wrote:' inside a sentence is not Gmail's attribution line")
        void onWroteMidSentence() {
            // The pattern is case-insensitive and spans up to 200 characters
            // between "On" and "wrote:", which is what makes it survive every
            // locale's date format — and also what makes it dangerous unless
            // anchored to the start of a line. Unanchored, this truncates to
            // "It depends".
            String body = "It depends on how Ravi wrote: the spec is ambiguous. Checking with him.";

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo(body);
        }

        @Test
        @DisplayName("'From:' inside a sentence is not an Outlook header block")
        void fromMidSentence() {
            String body = "The escalation came From: the client, not from us. Handling it.";

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo(body);
        }

        @Test
        @DisplayName("a message with no quoted part is returned whole")
        void noQuoteAtAll() {
            String body = "Retested on build 412. Works.\n\nMoving to Deployment.";

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo(body);
        }
    }

    @Nested
    @DisplayName("the fail-safe")
    class NeverEmpty {

        @Test
        @DisplayName("a body that is entirely quoted keeps the quote rather than becoming empty")
        void whollyQuoted() {
            // Somebody replying with nothing but the quoted thread. An empty
            // comment would record that they replied and destroy what they
            // sent; this is also what an unrecognised client format looks like.
            String body = "> The deployment window is 22:00 IST.\n> Please confirm.";

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo(body.strip());
        }

        @Test
        @DisplayName("a quote marker on the very first line does not empty the message")
        void markerOnFirstLine() {
            String body = "On Tue, 11 Aug 2026, EduTrack wrote:\n\n> the whole thread";

            assertThat(QuotedReplyStripper.strip(body)).isNotBlank();
        }

        @Test
        @DisplayName("null and blank are handled rather than thrown on")
        void nullAndBlank() {
            assertThat(QuotedReplyStripper.strip(null)).isEmpty();
            assertThat(QuotedReplyStripper.strip("")).isEmpty();
            assertThat(QuotedReplyStripper.strip("   \n  ")).isBlank();
        }
    }

    @Nested
    @DisplayName("the earliest marker wins")
    class EarliestMarker {

        @Test
        @DisplayName("a marker later in the pattern list but earlier in the text still wins")
        void earliestInTextNotFirstInList() {
            // The case that actually pins the rule. A two-deep reply chain puts
            // a ">" quote (last-but-one pattern) ABOVE an "On … wrote:" (first
            // pattern). Iterating the patterns and stopping at the first that
            // matches anywhere would cut at the "On … wrote:" line and keep the
            // ">" block above it — the exact bug the earlier two-marker test
            // could not see, because there both markers sat in list order.
            String body = """
                    Thanks, that clears it up.

                    > Fixed in build 412.

                    On Mon, 10 Aug 2026 at 09:14, Sneha <sneha@edunext.test> wrote:

                    > > the original report
                    """;

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Thanks, that clears it up.");
        }

        @Test
        @DisplayName("a client emitting two different markers cuts at the first")
        void twoMarkers() {
            // Outlook draws its rule above the header block, so both patterns
            // match the same mail. Cutting at whichever matched first in the
            // pattern list rather than first in the text would keep the rule.
            String body = """
                    Noted.

                    ________________________________
                    From: EduTrack <no-reply@edutrack.local>
                    Sent: 11 August 2026 14:02
                    To: Priya Nair
                    Subject: [CRM-26-00347] Status

                    > earlier text
                    """;

            assertThat(QuotedReplyStripper.strip(body)).isEqualTo("Noted.");
        }
    }
}
