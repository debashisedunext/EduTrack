package com.edunext.edutrack.api.feature.imports;

import java.util.List;
import java.util.Set;

/**
 * B-030 · <b>the interface that makes the import wizard one engine.</b>
 *
 * <p>Blueprint §4B.3 closes with "build it once, register two schemas", and
 * CLAUDE.md restates it as a stop rule: <i>if you find yourself writing a second
 * import flow, stop.</i> This interface is where that rule is made structural
 * rather than remembered. Everything that differs between importing clients and
 * importing resources is behind these six methods; everything else — parsing,
 * mapping, validating, previewing, committing, error reporting — is written
 * once and knows nothing about either.
 *
 * <p>A registration is a Spring {@code @Component} implementing this. It needs
 * no controller, no route, no migration and no change to
 * {@link ImportSchemaRegistry}: the registry collects implementations by type.
 * That is what reduces B-038 — "resource bulk import" — from a feature to a
 * file.
 *
 * <p><b>Implementations must be stateless and thread-safe.</b> They are
 * singletons and two users can import at once.
 */
public interface ImportSchemaDefinition {

    /**
     * The URL segment — {@code clients} or {@code users}.
     *
     * <p>Must match the contract's {@code ImportSchema} enum exactly, because
     * the generated TypeScript client already emits these paths. Note it is
     * {@code users}, not {@code resources}: B-038 is titled "resource bulk
     * import" but the contract settled on {@code users}, and the URL is the
     * thing that has to be right.
     */
    String key();

    /**
     * The value stored in {@code import_batches.entity} — {@code CLIENT} or
     * {@code RESOURCE}.
     *
     * <p>Deliberately separate from {@link #key()}. The URL segment is a public
     * name a client depends on; the discriminator is a stored value the reversal
     * query in B-037 depends on. Collapsing them would mean either renaming a
     * live URL to fix a column or rewriting stored rows to fix a URL.
     */
    String entityCode();

    /** Every column, in template order. See {@link ImportField} for who reads what. */
    List<ImportField> fields();

    /**
     * The field whose value decides create-versus-update — {@code clientCode}
     * for clients.
     *
     * <p>Must be one of {@link #fields()} and must be {@link ImportField#required()};
     * {@link ImportSchemaRegistry} refuses a registration where it is not. A
     * natural key that can be blank would make "already exists?" unanswerable
     * for the rows that need it most.
     */
    ImportField naturalKey();

    /**
     * Which of these natural-key values already exist.
     *
     * <p><b>Takes the whole set and returns the whole set — never one at a
     * time.</b> The limit is 5,000 rows; asked per row this becomes 5,000
     * queries per dry run, and the dry run is the step users repeat most because
     * it is the safe one. Implementations must answer in a single query.
     *
     * <p>Read-only. Called during a dry run, which writes nothing.
     *
     * @param naturalKeyValues normalised by {@link #normaliseKey(String)}
     * @return the subset that exists, in the same normalised form
     */
    Set<String> findExisting(Set<String> naturalKeyValues);

    /**
     * Insert or update one row, stamping {@code importBatchId} for B-037.
     *
     * <p>Called only by B-035's commit job, only for rows the dry run judged
     * {@link ImportVerdict#WILL_CREATE} or {@link ImportVerdict#WILL_UPDATE},
     * and never during validation.
     *
     * <p><b>An implementation must upsert on the natural key, never insert
     * blindly.</b> That is the rule the whole feature is judged on: a user who
     * fixes six rejected rows and re-uploads the file must not end with two
     * copies of the other 494.
     */
    void upsert(ImportRow row, Long importBatchId);

    /**
     * How two spellings of the same key are recognised as one.
     *
     * <p>Applies to duplicate-in-file detection and to the existence probe
     * together, which is the only way they can agree. If {@code acme} and
     * {@code ACME} are the same client, they must be the same client in both
     * questions — otherwise a file containing both passes the duplicate check
     * and then has its two rows fight over one database row at commit.
     *
     * <p>The default is trim-and-uppercase, matching MySQL's
     * {@code utf8mb4_0900_ai_ci} collation on {@code clients.client_code}: the
     * unique index would treat them as one regardless, so the engine had better
     * agree with it rather than discover it at INSERT time.
     */
    default String normaliseKey(String rawValue) {
        return rawValue == null ? null : rawValue.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
