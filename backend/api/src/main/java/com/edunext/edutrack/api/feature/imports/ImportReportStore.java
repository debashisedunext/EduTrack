package com.edunext.edutrack.api.feature.imports;

import java.util.Optional;

/**
 * B-036 · where a generated error report lives — PLAN.md §2.2's object store.
 *
 * <h2>Two methods, and no third</h2>
 *
 * <p>An interface rather than an {@code S3Client} injected directly, for
 * {@link com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage}'s
 * reason and not for testability: <b>nothing here can produce a public
 * address.</b> There is no presign, no ACL and no bucket URL, so an error report
 * is readable only by a caller who has been through
 * {@link ImportBatchController}'s {@code master.write} check. A report is a
 * verbatim extract of somebody's client master — names, addresses, contract
 * dates — and a signed URL for one would be a credential that outlives the
 * screen it was minted for, in a browser history and a proxy log.
 *
 * <p>The test double is then a consequence rather than the purpose: no MinIO is
 * needed to prove that a run whose report could not be stored still completes.
 *
 * <h2>The key is opaque to everything above this</h2>
 *
 * <p>{@code import_batches.error_report_key} stores whatever {@link #put}
 * returned and nothing reads it apart from {@link #read}. It is deliberately not
 * on the wire — see {@code ImportDtos.Batch}, which projects a route rather than
 * the key — so the layout below can change without a contract change.
 */
interface ImportReportStore {

    /**
     * Store the workbook and answer the key it went under.
     *
     * <p>Returns the key rather than taking one, because the layout is this
     * store's business and a caller that composed keys would be a second place
     * that has to agree about them.
     *
     * @throws RuntimeException if the object store refuses or cannot be reached.
     *         The caller decides what that means; see {@link ImportErrorReportService},
     *         where it means the run completes with no report rather than fails
     */
    String put(long batchId, String entityCode, byte[] workbook);

    /** The stored bytes, or empty when the object is gone. */
    Optional<byte[]> read(String key);
}
