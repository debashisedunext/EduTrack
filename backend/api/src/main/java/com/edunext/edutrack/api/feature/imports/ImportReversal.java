package com.edunext.edutrack.api.feature.imports;

import java.util.List;

/**
 * B-037 · what one registration did when asked to take a batch back.
 *
 * <p>Blueprint §4B.3's closing validation rule, and §17's mitigation for "Client
 * Excel import silently corrupts the master": <i>every import writes an
 * {@code import_batch} row so a bad import can be identified and reversed as a
 * set.</i> This is the answer half of "reversed as a set".
 *
 * <h2>Two lists rather than two numbers</h2>
 *
 * <p>A reversal is not guaranteed to be total, and the user is entitled to know
 * <em>which</em> rows survived it and why. "412 deleted, 3 retained" leaves
 * somebody diffing a spreadsheet against the client master to find out which
 * three; the natural keys cost a few hundred bytes on a response nobody polls.
 *
 * <p>The counts the batch row stores are {@code deleted.size()} and
 * {@code retained.size()} — stored because they are not derivable afterwards
 * (once the rows are gone, an unreversed batch and a fully reversed one both
 * count zero), and the lists are not stored because a reversal is read once, on
 * the screen that asked for it.
 *
 * <h2>What a reversal never contains</h2>
 *
 * <p><b>Rows the run merely updated.</b> {@code import_batch_id} is stamped on
 * insert only — see {@code ClientImportSchema.upsert} — so a client an import
 * edited is never attributed to it, and could not be undone if it were: there is
 * no before image. That is a real limit of the promise rather than a gap in this
 * type, and it is stated on the wire and on the screen instead of being left for
 * a user to discover by comparing the counts.
 *
 * @param deleted  natural keys of the rows this run created and the reversal
 *                 removed, in the order the registration found them
 * @param retained rows this run created that the reversal refused to remove,
 *                 each with the reason. <b>Retained is not failed.</b> Keeping a
 *                 client that has since been named on a ticket is the correct
 *                 outcome — the alternatives are failing the whole reversal
 *                 because one client got used, or destroying a ticket's client
 */
public record ImportReversal(List<String> deleted, List<Retained> retained) {

    /**
     * One row a reversal declined to remove.
     *
     * @param naturalKey the value the user will recognise — a client code, not
     *                   a database id. They are going to look it up in the
     *                   spreadsheet they uploaded
     * @param reason     plain language, for the person reading the screen. Never
     *                   a constraint name or a JDBC message: B-036 made the same
     *                   call about the error report's Reason column, and for the
     *                   same reason — this text gets copied into an email
     */
    public record Retained(String naturalKey, String reason) {
    }

    /** A run that created nothing, or a registration with nothing to take back. */
    public static ImportReversal none() {
        return new ImportReversal(List.of(), List.of());
    }

    /** {@code reversed_rows + retained_rows} — what the run actually created. */
    public int createdRowsFound() {
        return deleted.size() + retained.size();
    }
}
