package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-030 · the registry. Adding an importable entity is adding a bean.
 *
 * <p>Spring injects every {@link ImportSchemaDefinition} on the context, so a
 * new registration needs no edit here, no {@code switch}, and no route. That
 * absence is the design: a registry you have to remember to update is a
 * dispatch table, and a dispatch table is what a second implementation grows
 * out of.
 *
 * <p>The constructor validates every registration and <b>refuses to start</b>
 * if one is malformed. A duplicate key or a nullable natural key surfaces as a
 * failed context refresh at boot rather than as a wrong verdict on somebody's
 * 5,000-row import — the two are separated by weeks and by whose problem it is.
 */
@Component
public class ImportSchemaRegistry {

    private final Map<String, ImportSchemaDefinition> byKey;
    private final Map<String, ImportSchemaDefinition> byEntityCode;

    ImportSchemaRegistry(List<ImportSchemaDefinition> registrations) {
        Map<String, ImportSchemaDefinition> map = new LinkedHashMap<>();
        Map<String, ImportSchemaDefinition> entities = new LinkedHashMap<>();
        for (ImportSchemaDefinition schema : registrations) {
            validate(schema);
            ImportSchemaDefinition clash = map.put(schema.key(), schema);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two import schemas registered under '" + schema.key() + "': "
                                + clash.getClass().getName() + " and " + schema.getClass().getName());
            }
            ImportSchemaDefinition entityClash = entities.put(schema.entityCode(), schema);
            if (entityClash != null) {
                // Same class of boot-time refusal as a duplicate key, and it
                // became reachable in B-036: an import_batches row stores the
                // entity code and the error report is resolved back through it,
                // so two registrations sharing one would make a stored run
                // ambiguous about which schema wrote it.
                throw new IllegalStateException(
                        "Two import schemas registered under entity code '" + schema.entityCode()
                                + "': " + entityClash.getClass().getName() + " and "
                                + schema.getClass().getName());
            }
        }
        this.byKey = Map.copyOf(map);
        this.byEntityCode = Map.copyOf(entities);
    }

    /**
     * @throws UnknownImportSchemaException so the caller answers 404 — an
     *         unregistered path segment means the resource is not there
     */
    public ImportSchemaDefinition resolve(String key) {
        ImportSchemaDefinition schema = key == null ? null : byKey.get(key);
        if (schema == null) {
            throw new UnknownImportSchemaException(key, byKey.keySet());
        }
        return schema;
    }

    public Set<String> keys() {
        return byKey.keySet();
    }

    /**
     * B-036 · the registration a stored run belongs to — {@code CLIENT} →
     * {@code ClientImportSchema}.
     *
     * <p>Resolution by {@link ImportSchemaDefinition#entityCode()} rather than by
     * {@link ImportSchemaDefinition#key()} because that is what
     * {@code import_batches} stores, and the two are deliberately different
     * things: the key is a URL segment a client depends on, the entity code is a
     * stored discriminator. Reconstructing one from the other would be the
     * collapse that separation exists to prevent.
     *
     * <p>Empty rather than a throw, because the caller is a batch row read long
     * after the run: a registration removed from a later release leaves rows
     * behind, and their reports become unavailable rather than a 500 on a
     * history screen.
     */
    Optional<ImportSchemaDefinition> byEntityCode(String entityCode) {
        return Optional.ofNullable(entityCode).map(byEntityCode::get);
    }

    private static void validate(ImportSchemaDefinition schema) {
        String key = schema.key();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(schema.getClass().getName() + " has no key()");
        }
        if (schema.entityCode() == null || schema.entityCode().isBlank()) {
            throw new IllegalStateException(key + " has no entityCode()");
        }
        List<ImportField> fields = schema.fields();
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(key + " declares no fields");
        }

        Set<String> names = new java.util.HashSet<>();
        for (ImportField field : fields) {
            if (!names.add(field.name())) {
                // Two fields with one name means the mapping object cannot
                // address them separately — one silently shadows the other.
                throw new IllegalStateException(key + " declares field '" + field.name() + "' twice");
            }
        }

        ImportField natural = schema.naturalKey();
        if (natural == null || !fields.contains(natural)) {
            throw new IllegalStateException(
                    key + "'s naturalKey() is not one of its fields()");
        }
        if (!natural.required()) {
            // See ImportSchemaDefinition#naturalKey: an optional natural key
            // makes create-versus-update unanswerable for the blank rows.
            throw new IllegalStateException(
                    key + "'s naturalKey '" + natural.name() + "' must be required()");
        }
    }
}
