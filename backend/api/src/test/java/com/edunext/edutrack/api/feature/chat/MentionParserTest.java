package com.edunext.edutrack.api.feature.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-052 · what counts as an {@code @mention}.
 *
 * <p>Worth its own test rather than being covered only through the engine,
 * because the interesting cases are all textual — an email address, a full stop
 * at the end of a sentence, the same person named twice — and each of them costs
 * a MySQL container to exercise through {@code ChatEngineIT}.
 */
class MentionParserTest {

    @Test
    void aPlainMention() {
        assertThat(MentionParser.handles("can you look at this @ravi"))
                .containsExactly("ravi");
    }

    @Test
    void severalMentionsKeepTheOrderTheyWereWrittenIn() {
        assertThat(MentionParser.handles("@ravi and @meera, thoughts?"))
                .containsExactly("ravi", "meera");
    }

    @Test
    @DisplayName("an email address is not a mention")
    void emailAddressesAreNotMentions() {
        // Without the lookbehind, every message quoting an address notifies
        // whoever happens to own that domain as a username.
        assertThat(MentionParser.handles("mail it to ravi@edunext.com")).isEmpty();
        assertThat(MentionParser.handles("cc: a.b-c_d@example.co.uk")).isEmpty();
    }

    @Test
    @DisplayName("a full stop ending the sentence is not part of the name")
    void trailingPunctuationIsTrimmed() {
        assertThat(MentionParser.handles("ask @ravi.")).containsExactly("ravi");
        assertThat(MentionParser.handles("ask @ravi.kumar.")).containsExactly("ravi.kumar");
        assertThat(MentionParser.handles("@ravi-")).containsExactly("ravi");
        assertThat(MentionParser.handles("@ravi_")).containsExactly("ravi");
    }

    @Test
    void surroundingPunctuationDoesNotBlockAMention() {
        assertThat(MentionParser.handles("(@ravi)")).containsExactly("ravi");
        assertThat(MentionParser.handles("@ravi's call")).containsExactly("ravi");
        assertThat(MentionParser.handles("\"@ravi\"")).containsExactly("ravi");
    }

    @Test
    @DisplayName("the same person named twice is one recipient")
    void duplicatesCollapse() {
        assertThat(MentionParser.handles("@ravi @Ravi @RAVI")).containsExactly("ravi");
    }

    @Test
    @DisplayName("case is folded, because users.username is case-insensitive anyway")
    void caseIsFolded() {
        assertThat(MentionParser.handles("@RaviKumar")).containsExactly("ravikumar");
    }

    @Test
    void aMentionAtTheVeryStartIsFound() {
        assertThat(MentionParser.handles("@ravi please review")).containsExactly("ravi");
    }

    @Test
    void nothingToFind() {
        assertThat(MentionParser.handles(null)).isEmpty();
        assertThat(MentionParser.handles("")).isEmpty();
        assertThat(MentionParser.handles("no mentions here")).isEmpty();
        assertThat(MentionParser.handles("a bare @ on its own")).isEmpty();
        assertThat(MentionParser.handles("@@ravi"))
                .as("@@ is not a handle prefix")
                .isEmpty();
    }

    @Test
    @DisplayName("a handle cannot start with punctuation")
    void aHandleStartsWithAlphanumeric() {
        assertThat(MentionParser.handles("@.ravi")).isEmpty();
        assertThat(MentionParser.handles("@-ravi")).isEmpty();
    }

    @Test
    @DisplayName("a run longer than users.username cannot be a username")
    void overlongRunsAreTruncatedToTheColumnWidth() {
        String tooLong = "a".repeat(60);

        // Truncated to 50 rather than dropped: the result simply will not
        // resolve, and dropping it would need the parser to know the column
        // width for a second reason.
        assertThat(MentionParser.handles("@" + tooLong))
                .allSatisfy(handle -> assertThat(handle).hasSize(50));
    }

    @Test
    @DisplayName("candidates are capped so a long message cannot build a huge query")
    void candidatesAreCapped() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("@user").append(i).append(' ');
        }

        // The real bound on who gets notified is thread participation. This cap
        // only stops a 20,000-character body reaching the IN (…) clause.
        assertThat(MentionParser.handles(body.toString())).hasSize(50);
    }
}
