package com.edunext.edutrack.api.feature.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B-032 · the wire shapes for {@code feature/imports}.
 *
 * <p>One file per feature, like {@code ClientDtos}: the envelope wrappers are one
 * line each and giving each of them its own file makes the package harder to read
 * rather than easier.
 */
final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * {@code ImportUploadResponse.data} — what step 2 hands step 3.
     *
     * <p>Deliberately <b>not</b> the rows. A 5,000-row file would be several
     * megabytes of JSON that the mapping screen has no use for: B-033 maps
     * columns, and columns are the {@code headers} list. The rows stay staged
     * server-side and are next read by the dry run, which is the step that
     * actually looks at them.
     *
     * @param sheet    which sheet these headers describe. Stated rather than left
     *                 to be inferred as "the first" — re-posting with a chosen
     *                 sheet is how the selector works, and a client that assumed
     *                 would mislabel every read after the first
     * @param rowCount data rows, blank rows excluded, header excluded. This is the
     *                 number the user is told they are about to import, so it
     *                 counts the same rows the dry run will
     * @param suggestedMapping {@link HeaderMatcher}'s auto-match — target field →
     *                 source column, and every entry overridable at step 3
     */
    record Upload(
            UUID uploadId,
            String fileName,
            List<String> sheets,
            String sheet,
            List<String> headers,
            int rowCount,
            Map<String, String> suggestedMapping) {
    }

    record UploadResponse(Upload data) {
    }
}
