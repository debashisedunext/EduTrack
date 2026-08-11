package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010 · the keyset cursor.
 *
 * <p>Small surface, but it decides which rows a caller sees. A cursor that
 * round-trips wrongly does not throw — it silently skips people.
 */
class ResourceCursorTest {

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("survives encode and decode unchanged")
        void survivesEncodeDecode() {
            ResourceCursor original = new ResourceCursor("Priya Sharma", 42L);

            assertThat(ResourceCursor.decode(original.encode())).isEqualTo(original);
        }

        @Test
        @DisplayName("a name containing the separator is not truncated")
        void nameContainingSeparator() {
            // Only the first separator splits; everything after it is the name.
            // Get this wrong and the page boundary lands on a shortened name,
            // which sorts differently and skips whoever falls between.
            ResourceCursor original = new ResourceCursor("O'Neill | Contractor", 7L);

            assertThat(ResourceCursor.decode(original.encode())).isEqualTo(original);
        }

        @Test
        @DisplayName("non-ASCII names survive the base64 round trip")
        void nonAsciiName() {
            ResourceCursor original = new ResourceCursor("Ananya Bhattachārya", 1234567890L);

            assertThat(ResourceCursor.decode(original.encode())).isEqualTo(original);
        }

        @Test
        @DisplayName("encodes URL-safely, so it survives being a query parameter")
        void urlSafe() {
            // A name whose UTF-8 encoding lands on base64's + and / characters.
            String encoded = new ResourceCursor("ÿÿÿ Ø", 1L).encode();

            assertThat(encoded).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        }
    }

    @Nested
    @DisplayName("bad input means the first page, not an error")
    class Tolerant {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
                "   ",
                "not base64 at all!!",
                "bm90LWEtY3Vyc29y",     // valid base64, no separator
                "fFByaXlh",             // separator first, so no id
                "YWJjfFByaXlh",         // non-numeric id
        })
        @DisplayName("decodes to null so the caller starts at the beginning")
        void malformedDecodesToNull(String cursor) {
            assertThat(ResourceCursor.decode(cursor)).isNull();
        }

        @Test
        @DisplayName("a bookmarked URL carrying yesterday's cursor is not a 400")
        void staleCursorIsNotAnError() {
            // Decoding never throws. A stale cursor points at a row that may
            // have been renamed or deactivated; the query simply resumes from
            // that position in the sort order and returns whatever is there.
            assertThat(ResourceCursor.decode("OTk5OTk5OTl8WlpaIEdvbmU"))
                    .isEqualTo(new ResourceCursor("ZZZ Gone", 99999999L));
        }
    }
}
