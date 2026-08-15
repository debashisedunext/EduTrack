package com.edunext.edutrack.common.pagination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-053 · the paging kernel.
 *
 * <p>Two things are worth testing here and the rest is plumbing: that a cursor
 * survives a round trip through a URL unchanged, and that the fetch-one-extra
 * boundary is right. The second is the one that matters — every failure mode it
 * has is silent, and shows up as a row nobody ever sees rather than as an error.
 */
class CursorPageTest {

    @Nested
    @DisplayName("the cursor")
    class CursorEncoding {

        @Test
        @DisplayName("round-trips through the wire form")
        void roundTrips() {
            Cursor original = new Cursor("2026-08-15T09:00:00Z", 4207);
            assertThat(Cursor.decode(original.encode())).isEqualTo(original);
        }

        /**
         * The reason id comes first in the encoding. A project called
         * "Phase 2 | rollout" is not exotic, and splitting on the last
         * separator instead of the first would truncate it.
         */
        @Test
        @DisplayName("survives a sort key containing the separator")
        void separatorInTheKeyIsNotASplitPoint() {
            Cursor original = new Cursor("Phase 2 | rollout", 12);
            assertThat(Cursor.decode(original.encode())).isEqualTo(original);
        }

        @Test
        @DisplayName("is URL-safe and unpadded, so a query string needs no escaping")
        void isUrlSafe() {
            String encoded = new Cursor("a name with spaces & symbols?", 999).encode();
            assertThat(encoded).doesNotContain("+", "/", "=");
        }

        /**
         * Both hand-written implementations reached this independently: a
         * bookmarked URL carrying yesterday's cursor should show the top of the
         * list, not an error page.
         */
        @Test
        @DisplayName("anything we did not issue means the first page, never an error")
        void malformedInputIsTheFirstPage() {
            assertThat(Cursor.decode(null)).isNull();
            assertThat(Cursor.decode("")).isNull();
            assertThat(Cursor.decode("   ")).isNull();
            assertThat(Cursor.decode("not base64 at all !!")).isNull();
            assertThat(Cursor.decode("bm8gc2VwYXJhdG9y")).as("decodes, but has no separator").isNull();
            assertThat(Cursor.decode("bm90YW51bWJlcnxrZXk")).as("id is not a number").isNull();
        }

        @Test
        @DisplayName("carries no column name — the caller cannot choose what to sort by")
        void carriesValuesOnly() {
            // The whole answer to ProjectCursor's objection: the wire form holds
            // an id and a value, and there is nowhere to put a column even if a
            // caller wanted to. ORDER BY stays in each endpoint's own code.
            String decoded = new String(java.util.Base64.getUrlDecoder()
                    .decode(new Cursor("Acme", 7).encode()));
            assertThat(decoded).isEqualTo("7|Acme");
        }

