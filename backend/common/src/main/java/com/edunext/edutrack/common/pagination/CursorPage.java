package com.edunext.edutrack.common.pagination;

import java.util.List;
import java.util.function.Function;

/**
 * A-053 · one page of a cursor-paged list — {@code { data, meta }}.
 *
 * <p>The envelope blueprint §1652 specifies, and the place the fetch-one-extra
 * boundary is decided <b>once</b>.
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * <p>Every paged endpoint performs the same three steps: select one row more
 * than the page size, notice whether that extra row arrived, and drop it before
 * answering. Each step is trivial and the combination is where the mistakes are:
 *
 * <ul>
 *   <li>Returning the extra row — the page is one longer than the caller asked
 *       for, and the next page starts after a row they have already seen, so it
 *       is silently skipped.</li>
 *   <li>Building {@code nextCursor} from the extra row rather than the last
 *       returned one — the same skip, from the other direction.</li>
 *   <li>Reporting {@code hasMore} from {@code rows.size() == limit} — true on
 *       the final page whenever the total is an exact multiple of the limit, so
 *       the client makes one more request and gets nothing.</li>
 * </ul>
 *
 * <p>None of these fail loudly. They surface as "a ticket went missing", weeks
 * later, on a list nobody suspects. Four hand-written implementations is four
 * chances to get it wrong; this is one.
 *
 * @param data the page, never longer than the requested limit
 * @param meta where to resume, and whether there is anything to resume to
 */
public record CursorPage<T>(List<T> data, PageMeta meta) {

    /**
     * Build a page from rows fetched with {@link PageLimit#fetchSize}.
     *
     * <p>Call it with exactly what the query returned — do not trim first. The
     * extra row is the evidence, and this method is what consumes it.
     *
     * <pre>{@code
     * int limit = PageLimit.clamp(requestedLimit);
     * List<Ticket> rows = repo.after(Cursor.decode(cursor), PageLimit.fetchSize(limit));
     * return CursorPage.of(rows, limit, t -> new Cursor(t.reportedAt().toString(), t.id()));
     * }</pre>
     *
     * @param fetched   up to {@code limit + 1} rows, in the endpoint's sort order
     * @param limit     the clamped page size the caller asked for
     * @param cursorOf  how to name a row — applied to the last <em>returned</em>
     *                  row, never to the extra one
     */
    public static <T> CursorPage<T> of(List<T> fetched, int limit, Function<T, Cursor> cursorOf) {
        if (fetched.size() <= limit) {
            // Fewer rows than we asked for means the table had no more to give.
            // This is the only honest source of "last page" — see PageMeta.
            return new CursorPage<>(List.copyOf(fetched), PageMeta.last());
        }
        List<T> page = List.copyOf(fetched.subList(0, limit));
        T lastReturned = page.get(page.size() - 1);
        return new CursorPage<>(page, new PageMeta(cursorOf.apply(lastReturned).encode(), true));
    }

    /**
     * A complete list, with no {@code meta}.
     *
     * <p>For the fourteen endpoints CONVENTIONS.md §6 exempts because a product
     * constraint already bounds them — 11 task types, 20 attachments, one
     * project's team. The null meta is the signal that there is nothing more,
     * and it is deliberately distinguishable from a page that happens to be
     * short.
     */
    public static <T> CursorPage<T> complete(List<T> all) {
        return new CursorPage<>(List.copyOf(all), null);
    }
}
