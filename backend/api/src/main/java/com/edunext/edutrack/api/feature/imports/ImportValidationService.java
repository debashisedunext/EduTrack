package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Service;

/**
 * B-034 · blueprint §4B.3 step 4 — take a staged file and a mapping, hand back
 * a per-row verdict. <b>Nothing is written.</b>
 *
 * <p>This is the thin half of the step. {@link ImportValidationEngine} decides
 * the verdicts and has since B-030; what was missing was a way to reach it —
 * resolving the schema, finding the file the wizard uploaded three requests ago,
 * and refusing the mappings that would produce a preview nobody could act on.
 *
 * <h2>It holds no repository, and that is structural</h2>
 *
 * <p>Like {@link ImportUploadService} beside it, and for the same reason:
 * {@code ImportEngineIsolationTest} fails the build if a persistence import
 * appears in {@link ImportValidationEngine}. The only path to the database from
 * here is {@link ImportSchemaDefinition#findExisting}, which is read-only by
 * contract. §4B.3's promise for this step is absolute — "nothing is written
 * yet" — and a promise that depends on nobody adding a convenient
 * {@code save()} later is not one.
 *
 * <h2>B-035 moved the checks out, and did not copy them</h2>
 *
 * <p>The four refusals this class used to hold privately now live in
 * {@link ImportRequestResolver}, because {@code /commit} takes the same request
 * and has to refuse it identically — see that class for the order and the
 * reasons, which are unchanged. Two copies would agree on the day they were
 * written, and the commit is the copy where disagreeing writes to the client
 * master.
 *
 * <p>What is left here is the one thing step 4 does that step 5 does not: it
 * stops. The staged upload survives, because reading the preview, going back to
 * step 3 and changing one column is the ordinary path through this screen — a
 * route that consumed the staging entry would answer "your file expired" to the
 * second attempt.
 */
@Service
class ImportValidationService {

    private final ImportRequestResolver resolver;
    private final ImportValidationEngine engine;

    ImportValidationService(ImportRequestResolver resolver, ImportValidationEngine engine) {
        this.resolver = resolver;
        this.engine = engine;
    }

    /**
     * @param schemaKey the URL segment — resolved first, so an unregistered
     *                  schema is a 404 before the body is looked at
     * @return the preview, and no side effects of any kind
     */
    ImportPreview validate(String schemaKey, ImportDtos.ValidateRequest request) {
        ImportRequestResolver.Resolved resolved = resolver.resolve(
                schemaKey, request.uploadId(), request.sheet(), request.mapping());

        return engine.validate(resolved.definition(), resolved.rows());
    }
}