        @Test
        @DisplayName("refuses a null sort key rather than encoding \"null\"")
        void nullKeyIsRejected() {
            assertThatThrownBy(() -> new Cursor(null, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the limit")
    class Limits {

        @Test
        @DisplayName("absent or nonsensical means the default")
        void defaults() {
            assertThat(PageLimit.clamp(null)).isEqualTo(50);
            assertThat(PageLimit.clamp(0)).isEqualTo(50);
            assertThat(PageLimit.clamp(-10)).isEqualTo(50);
        }

        @Test
        @DisplayName("clamps to the maximum rather than rejecting")
        void clampsRatherThanRejects() {
            assertThat(PageLimit.clamp(1_000)).isEqualTo(200);
            assertThat(PageLimit.clamp(200)).isEqualTo(200);
            assertThat(PageLimit.clamp(37)).isEqualTo(37);
        }

        @Test
        @DisplayName("fetches one more than the page, which is what makes hasMore knowable")
        void fetchesOneExtra() {
            assertThat(PageLimit.fetchSize(50)).isEqualTo(51);
        }
    }

    @Nested
    @DisplayName("the page boundary")
    class Boundary {

        private static final List<String> ROWS =
                IntStream.rangeClosed(1, 100).mapToObj(i -> "row" + i).toList();

        private static CursorPage<String> page(int limit, int available) {
            List<String> fetched = ROWS.subList(0, Math.min(available, PageLimit.fetchSize(limit)));
            return CursorPage.of(fetched, limit, r -> new Cursor(r, Long.parseLong(r.substring(3))));
        }

        @Test
        @DisplayName("never returns more rows than were asked for")
        void neverOverfills() {
            CursorPage<String> p = page(10, 100);
            assertThat(p.data()).hasSize(10).endsWith("row10");
            assertThat(p.meta().hasMore()).isTrue();
        }

        /**
         * The one that silently loses rows. If nextCursor names the extra row
         * rather than the last returned one, the next page starts after a row
         * the caller was never given, and it is gone for good.
         */
        @Test
        @DisplayName("resumes from the last returned row, not the extra one")
        void resumesFromTheLastReturnedRow() {
            CursorPage<String> p = page(10, 100);
            Cursor resume = Cursor.decode(p.meta().nextCursor());
            assertThat(resume).isNotNull();
            assertThat(resume.sortKey()).as("row10 was returned; row11 was only evidence").isEqualTo("row10");
            assertThat(resume.id()).isEqualTo(10);
        }

        /**
         * The off-by-one that costs a wasted round trip on every list whose
         * total happens to be a multiple of the page size.
         */
        @Test
        @DisplayName("an exactly-full final page is the last page")
        void exactlyFullFinalPageIsNotAPromiseOfMore() {
            CursorPage<String> p = page(10, 10);
            assertThat(p.data()).hasSize(10);
            assertThat(p.meta().hasMore()).as("10 of exactly 10 — there is no eleventh").isFalse();
            assertThat(p.meta().nextCursor()).isNull();
        }

        @Test
        @DisplayName("a short page is the last page")
        void shortPageIsTheLastPage() {
            CursorPage<String> p = page(10, 3);
            assertThat(p.data()).hasSize(3);
            assertThat(p.meta()).isEqualTo(PageMeta.last());
        }

        @Test
        @DisplayName("an empty result is a last page, not an absent one")
        void emptyIsStillAPage() {
            CursorPage<String> p = page(10, 0);
            assertThat(p.data()).isEmpty();
            assertThat(p.meta()).isNotNull();
            assertThat(p.meta().hasMore()).isFalse();
        }

        /**
         * Walking the whole list must visit every row exactly once. This is the
         * property all the individual assertions above are really about, and it
         * is the one that would have caught any of them.
         */
        @Test
        @DisplayName("paging all the way through visits every row exactly once")
        void fullTraversalLosesNothing() {
            int limit = 7;                       // deliberately not a divisor of 100
            List<String> seen = new ArrayList<>();
            Cursor at = null;

            for (int guard = 0; guard < 100; guard++) {
                int from = at == null ? 0 : (int) at.id();
                List<String> fetched = ROWS.subList(from, Math.min(from + PageLimit.fetchSize(limit), ROWS.size()));
                CursorPage<String> p =
                        CursorPage.of(fetched, limit, r -> new Cursor(r, Long.parseLong(r.substring(3))));
                seen.addAll(p.data());
                if (!p.meta().hasMore()) {
                    break;
                }
                at = Cursor.decode(p.meta().nextCursor());
            }

            assertThat(seen).as("no row skipped, no row repeated").isEqualTo(ROWS);
        }

        @Test
        @DisplayName("a complete list carries no meta at all")
        void completeListHasNoMeta() {
            // CONVENTIONS.md §6: absence of meta is the signal that a list is
            // whole. An empty meta would say something different.
            assertThat(CursorPage.complete(List.of("a", "b")).meta()).isNull();
        }

        @Test
        @DisplayName("the returned page cannot be mutated by its caller")
        void pageIsImmutable() {
            List<String> mutable = new ArrayList<>(List.of("a", "b"));
            CursorPage<String> p = CursorPage.of(mutable, 5, r -> new Cursor(r, 1));
            mutable.add("c");
            assertThat(p.data()).containsExactly("a", "b");
        }
    }
}
