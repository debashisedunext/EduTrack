package com.edunext.edutrack.api.feature.imports;

import java.util.UUID;

/**
 * B-034 · the staged upload this request names is not there — a 422.
 *
 * <p>The wizard spans four requests and the file is uploaded in the first, so
 * every step after it addresses a staging entry rather than a row. This is what
 * happens when that entry cannot be found.
 *
 * <h2>Two causes, one condition</h2>
 *
 * <p>The {@code uploadId} may have expired ({@code edutrack.imports.staging-ttl},
 * thirty minutes — a wizard left open over lunch), or it may name a sheet other
 * than the one staged under it. They read as two things and they are one: the
 * request describes a staging entry the server does not have, and the remedy for
 * both is to go back to step 2 and upload again. Giving them separate
 * {@code type}s would let the screen write two sentences that both end "upload
 * the file again".
 *
 * <p><b>Expired and never-existed are deliberately indistinguishable.</b>
 * {@link InMemoryImportStagingStore} purges on access, so by the time the lookup
 * misses there is nothing left to say which it was — and an id the caller
 * invented is not a case worth a different message anyway.
 *
 * <h2>422 and not 404</h2>
 *
 * <p>The id is in the body, not the path. The request is well-formed JSON of the
 * declared shape and what is wrong is that it refers to something absent, which
 * is the same reading {@link UnknownImportFieldException} takes for a field the
 * schema does not declare. A 404 here would also be ambiguous with the one the
 * path's {@code schema} segment answers, and those two need different remedies.
 *
 * @param staged the sheet actually held under this id, or {@code null} when the
 *               upload itself is gone. On the body so the screen can say which
 *               sheet it would have validated rather than only that something
 *               disagreed
 */
class ImportUploadNotAvailableException extends RuntimeException {

    private final UUID uploadId;
    private final String requested;
    private final String staged;

    /** The upload is gone — expired, or never staged on this instance. */
    static ImportUploadNotAvailableException expired(UUID uploadId) {
        return new ImportUploadNotAvailableException(uploadId, null, null,
                "The uploaded file is no longer available — it may have expired. "
                        + "Go back to step 2 and upload it again. Nothing has been written.");
    }

    /**
     * The upload is there and holds a different sheet.
     *
     * <p>Reachable when a client keeps an {@code uploadId} across a sheet change
     * — the selector re-posts the file and supersedes the old id, so a request
     * pairing the old id with the new sheet name is a client that fell out of
     * step. Validating the staged sheet regardless would show a preview of one
     * sheet under the heading of another.
     */
    static ImportUploadNotAvailableException wrongSheet(UUID uploadId, String requested,
                                                        String staged) {
        return new ImportUploadNotAvailableException(uploadId, requested, staged,
                "The upload holds the '" + staged + "' sheet, not '" + requested
                        + "'. Go back to step 2 and choose the sheet again. "
                        + "Nothing has been written.");
    }

    private ImportUploadNotAvailableException(UUID uploadId, String requested, String staged,
                                              String message) {
        super(message);
        this.uploadId = uploadId;
        this.requested = requested;
        this.staged = staged;
    }

    UUID uploadId() {
        return uploadId;
    }

    String requestedSheet() {
        return requested;
    }

    String stagedSheet() {
        return staged;
    }
}
