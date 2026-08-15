package com.edunext.edutrack.common.pagination;

/**
 * A-053 · the {@code meta} of a paged response, in the one shape.
 *
 * <p>CONVENTIONS.md §6: {@code meta.nextCursor} and {@code meta.hasMore}. Two
 * fields, and both are needed — {@code nextCursor} alone cannot express "this
 * was the last page" without overloading null, and {@code hasMore} alone does
 * not say where to resume.
 *
 * <p><b>No {@code totalCount}.</b> One existing meta record carries one and the
 * others do not, so this is a decision rather than an omission. A total over a
 * cursor-paged list costs a second {@code COUNT(*)} across the whole predicate —
 * the exact query shape CLAUDE.md forbids for dashboards and A-050 built summary
 * tables to avoid — and it is stale the moment it is computed, because the point
 * of a cursor is that the table is being written to underneath the reader. A
 * screen that genuinely needs a total should read it from the summary tables,
 * where it is pre-aggregated and where its staleness is visible in
 * {@code computed_at}.
 *
 * <p>Adopting this therefore means {@code CalendarDtos.Meta} loses its
 * {@code totalCount}, which is a contract change and Ayush's to make. It is not
 * done as part of A-053.
 *
 * <p><b>A list with no {@code meta} at all is complete.</b> CONVENTIONS.md §6
 * lists fourteen endpoints that deliberately do not page because a product
 * constraint already bounds them, and absence of {@code meta} is the signal.
 * Do not emit an empty one to be tidy — it says something different.
 *
 * @param nextCursor where to resume, or {@code null} on the last page
 * @param hasMore    whether another row exists beyond this page
 */
public record PageMeta(String nextCursor, boolean hasMore) {

    /** The last page: nothing beyond it, nowhere to resume. */
    public static PageMeta last() {
        return new PageMeta(null, false);
    }
}
