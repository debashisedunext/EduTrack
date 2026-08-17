package com.edunext.edutrack.api.feature.imports;

import java.util.List;
import java.util.Set;

/**
 * B-033 · the preset names a target field this schema does not declare — a 422.
 *
 * <h2>Why this is refused rather than dropped</h2>
 *
 * <p>A preset is saved once and applied weeks later, against a file nobody is
 * looking at today. Silently dropping the unknown key is the tempting option and
 * is the worse one by a distance: the preset then <em>looks</em> complete in the
 * picker, applies cleanly, and the column it was supposed to map is simply not
 * imported — which surfaces at step 4 as a required column missing, or worse, at
 * step 5 as four hundred clients created with no support email and nothing on
 * screen ever having said so.
 *
 * <p>Refusing at save time puts the complaint in front of the one person who
 * knows what they meant, at the one moment they are looking at the mapping.
 *
 * <p><b>422 rather than 400.</b> The body is well-formed JSON of the declared
 * shape; what is wrong is that it refers to something that does not exist, which
 * is the distinction A-020's problem-detail vocabulary draws between a malformed
 * request and an unprocessable one. The realistic cause is not a typo but a
 * preset built against an older registration, so the response lists what the
 * schema does declare.
 */
class UnknownImportFieldException extends RuntimeException {

    private final String schemaKey;
    private final List<String> unknownFields;
    private final List<String> declaredFields;

    UnknownImportFieldException(String schemaKey, Set<String> unknownFields,
                                List<String> declaredFields) {
        super("The '" + schemaKey + "' import declares no field called "
                + String.join(", ", unknownFields)
                + ". A mapping may only name fields the schema has: "
                + String.join(", ", declaredFields) + ".");
        this.schemaKey = schemaKey;
        this.unknownFields = List.copyOf(unknownFields);
        this.declaredFields = List.copyOf(declaredFields);
    }

    String schemaKey() {
        return schemaKey;
    }

    List<String> unknownFields() {
        return unknownFields;
    }

    List<String> declaredFields() {
        return declaredFields;
    }
}
