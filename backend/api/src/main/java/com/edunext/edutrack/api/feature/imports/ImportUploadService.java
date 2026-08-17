package com.edunext.edutrack.api.feature.imports;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * B-032 · blueprint §4B.3 step 2 — take a file, read one sheet, stage it.
 *
 * <p><b>This service holds no repository and cannot reach one.</b> Step 2 is on
 * the same side of §4B.3's line as step 4: an upload has changed nothing about
 * any client, and a user who abandons the wizard here leaves no trace beyond a
 * staged file that expires. {@code ImportEngineIsolationTest} enforces the same
 * absence across the package.
 *
 * <p>The order of the checks is the design. Size is compared before the bytes are
 * looked at, the type before anything is parsed, and the row cap during the
 * parse — so the cost of a refusal always matches the cost of finding out, and
 * never the cost of the file.
 */
@Service
class ImportUploadService {

    private final ImportSchemaRegistry registry;
    private final ImportFileParser parser;
    private final ImportStagingStore staging;
    private final ImportUploadLimits limits;
    private final Clock clock;

    @Autowired
    ImportUploadService(ImportSchemaRegistry registry,
                        ImportFileParser parser,
                        ImportStagingStore staging,
                        ImportUploadLimits limits) {
        this(registry, parser, staging, limits, Clock.systemUTC());
    }

    /** Test seam — the staging timestamp is the one thing here that is not a pure function of the input. */
    ImportUploadService(ImportSchemaRegistry registry,
                        ImportFileParser parser,
                        ImportStagingStore staging,
                        ImportUploadLimits limits,
                        Clock clock) {
        this.registry = registry;
        this.parser = parser;
        this.staging = staging;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * @param schemaKey      the URL segment — resolved first, so an unregistered
     *                       schema is a 404 before a byte of the body matters
     * @param fileName       as uploaded; path segments already stripped by the caller
     * @param size           the declared length, checked before {@code content} is read
     * @param content        the file
     * @param requestedSheet which sheet, or {@code null} for the first
     * @param replaces       an earlier {@code uploadId} this one supersedes, or {@code null}
     */
    ImportDtos.Upload upload(String schemaKey, String fileName, long size, byte[] content,
                             String requestedSheet, UUID replaces) {
        ImportSchemaDefinition definition = registry.resolve(schemaKey);

        if (size > limits.maxBytes()) {
            throw ImportLimitExceededException.bytes(limits.maxBytes(), size);
        }

        ParsedSheet parsed = parser.parse(fileName, content, requestedSheet);

        // Released before the new one is staged rather than after, so switching
        // sheets in a workbook with several cannot walk a user into the store's
        // concurrency ceiling one attempt at a time. Unknown ids are a no-op —
        // this is housekeeping, not a precondition, and failing an upload because
        // the previous one had already expired would be absurd.
        if (replaces != null) {
            staging.discard(replaces);
        }

        StagedUpload upload = new StagedUpload(
                UUID.randomUUID(),
                fileName,
                parsed.sheets(),
                parsed.sheet(),
                parsed.headers(),
                parsed.rows(),
                Instant.now(clock));
        staging.stage(upload);

        ImportMapping suggested = HeaderMatcher.suggest(definition.fields(), parsed.headers());

        return new ImportDtos.Upload(
                upload.uploadId(),
                upload.fileName(),
                upload.sheets(),
                upload.sheet(),
                upload.headers(),
                upload.rowCount(),
                suggested.targetToSource());
    }
}
