package com.edunext.edutrack.api.feature.imports;

import java.util.List;
import java.util.Map;
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
     * Which of these natural-key values already exist, and what they currently
     * hold.
     *
     * <p><b>Takes the whole set and returns the whole set — never one at a
     * time.</b> The limit is 5,000 rows; asked per row this becomes 5,000
     * queries per dry run, and the dry run is the step users repeat most because
     * it is the safe one. Implementations must answer in a single query.
     *
     * <p>Read-only. Called during a dry run, which writes nothing.
     *
     * <h2>Why the values and not just the keys — B-034</h2>
     *
     * <p>Existence alone decides {@link ImportVerdict#WILL_CREATE} versus
     * {@link ImportVerdict#WILL_UPDATE}, and that is all this returned until
     * step 4 had a screen. Blueprint §4B.3's step-4 table wants more from an
     * update than the word "update":
     *
     * <pre>
     *   │  3   │ NORTHWIND │ ♻ Will update │ Name, phone │
     * </pre>
     *
     * <p>The Message column names <em>which fields would change</em>, and
     * without it "38 will update" is not reviewable: a spreadsheet that corrects
     * six phone numbers and one that rewrites every address in the account look
     * identical, and the user is being asked to approve one of them. The
     * comparison itself is schema-agnostic and stays in
     * {@link ImportValidationEngine} — what only a registration can supply is
     * the current values.
     *
     * @param naturalKeyValues normalised by {@link #normaliseKey(String)}
     * @return the subset that exists, keyed in the same normalised form. Each
     *         value maps <em>this schema's field names</em> to the stored value
     *         as a string, formatted the way the import would read it — an ISO
     *         date for a {@link ImportFieldType#DATE}, so a comparison against
     *         an uploaded cell is meaningful. A field that is null in storage is
     *         absent from the map, matching {@link ImportRow}'s rule that
     *         "missing" has one representation.
     *         <p>An <b>empty</b> map is permitted, and means "it exists, I
     *         cannot cheaply say what is in it". The row is still
     *         {@code WILL_UPDATE}; its reason is simply null. That keeps the
     *         cost of a registration where B-030 put it — a list of columns and
     *         two short methods
     */
    Map<String, Map<String, String>> findExisting(Set<String> naturalKeyValues);

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
     * B-037 · <b>take one run back as a set.</b>
     *
     * <p>Blueprint §4B.3's closing rule and §17's mitigation: <i>every import
     * writes an {@code import_batch} row so a bad import can be identified and
     * reversed as a set.</i> The identification is {@code import_batch_id} on
     * the row; this is the reversal, and it is on the SPI rather than in the
     * engine for the reason every other method here is — the engine does not
     * know what a client is, and B-038 must not have to write a second one.
     *
     * <p><b>Only rows this run created.</b> {@link #upsert} stamps the batch id
     * on insert and never on update, so a row the run merely edited is not
     * attributed to it. That is not a simplification: there is no before image
     * anywhere, so an update is not a thing a reversal could undo. An
     * implementation that widened this to "everything the run touched" would
     * delete clients that existed before the import and merely had a phone
     * number corrected by it.
     *
     * <p><b>A row something else now references is retained, not destroyed.</b>
     * The batch is not more important than the work that has happened since:
     * failing the whole reversal because one client acquired a ticket is
     * unhelpful, and deleting the ticket's client to get the count to zero is
     * worse. Retained rows come back named, with a reason a person can read.
     *
     * <p>Called once per batch by {@link ImportReversalService}, which has
     * already refused a run that is still going and a run already reversed — so
     * an implementation does not re-check either. It runs inside no ambient
     * transaction; an implementation that deletes many rows should commit them
     * in units small enough that a surprise reference retains one row rather
     * than losing the set, exactly as {@link ImportCommitRunner} does on the way
     * in.
     *
     * <p><b>The {@code import_batches} row itself is never deleted</b> — by this
     * method or by anything else. It is the audit trail, and a reversal that
     * erased its own record would leave the master short of rows with nothing
     * anywhere explaining why.
     *
     * @param batchId the run to take back
     * @return what was removed and what was kept. Never null; a run that created
     *         nothing returns {@link ImportReversal#none()}
     */
    ImportReversal reverse(long batchId);

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
