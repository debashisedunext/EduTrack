package com.edunext.edutrack.api.feature.tickets.attachments;

/**
 * C-027 · blueprint §4B.4's three caps, as one value.
 *
 * <p>They travel together because they are only meaningful together: the
 * per-ticket total has to cover the per-file cap or the per-file cap is
 * unreachable, and a caller that could set one without the other would be able
 * to reach that state in two valid-looking steps. Every producer of this record
 * goes through {@link #of}, so no such combination exists anywhere in the
 * application — not from the settings table, not from the properties fallback,
 * and not from the {@code PUT}.
 *
 * <h2>What is <em>not</em> checked here</h2>
 *
 * <p>The real ceiling on {@code maxFileBytes} is the servlet container's
 * multipart limit, because it refuses an oversized body during parsing before
 * any of this feature's code runs — a setting above it would produce a generic
 * container 413 instead of §4B.4's worded one, and would look like a limit that
 * simply does not work. That check needs the running configuration and lives in
 * {@link AttachmentSettingsService}; the bounds below are the ones that hold
 * whatever the deployment looks like, and they match the {@code CHECK}
 * constraints in {@code V20260815_1140__attachment_settings.sql} so a bad value
 * is refused with a sentence rather than by a constraint violation.
 *
 * @param maxFileBytes   §4B.4's "10 MB per file by default"
 * @param maxTicketBytes §4B.4's 50 MB per ticket
 * @param maxFiles       §4B.4's 20 files per ticket
 */
record AttachmentLimits(long maxFileBytes, long maxTicketBytes, int maxFiles) {

    /**
     * 100 MB. An upper bound exists because {@link AttachmentService} reads the
     * whole part into a {@code byte[]} to sniff, strip and store it, so the
     * per-file cap is also this feature's per-request heap cost multiplied by
     * however many uploads are in flight.
     */
    static final long MAX_FILE_BYTES_CEILING = 100L * 1024 * 1024;

    /**
     * 200. The per-ticket count bounds a query that loads every live attachment
     * row on every upload, and a gallery strip that renders every one of them.
     * Twenty is §4B.4's number; this is the point past which an administrator
     * has broken the ticket page rather than configured it.
     */
    static final int MAX_FILES_CEILING = 200;

    /**
     * The only way to build one.
     *
     * <p>Zero is deliberately not "unlimited" — it is "no attachment may ever be
     * uploaded", and an administrator who meant the first would switch the
     * feature off everywhere with nothing saying so. Unlimited is not
     * expressible at all: §4B.4 has no such state, and an uncapped upload path
     * is not a setting.
     *
     * @throws InvalidAttachmentLimitsException with a message naming the value
     *                                          that is wrong and what it must be
     */
    static AttachmentLimits of(long maxFileBytes, long maxTicketBytes, int maxFiles) {
        if (maxFileBytes < 1 || maxFileBytes > MAX_FILE_BYTES_CEILING) {
            throw new InvalidAttachmentLimitsException(
                    "maxFileBytes must be between 1 byte and " + Bytes.human(MAX_FILE_BYTES_CEILING)
                            + "; " + Bytes.human(maxFileBytes) + " is outside it.");
        }
        if (maxFiles < 1 || maxFiles > MAX_FILES_CEILING) {
            throw new InvalidAttachmentLimitsException(
                    "maxFiles must be between 1 and " + MAX_FILES_CEILING + "; " + maxFiles + " is outside it.");
        }
        if (maxTicketBytes < maxFileBytes) {
            // Not merely untidy. A per-ticket total below the per-file cap makes
            // the per-file cap unreachable: every file large enough to test it is
            // refused by the ticket total first, with a message telling the user
            // to remove an attachment from a ticket that may have none.
            throw new InvalidAttachmentLimitsException(
                    "maxTicketBytes (" + Bytes.human(maxTicketBytes) + ") must be at least maxFileBytes ("
                            + Bytes.human(maxFileBytes) + "), or no file that size could ever be attached.");
        }
        return new AttachmentLimits(maxFileBytes, maxTicketBytes, maxFiles);
    }

    /**
     * Binary units with decimal labels, the one spelling this product uses.
     *
     * <p>Shared with {@link AttachmentLimitExceededException} and with
     * {@code formatFileSize} in {@code components/ui/attachments.ts}: a user who
     * hits a cap on the client and then on the server must not be shown two
     * different numbers for one rule.
     */
    static final class Bytes {

        private Bytes() {
        }

        static String human(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            double value = bytes / 1024.0;
            String[] units = {"KB", "MB", "GB"};
            int unit = 0;
            while (value >= 1024 && unit < units.length - 1) {
                value /= 1024;
                unit++;
            }
            return number(value) + " " + units[unit];
        }

        /**
         * One decimal below 10 ("9.4 MB"), none above ("412 KB") — and
         * <b>no trailing {@code .0}</b>.
         *
         * <p>That last clause is the whole reason this is a method. The obvious
         * spelling, {@code Math.round(value * 10) / 10.0}, renders a 2 MB cap as
         * {@code "2.0 MB"} while {@code formatFileSize} in
         * {@code components/ui/attachments.ts} renders the same number as
         * {@code "2 MB"} — JavaScript drops an integral decimal and Java does
         * not. Found by running the server, not by a test: every value the tests
         * happened to use was either ≥ 10 (which takes the integer branch
         * anyway) or genuinely fractional. It is small, and it is exactly the
         * kind of small that makes a user hitting the cap on the client and then
         * on the server believe they are looking at two different rules.
         */
        private static String number(double value) {
            if (value >= 10) {
                return String.valueOf(Math.round(value));
            }
            double oneDecimal = Math.round(value * 10) / 10.0;
            return oneDecimal == Math.rint(oneDecimal)
                    ? String.valueOf((long) oneDecimal)
                    : String.valueOf(oneDecimal);
        }
    }
}
