package com.edunext.edutrack.api.feature.portal.tickets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-127 · the duplicated parser is held to the same rules as the original.
 *
 * <p>{@link PortalStorageKey} exists only because
 * {@code AttachmentStorageKey.parse} is package-private in Stream C's package;
 * its javadoc says so and says it should be deleted when that changes. What
 * makes the duplicate tolerable in the meantime is that its refusals are
 * asserted rather than assumed — a second parser that quietly accepted more than
 * the first is precisely the hazard {@code StorageKey}'s own javadoc names.
 *
 * <p>The traversal and absolute-URL cases below are the ones that class calls
 * out by name.
 */
class PortalStorageKeyTest {

    private static final String VALID = "tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("reads the shape this application mints")
        void readsAMintedKey() {
            PortalStorageKey key = PortalStorageKey.parse(VALID);

            assertThat(key.ticketId()).isEqualTo(42);
            assertThat(key.thumbnail()).isFalse();
            assertThat(key.value()).isEqualTo(VALID);
        }

        @Test
        @DisplayName("-thumb is the one suffix allowed, and round-trips")
        void readsAThumbnailKey() {
            PortalStorageKey key = PortalStorageKey.parse(VALID + "-thumb");

            assertThat(key.thumbnail()).isTrue();
            assertThat(key.value()).isEqualTo(VALID + "-thumb");
        }

        /**
         * The whole reason a parser stands between the column and the presigner.
         *
         * <p>Each of these is a value that, handed to a storage client that
         * normalises differently from how we assume, addresses an object the
         * caller was never entitled to — or, in the {@code http://} case, invites
         * the presigner to sign somebody else's host.
         */
        @ParameterizedTest(name = "refuses \"{0}\"")
        @ValueSource(strings = {
                "tickets/42/../43/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "../tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301/../../secrets",
                "http://evil.example.com/tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "/tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "chat/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "tickets/42/not-a-uuid",
                "tickets//3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301-thumb-thumb",
                "tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301.jpg",
                "tickets/42/3f2504e0-4f89-41d3-9a0c-0305e82c3301\ntickets/1/x",
                "",
                "   ",
        })
        void refusesAnythingButTheExactShape(String key) {
            assertThatThrownBy(() -> PortalStorageKey.parse(key))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses null without a NullPointerException escaping")
        void refusesNull() {
            assertThatThrownBy(() -> PortalStorageKey.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Anchored, so a valid key with anything appended is not a valid key.
         *
         * <p>Separate from the list above because the failure it catches is a
         * pattern that lost its {@code $} — which still accepts every good key,
         * so nothing else here would notice.
         */
        @Test
        @DisplayName("a valid key with a suffix is not a valid key")
        void isAnchoredAtBothEnds() {
            assertThatThrownBy(() -> PortalStorageKey.parse(VALID + "/../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PortalStorageKey.parse("x" + VALID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("belongsTo")
    class BelongsTo {

        @Test
        @DisplayName("agrees when the key's ticket is the row's ticket")
        void agrees() {
            assertThat(PortalStorageKey.belongsTo(VALID, 42)).isTrue();
        }

        /**
         * The portal's second line, and the one that holds even if the pattern
         * above is ever loosened: a signed URL is minted only for an object under
         * the ticket the caller has already been scoped to.
         */
        @Test
        @DisplayName("refuses a well-formed key belonging to another ticket")
        void refusesAnotherTicketsObject() {
            assertThat(PortalStorageKey.belongsTo(VALID, 43)).isFalse();
        }

        @Test
        @DisplayName("answers false rather than throwing on a malformed key")
        void answersFalseOnRubbish() {
            assertThat(PortalStorageKey.belongsTo("../../etc/passwd", 42)).isFalse();
            assertThat(PortalStorageKey.belongsTo(null, 42)).isFalse();
        }
    }
}
