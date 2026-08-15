package com.edunext.edutrack.common.pagination;

/**
 * A-053 · the one place {@code ?limit=} is interpreted.
 *
 * <p>CONVENTIONS.md §6 fixes the numbers: default 50, maximum 200. They are
 * repeated today in every controller that pages, as private constants, which is
 * how a maximum becomes 200 in three places and 100 in a fourth without anyone
 * deciding to change it.
 *
 * <p><b>Out-of-range clamps rather than rejects.</b> {@code limit=1000} is
 * answered with 200 rows and a {@code nextCursor}, not a 400 — the caller gets
 * a correct, complete traversal either way, and the cap is the server's business
 * rather than something the caller has to know. Zero and negatives mean the
 * caller expressed no preference and get the default. This matches how the
 * existing controllers already behave; it is written down here so it keeps
 * matching.
 *
 * <p>The maximum is not advisory. It is what stops one request asking for the
 * whole ticket table, which A-073 targets at 50,000 rows.
 */
public final class PageLimit {

    public static final int DEFAULT = 50;
    public static final int MAX = 200;

    private PageLimit() {
    }

    /**
     * @param requested the raw {@code ?limit=} value, or {@code null} when absent
     * @return a limit within {@code [1, MAX]}
     */
    public static int clamp(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT;
        }
        return Math.min(requested, MAX);
    }

    /**
     * How many rows to actually SELECT for a page of {@code limit}.
     *
     * <p>One more than asked for. Whether there is a next page is a fact about
     * a row you must not return, and the alternatives are worse: a second
     * {@code COUNT(*)} over the same predicate doubles the query cost and can
     * still disagree with the page under concurrent writes, and "a full page
     * means there is probably more" reports {@code hasMore} on the last page
     * whenever the total is an exact multiple of the limit.
     *
     * <p>Pair with {@link CursorPage#of} rather than trimming by hand — the
     * off-by-one lives in the trim, not here.
     */
    public static int fetchSize(int limit) {
        return limit + 1;
    }
}
