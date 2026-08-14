package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-025 · {@code tickets/{ticket_id}/{uuid}} — blueprint §4B.4's object key.
 */
class AttachmentStorageKeyTest {

    @Nested
    @DisplayName("the shape the blueprint specifies, and only that shape")
    class Shape {

        @Test
        void isTicketsThenTheIdThenAUuid() {
            assertThat(AttachmentStorageKey.mint(347).toString())
                    .matches("^tickets/347/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }

        @Test
        void roundTripsThroughParse() {
            AttachmentStorageKey key = AttachmentStorageKey.mint(347);
            assertThat(AttachmentStorageKey.parse(key.toString())).isEqualTo(key);
        }

        @Test
        void aTicketIdOfZeroOrLessIsRejected() {
            assertThatThrownBy(() -> AttachmentStorageKey.mint(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AttachmentStorageKey.mint(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the file name never reaches the key")
    class NoUserInput {

        @Test
        void twoUploadsOfTheSameNameToTheSameTicketGetDifferentKeys() {
            // C-024's clipboard paste makes this routine rather than exotic:
            // every screenshot a browser hands over is called `image.png`. A key
            // derived from the name would have one silently overwrite the other.
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                keys.add(AttachmentStorageKey.mint(347).toString());
            }
            assertThat(keys).hasSize(500);
        }
    }

    @Nested
    @DisplayName("a key read back out of the database is validated, not trusted")
    class Parsing {

        @Test
        void pathTraversalIsRefused() {
            // The value reaches an S3 GetObject and a presigner. MinIO on a
            // filesystem backend treats keys as paths.
            assertThatThrownBy(() -> AttachmentStorageKey.parse("tickets/347/../../etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AttachmentStorageKey.parse("../tickets/347/" + java.util.UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anAbsoluteUrlIsRefused() {
            assertThatThrownBy(() -> AttachmentStorageKey.parse("https://evil.example/x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anythingOtherThanTheExactShapeIsRefused() {
            assertThatThrownBy(() -> AttachmentStorageKey.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AttachmentStorageKey.parse(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AttachmentStorageKey.parse("tickets/347/not-a-uuid"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AttachmentStorageKey.parse("avatars/347/" + java.util.UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
            // Anchored: a valid key with anything appended is not a valid key.
            assertThatThrownBy(() -> AttachmentStorageKey.parse(
                    "tickets/347/" + java.util.UUID.randomUUID() + "/../secrets"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void belongsToAnswersFalseRatherThanThrowingForAKeyFromAnotherTicket() {
            String key = AttachmentStorageKey.mint(347).toString();
            assertThat(AttachmentStorageKey.belongsTo(key, 347)).isTrue();
            assertThat(AttachmentStorageKey.belongsTo(key, 348)).isFalse();
            assertThat(AttachmentStorageKey.belongsTo("nonsense", 347)).isFalse();
        }
    }
}
