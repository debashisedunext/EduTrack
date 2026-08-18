package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * B-037 · <b>a bad import, taken back as a set.</b>
 *
 * <p>Blueprint §4B.3's closing validation rule — <i>"every import writes an
 * {@code import_batch} row so a bad import can be identified and reversed as a
 * set"</i> — and §17's named mitigation for the risk "Client Excel import
 * silently corrupts the master". B-035 wrote the batch row and stamped
 * {@code clients.import_batch_id}; this is the half that reads them.
 *
 * <h2>What a reversal is, precisely</h2>
 *
 * <p>It deletes the rows the run <b>created</b>, and nothing else. Not the rows
 * it updated: {@code import_batch_id} is stamped on insert only, and there is no
 * before image anywhere, so an update is not something any code here could undo.
 * That limit is reported on the response as
 * {@code updatedRowsNotReverted} rather than left for a user to infer from a
 * count that does not add up — somebody who imported 412 rows and sees "12
 * deleted" is owed the sentence explaining the other 400.
 *
 * <h2>Two refusals, and they are two because the remedies are opposite</h2>
 *
 * <ul>
 *   <li>{@link ImportBatchNotFinishedException} — the job is still walking the
 *       file. Resolves itself in a moment; the screen should wait.
 *   <li>{@link ImportBatchAlreadyReversedException} — done once already. Never
 *       resolves; the screen should stop offering the button.
 * </ul>
 *
 * <p>One shared "cannot reverse" type would put a "try again" on a batch that
 * will refuse forever, which is the same argument B-035 recorded for splitting
 * {@code import-nothing-to-commit} from {@code import-rejected-rows-present}.
 *
 * <h2>Why the deletes are not this class's business</h2>
 *
 * <p>{@link ImportSchemaDefinition#reverse} does them, for the reason every
 * other method on that interface exists: this file must not learn what a client
 * is. B-038 registers resources and gets reversal without writing any of it —
 * blueprint §4B.3's "build it once, register two schemas", carried through to
 * the last operation the feature has.
 *
 * <p>What stays here is everything that is true of any registration: which
 * requests are refused, in what order, and what the batch row records afterwards.
 */
@Service
class ImportReversalService {

    private static final Logger log = LoggerFactory.getLogger(ImportReversalService.class);

    private final ImportSchemaRegistry registry;
    private final ImportBatchService batches;

    ImportReversalService(ImportSchemaRegistry registry, ImportBatchService batches) {
        this.registry = registry;
        this.batches = batches;
    }

    /**
     * @param batchId the run to take back
     * @param userId  the caller, best-effort and recorded on the row — the same
     *                treatment {@code imported_by} gets, and for the same reason:
     *                an unidentifiable {@code dev-noauth} caller records a null
     *                actor rather than being refused an operation their role
     *                permits
     * @throws ImportBatchNotFoundException        no such run. <b>404 before
     *                                             anything else</b>, so a caller
     *                                             is told the most structural
     *                                             thing rather than the first
     *                                             thing that failed — the order
     *                                             {@link ImportRequestResolver}
     *                                             sets for the wizard's own
     *                                             routes
     * @throws ImportBatchNotFinishedException     still {@code QUEUED} or
     *                                             {@code RUNNING}
     * @throws ImportBatchAlreadyReversedException reversed once already
     */
    ImportDtos.Reversal reverse(long batchId, Long userId) {
        ImportBatch batch = batches.load(batchId);

        // Order matters: "already reversed" is checked second because a reversed
        // batch is necessarily finished, so the other test can never be the
        // surprising one. A caller holding a stale list gets the answer about
        // their staleness rather than about the lifecycle.
        if (!isFinished(batch.getStatus())) {
            throw new ImportBatchNotFinishedException(batchId, batch.getStatus());
        }
        if (batch.getReversedAt() != null) {
            throw new ImportBatchAlreadyReversedException(batchId, batch.getReversedAt());
        }

        // Resolved by the stored discriminator rather than by a schema the caller
        // named. `entity` is on the row because ImportSchemaDefinition.entityCode
        // exists for exactly this: a run knows which registration wrote it, and
        // asking a caller to restate it would let them ask the client
        // registration to reverse a batch of resources.
        ImportSchemaDefinition definition = registry.byEntityCode(batch.getEntity())
                .orElseThrow(() -> new ImportSchemaUnavailableException(batchId, batch.getEntity()));

        ImportReversal reversal = definition.reverse(batchId);

        log.info("Import batch {} reversed by {} — {} deleted, {} retained",
                batchId, userId, reversal.deleted().size(), reversal.retained().size());

        // Stamped after the deletes. A reversal that dies partway leaves
        // reversed_at null, so the batch is still reversible and re-running it
        // finishes the job — the deletes are idempotent, because a row already
        // gone is not in the registration's set the second time. See
        // ImportBatchService#markReversed.
        batches.markReversed(batchId, reversal.deleted().size(), reversal.retained().size(), userId);

        return new ImportDtos.Reversal(
                batches.find(batchId),
                reversal.deleted(),
                reversal.retained(),
                batch.getUpdatedRows());
    }

    /**
     * {@code COMPLETED} or {@code FAILED} — a run nothing is still writing to.
     *
     * <p>A {@code switch} over the enum rather than {@code != RUNNING && !=
     * QUEUED}, so a fifth status added to {@link ImportBatchStatus} is a compile
     * error here instead of silently defaulting to reversible. The one operation
     * in this product that deletes master data should not acquire new inputs by
     * omission.
     *
     * <p><b>FAILED is reversible, and it is the case this exists for.</b> A run
     * that died at row 314 left 313 clients in the master that nobody approved
     * the presence of, and refusing to reverse it would leave the worst outcome
     * the feature has as the one it cannot clean up.
     */
    private static boolean isFinished(ImportBatchStatus status) {
        return switch (status) {
            case COMPLETED, FAILED -> true;
            case QUEUED, RUNNING -> false;
        };
    }
}
