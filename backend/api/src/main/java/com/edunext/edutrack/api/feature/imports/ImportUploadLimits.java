package com.edunext.edutrack.api.feature.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B-032 · blueprint §4B.3's step-2 caps, in one place.
 *
 * <p>Held as {@code @Value} injection rather than as a
 * {@code @ConfigurationProperties} record because
 * {@link InMemoryImportStagingStore} in this same package already reads
 * {@code edutrack.imports.*} that way, and the API application registers each
 * properties record by hand — a second {@code @Configuration} class whose only
 * job is one {@code @EnableConfigurationProperties} is a file to maintain for no
 * behaviour.
 *
 * <p>The three limits are not interchangeable, and the third is the one that is
 * easy to leave out:
 *
 * <ul>
 *   <li><b>bytes</b> — refused before the file is read at all, so an oversized
 *       upload costs a size comparison rather than a parse.
 *   <li><b>rows</b> — §4B.3's 5,000. Enforced <em>during</em> the parse: see
 *       {@link XlsxSheetReader}, which stops at the first row past the limit
 *       rather than reading a million and then counting them.
 *   <li><b>columns</b> — not in the blueprint, and present because the other two
 *       do not bound the work. A sheet is allowed 16,384 columns; 5,000 rows of
 *       them is eighty million cells, built out of a source file small enough to
 *       pass the byte check comfortably. The row cap alone would let that
 *       through, because it is only reached after each row has been assembled.
 * </ul>
 */
@Component
class ImportUploadLimits {

    /**
     * 5 MB, §4B.3. Below {@code spring.servlet.multipart.max-file-size} (10 MB),
     * which means a file between the two reaches this code and is refused with a
     * problem document naming the real limit, instead of being cut off by the
     * container with a message about a different number.
     */
    private final long maxBytes;

    private final int maxRows;

    private final int maxColumns;

    ImportUploadLimits(
            @Value("${edutrack.imports.max-file-bytes:5242880}") long maxBytes,
            @Value("${edutrack.imports.max-rows:5000}") int maxRows,
            @Value("${edutrack.imports.max-columns:200}") int maxColumns) {
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
        this.maxColumns = maxColumns;
    }

    long maxBytes() {
        return maxBytes;
    }

    int maxRows() {
        return maxRows;
    }

    int maxColumns() {
        return maxColumns;
    }
}
