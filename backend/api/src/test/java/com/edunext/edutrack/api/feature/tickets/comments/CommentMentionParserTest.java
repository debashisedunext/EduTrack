package com.edunext.edutrack.api.feature.tickets.comments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-030 · what counts as an {@code @mention} in a comment.
 *
 * <p>Written adversarially rather than as a happy path with one {@code @ravi}
 * case, which is the lesson C-029's sanitiser tests recorded: the two jsoup
 * defects there were both found by asking what the rule lets through, not by
 * asking whether it works.
 *
 * <p>The rules deliberately match {@code chat.MentionParser}'s. Where a case
 * below looks arbitrary, it is because the two must agree — {@code @ravi}
 * behaving differently in a comment and in chat reads as a product bug rather
 * than as two implementations.
 */
class CommentMentionParserTest {

    @Nested
    @DisplayName("what is a handle")
    class Recognised {

        @Test
        void findsAPlainHandle() {
            assertThat(CommentMentionParser.handles("ask @ravi about it"))
                    .containsExactly("ravi");
        }

        @Test
        @DisplayName("dots, hyphens and underscores are handle characters — ravi.kumar is the house style")
        void findsADottedHandle() {
            assertThat(CommentMentionParser.handles("@ravi.kumar @meera-s @dev_ops"))
                    .containsExactly("ravi.kumar", "meera-s", "dev_ops");
        }

        @Test
        void findsAHandleAtTheVeryStart() {
            assertThat(CommentMentionParser.handles("@ravi please look"))
                    .containsExactly("ravi");
        }

        @Test
        @DisplayName("@Ravi and @ravi are one recipient, not two")
        void foldsCase() {
            assertThat(CommentMentionParser.handles("@Ravi and @ravi and @RAVI"))
                    .containsExactly("ravi");
        }

        @Test
        @DisplayName("order of first appearance, so a notification order is reproducible")
        void keepsFirstAppearanceOrder() {
            assertThat(CommentMentionParser.handles("@zed @amy @zed"))
                    .containsExactly("zed", "amy");
        }

        /**
         * "Ask @ravi." ends a sentence. The character class has to admit a dot
         * mid-handle, so position is the only thing that separates the two.
         */
        @Test
        void trimsTrailingPunctuationThatIsNotPartOfTheName() {
            assertThat(CommentMentionParser.handles("Ask @ravi.")).containsExactly("ravi");
            assertThat(CommentMentionParser.handles("cc @meera-")).containsExactly("meera");
            assertThat(CommentMentionParser.handles("@ravi.kumar. done")).containsExactly("ravi.kumar");
        }

        @Test
        void findsHandlesAcrossLines() {
            assertThat(CommentMentionParser.handles("@ravi\nand also\n@meera"))
                    .containsExactly("ravi", "meera");
        }
    }

    @Nested
    @DisplayName("what is not")
    class Refused {

        /**
         * The case that reaches production. Comments quote addresses constantly
         * — "chase this with ops@edunext.com" — and without the lookbehind every
         * one of them notifies whoever owns that domain as a username.
         */
        @Test
        void anEmailAddressIsNotAMention() {
            assertThat(CommentMentionParser.handles("chase this with ops@edunext.com")).isEmpty();
            assertThat(CommentMentionParser.handles("ravi.kumar@edunext.com raised it")).isEmpty();
        }

        /**
         * The lookbehind's one accepted false negative, asserted so it is a
         * decision rather than a surprise. Nobody writes handles back to back.
         */
        @Test
        @DisplayName("back-to-back handles find only the first — the accepted cost of the lookbehind")
        void backToBackHandlesFindOnlyTheFirst() {
            assertThat(CommentMentionParser.handles("@ravi@meera")).containsExactly("ravi");
        }

        @Test
        void aBareAtIsNotAMention() {
            assertThat(CommentMentionParser.handles("look @ this")).isEmpty();
            assertThat(CommentMentionParser.handles("@")).isEmpty();
        }

        @Test
        @DisplayName("a handle must start with a letter or digit")
        void aHandleCannotStartWithPunctuation() {
            assertThat(CommentMentionParser.handles("@.ravi")).isEmpty();
            assertThat(CommentMentionParser.handles("@-ravi")).isEmpty();
        }

        /** {@code users.username} is {@code VARCHAR(50)}; a longer run is not one. */
        @Test
        void aRunLongerThanTheColumnIsTruncatedToTheColumnsWidth() {
            String long51 = "a".repeat(51);

            // The regex bounds the capture at 50, so what comes back is a
            // 50-character candidate that will simply not resolve — rather than
            // a 51-character string carried to the query for nothing.
            assertThat(CommentMentionParser.handles("@" + long51))
                    .containsExactly("a".repeat(50));
        }

        @Test
        void nullAndEmptyAreNotErrors() {
            assertThat(CommentMentionParser.handles(null)).isEmpty();
            assertThat(CommentMentionParser.handles("")).isEmpty();
            assertThat(CommentMentionParser.handles("no at sign at all")).isEmpty();
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        /**
         * A cap on the {@code IN (…)} clause, not on people notified. Project
         * membership is the real limit; this only stops a 20 000-character body
         * becoming a query with two thousand parameters.
         */
        @Test
        void stopsAtFiftyDistinctCandidates() {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                body.append("@user").append(i).append(' ');
            }

            List<String> handles = CommentMentionParser.handles(body.toString());

            assertThat(handles).hasSize(50);
            assertThat(handles.getFirst()).isEqualTo("user0");
        }

        @Test
        @DisplayName("repetition does not consume the budget — the set is distinct")
        void repeatsDoNotCountTowardsTheCap() {
            String body = "@ravi ".repeat(200) + "@meera";

            assertThat(CommentMentionParser.handles(body)).containsExactly("ravi", "meera");
        }
    }
}
