package com.edunext.edutrack.api.feature.imports;

/**
 * B-037 · 422 when the registration that wrote a run is no longer installed.
 *
 * <p>{@code import_batches.entity} is a stored discriminator, and a batch
 * outlives the release that created it. If a later deploy removes a registration
 * — or a rollback lands before one was added — its historical rows are still
 * there and nothing on the context knows how to take them back.
 *
 * <p><b>Reads still work; only the reversal refuses.</b> B-036's error report
 * falls back to a plainer file name when this happens, because a report already
 * written is a file and reading a file needs no registration. A reversal is not
 * like that: it is entity-specific behaviour, {@link ImportSchemaDefinition} is
 * the only place it exists, and there is nothing sensible to fall back to.
 * Guessing — deleting from {@code clients} because most imports are clients —
 * is how a rollback destroys the wrong table.
 *
 * <p>422 rather than 404: the batch is right there in the history panel the
 * caller is reading, and a 404 would say it is not. It is also not the caller's
 * mistake, which is why the message names a deployment rather than the request —
 * the person who can act on it is an operator, and the sentence has to reach
 * them through whoever pressed the button.
 */
class ImportSchemaUnavailableException extends RuntimeException {

    private final long batchId;
    private final String entityCode;

    ImportSchemaUnavailableException(long batchId, String entityCode) {
        super("Import #" + batchId + " was made by the '" + entityCode
                + "' importer, which this version of EduTrack does not have."
                + " It cannot be reversed here.");
        this.batchId = batchId;
        this.entityCode = entityCode;
    }

    long batchId() {
        return batchId;
    }

    String entityCode() {
        return entityCode;
    }
}
