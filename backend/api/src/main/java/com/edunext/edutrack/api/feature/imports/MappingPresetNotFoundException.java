package com.edunext.edutrack.api.feature.imports;

/**
 * B-033 · no preset with that id under that schema — a 404.
 *
 * <p><b>Scoped by schema, not only by id.</b> A preset saved for the resource
 * schema is absent from the client schema's path rather than deletable through
 * it: the nesting in {@code /imports/{schema}/mapping-presets/{presetId}} is
 * real, and the repository's {@code DELETE} carries {@code schema_key} in its
 * {@code WHERE} for that reason.
 *
 * <p>Note this is <em>not</em> the row-scoping rule at work — presets are
 * org-wide and every Admin sees all of them. It is ordinary nesting, of the same
 * kind {@code /clients/{clientId}/contacts/{contactId}} applies.
 */
class MappingPresetNotFoundException extends RuntimeException {

    private final String schemaKey;
    private final long presetId;

    MappingPresetNotFoundException(String schemaKey, long presetId) {
        super("No mapping preset " + presetId + " saved for the '" + schemaKey + "' import.");
        this.schemaKey = schemaKey;
        this.presetId = presetId;
    }

    String schemaKey() {
        return schemaKey;
    }

    long presetId() {
        return presetId;
    }
}
