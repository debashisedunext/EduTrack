package com.edunext.edutrack.api.feature.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-053 · what a typed query becomes before it reaches MySQL.
 *
 * <p>The case that matters is the one nobody thinks to try: pasting a chunk of
 * a message into the search box. That text carries {@code + - " ( ) @}, all of
 * which are boolean-mode operators, and none of which the person typing meant
 * as one.
 */
class ChatSearchTest {

    @Test
    void everyWordIsRequiredAndMatchesAsAPrefix() {
        assertThat(ChatSearch.toBooleanQuery("deployment rollback"))
                .isEqualTo("+deployment* +rollback*");
    }

    @Test
    @DisplayName("boolean-mode operators are stripped, not honoured")
    void operatorsCannotBeInjected() {
        // "-rollback" in boolean mode means "must NOT contain rollback", which
        // is the opposite of what somebody typing a hyphenated phrase wants.
        assertThat(ChatSearch.toBooleanQuery("deployment -rollback"))
                .isEqualTo("+deployment* +rollback*");
        assertThat(ChatSearch.toBooleanQuery("\"exact phrase\""))
                .isEqualTo("+exact* +phrase*");
        assertThat(ChatSearch.toBooleanQuery("(ravi) @meera >urgent"))
                .isEqualTo("+ravi* +meera* +urgent*");
    }

    @Test
    @DisplayName("pasting a message body is a search, not a syntax error")
    void aPastedMessageIsSafe() {
        String pasted = "@it_chat_ravi — deploy failed (again!) ~ rollback? +1";

        assertThat(ChatSearch.toBooleanQuery(pasted))
                .isEqualTo("+it_chat_ravi* +deploy* +failed* +again* +rollback*");
    }

    @Test
    @DisplayName("words below the index's minimum are dropped, not required")
    void shortWordsAreDroppedRatherThanRequired() {
        // Requiring "qa" would make the whole query match nothing, because the
        // index does not contain two-letter tokens — so "QA fix" would fail to
        // find a message plainly containing "fix".
        assertThat(ChatSearch.toBooleanQuery("QA fix")).isEqualTo("+fix*");
        assertThat(ChatSearch.toBooleanQuery("a b deployment")).isEqualTo("+deployment*");
    }

    @Test
    void caseIsFolded() {
        assertThat(ChatSearch.toBooleanQuery("Deployment ROLLBACK"))
                .isEqualTo("+deployment* +rollback*");
    }

    @Test
    void theSameWordTwiceIsOneTerm() {
        assertThat(ChatSearch.toBooleanQuery("deploy deploy DEPLOY"))
                .isEqualTo("+deploy*");
    }

    @Test
    @DisplayName("a search this index cannot serve is empty, not an error")
    void nothingUsableYieldsAnEmptyQuery() {
        assertThat(ChatSearch.toBooleanQuery(null)).isEmpty();
        assertThat(ChatSearch.toBooleanQuery("")).isEmpty();
        assertThat(ChatSearch.toBooleanQuery("   ")).isEmpty();
        assertThat(ChatSearch.toBooleanQuery("!!! ??? ---")).isEmpty();
        assertThat(ChatSearch.toBooleanQuery("qa ui")).as("both below the floor").isEmpty();
    }

    @Test
    @DisplayName("a name in any script is a search term")
    void nonLatinWordsSurvive() {
        assertThat(ChatSearch.toBooleanQuery("तैनाती")).isEqualTo("+तैनाती*");
    }

    @Test
    void theTermCountIsBounded() {
        StringBuilder wall = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            wall.append("word").append(i).append(' ');
        }

        assertThat(ChatSearch.toBooleanQuery(wall.toString()).split(" ")).hasSize(16);
    }
}
