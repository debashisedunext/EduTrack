package com.edunext.edutrack.api.feature.imports;

/**
 * B-032 · the upload is bigger than blueprint §4B.3 allows — answered 413.
 *
 * <p>Carries the limit that was hit, the ceiling and the value that hit it,
 * because "your file is too large" is not a message a user can act on. They have
 * a file in front of them and no way to tell which of its properties is the
 * problem: "5,412 rows, and the limit is 5,000" tells them to split it, and "212
 * columns" tells them they have uploaded the wrong sheet entirely.
 *
 * <p>{@code limit} is a machine name — {@code bytes}, {@code rows},
 * {@code columns} — so the screen can react without parsing English.
 */
class ImportLimitExceededException extends RuntimeException {

    private final String limit;
    private final long ceiling;
    private final long actual;

    private ImportLimitExceededException(String limit, long ceiling, long actual, String message) {
        super(message);
        this.limit = limit;
        this.ceiling = ceiling;
        this.actual = actual;
    }

    static ImportLimitExceededException bytes(long ceiling, long actual) {
        return new ImportLimitExceededException("bytes", ceiling, actual,
                "The file is %s and the limit is %s.".formatted(size(actual), size(ceiling)));
    }

    /**
     * {@code actual} is "at least this many", not a total.
     *
     * <p>The parse stops at the first row past the ceiling rather than reading to
     * the end to produce an exact count — which is the whole point of the cap, so
     * the message says "more than 5,000" rather than inventing a number.
     */
    static ImportLimitExceededException rows(int ceiling) {
        return new ImportLimitExceededException("rows", ceiling, ceiling + 1L,
                "The sheet has more than %,d rows, which is the limit. Split it and import the parts."
                        .formatted(ceiling));
    }

    static ImportLimitExceededException columns(int ceiling, int actual) {
        return new ImportLimitExceededException("columns", ceiling, actual,
                ("The sheet has %,d columns and the limit is %,d. "
                        + "Check that the heading row is the first row of the sheet.")
                        .formatted(actual, ceiling));
    }

    String limit() {
        return limit;
    }

    long ceiling() {
        return ceiling;
    }

    long actual() {
        return actual;
    }

    /**
     * Megabytes above a megabyte, kilobytes below it.
     *
     * <p>Fixed on MB would render a limit of 5,242,880 and a file of 5,300,000 as
     * "5.0 MB" and "5.1 MB", which is fine — and a test fixture with a small
     * ceiling as "0.0 MB and the limit is 0.0 MB", which reads as a bug in the
     * check rather than a message about the file.
     */
    private static String size(long bytes) {
        return bytes >= 1024 * 1024
                ? "%.1f MB".formatted(bytes / 1048576d)
                : "%,d KB".formatted(Math.max(1, bytes / 1024));
    }
}
