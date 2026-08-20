package com.edunext.edutrack.api.feature.reports.export;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * B-062 · the rows an export is written from, as a source rather than a list.
 *
 * <h2>Why the signature changed</h2>
 *
 * <p>A-064 wrote {@link ReportExporter#write} against
 * {@code List<Map<String, Object>>} and explained at length that every
 * implementation streams into an {@code OutputStream} so nothing larger than
 * SXSSF's row window is resident. Both halves of that could not be true at
 * once: the parameter itself required every row in the heap before the first
 * byte was written, so the streaming was of the *output* only.
 *
 * <p>That is not an abstract complaint. It is the reason a second complete xlsx
 * and CSV writer existed in {@code feature/masters/resources} — B-010 could not
 * use the engine, because {@code ResourceService.streamAll} hands out batches of
 * 500 and the engine wanted the whole directory. The consequence was three
 * copies of the spreadsheet formula-injection guard, which is a security control
 * and the worst thing in this codebase to have three copies of: a fix applied to
 * one of them is applied to neither of the others, and nothing fails.
 *
 * <p>So the engine takes a source it can pull from. A caller that already holds
 * its rows passes {@link #of(List)} and nothing about it changes; a caller that
 * streams passes one that streams.
 *
 * <h2>Push, not {@code Iterator} or {@code Stream}</h2>
 *
 * <p>{@code forEach} matches the shape the one streaming producer already has —
 * {@code streamAll(filter, sink)} drives a keyset cursor in a loop and calls a
 * {@link Consumer} per batch. An {@code Iterator} would mean inverting that
 * loop, which for a cursor-paged query means holding the paging state in a
 * hand-written iterator; a {@code Stream} would mean the same plus a spliterator
 * nobody would split. Neither buys anything an exporter wants: an exporter walks
 * every row exactly once, in order, and never looks back.
 *
 * <h2>The count is optional, and that is the point</h2>
 *
 * <p>A list knows how many rows it has. A cursor over MySQL does not, and asking
 * would be a second {@code COUNT(*)} over the same filter — the query the export
 * exists to avoid running twice.
 *
 * <p>So {@link #knownCount()} answers {@code null} rather than a guess, and the
 * one exporter that wants a total up front — the PDF, which prints a row count
 * under its heading — prints it when it is known and prints the true count under
 * the table either way. A source that lied here would put a wrong figure on the
 * one export shaped to be handed to somebody.
 */
@FunctionalInterface
public interface ExportRows {

    /**
     * Presents every row to {@code sink}, in order, exactly once.
     *
     * <p>May be called only once per instance — a streaming implementation
     * consumes a cursor, and there is no exporter that needs a second pass.
     */
    void forEach(Consumer<Map<String, Object>> sink);

    /**
     * How many rows there are, or {@code null} when that is not knowable without
     * running the query twice.
     *
     * @see #of(List)
     */
    default Integer knownCount() {
        return null;
    }

    /** The rows a caller already holds — every report runner, today. */
    static ExportRows of(List<Map<String, Object>> rows) {
        List<Map<String, Object>> held = rows == null ? List.of() : rows;
        return new ExportRows() {

            @Override
            public void forEach(Consumer<Map<String, Object>> sink) {
                held.forEach(sink);
            }

            @Override
            public Integer knownCount() {
                return held.size();
            }
        };
    }

    /**
     * A source fed in batches, for a producer that pages rather than materialises.
     *
     * <p>Written as a named factory rather than left to each caller's lambda so
     * that "a batch is flattened into rows" is stated once. A caller doing it
     * inline is one nested loop away from emitting a batch as a single row, and
     * the file would look plausible.
     *
     * @param batches drives the underlying cursor, handing each page to the sink
     */
    static ExportRows batched(Consumer<Consumer<List<Map<String, Object>>>> batches) {
        return sink -> batches.accept(batch -> batch.forEach(sink));
    }
}
