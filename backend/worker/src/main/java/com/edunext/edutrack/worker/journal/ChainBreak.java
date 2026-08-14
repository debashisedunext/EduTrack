package com.edunext.edutrack.worker.journal;

/**
 * A-044 · one thing the verifier found wrong with one chain.
 *
 * <p><b>The kind says where to look. It does not say how much to care.</b>
 * Every kind below is reported at the same weight, and that is a decision
 * rather than an omission: {@link Kind#FORK} and {@link Kind#MULTIPLE_GENESIS}
 * are the shapes our <em>own</em> bugs produce — A-045 generated the second one
 * from eight concurrent writers — and they are therefore also the cheapest
 * shapes for somebody to produce deliberately, knowing a race reads as an
 * engineering wobble and gets triaged accordingly. Ranking them below the
 * others would hand out a disguise.
 *
 * <p>What the kind buys is the first hour of the investigation. "Chain broken
 * on ticket 4711" starts from nothing; {@code FORK} says audit the lock path,
 * {@code UNCHAINED} says find who wrote outside the journal, and
 * {@code HASH_MISMATCH} says somebody altered a stored column and got past two
 * triggers to do it.
 *
 * @param ticketId  the ticket whose chain this is — chains are per-ticket
 *                  (PLAN.md §3.7), so a break is localised
 * @param table     which of the three chains
 * @param rowId     the row the break was found at; {@code null} where the
 *                  break is a property of the chain rather than of one row
 * @param kind      what shape of wrong
 * @param detail    what was expected against what was there
 */
record ChainBreak(long ticketId, String table, Long rowId, Kind kind, String detail) {

    enum Kind {

        /**
         * The stored {@code row_hash} is not what recomputing gives. A hashed
         * column changed after the row was written, which means getting past
         * the A-008 triggers — or, far more often at this ratio,
         * {@code ChainPayloads} changed without a {@code VERSION} bump.
         */
        HASH_MISMATCH,

        /**
         * A row's {@code prev_hash} is not the {@code row_hash} of the row
         * before it. Something was removed from the middle, or inserted with a
         * parent it did not have.
         */
        BROKEN_LINK,

        /**
         * Two rows claim the same parent. The fork PLAN.md §3.7 is written to
         * prevent: both sides verify perfectly against the row they share, so
         * nothing else in this list would notice.
         */
        FORK,

        /**
         * More than one row carries no {@code prev_hash}. Not a fork but a
         * shattering — several writers each believed they were first. A-045
         * produced exactly this with eight concurrent appends before the tail
         * read was made a locking read.
         */
        MULTIPLE_GENESIS,

        /**
         * A protected row with no {@code row_hash} at all — written around the
         * journal. A-042 decided this is a finding rather than a tolerated
         * category, because a verifier that skips unhashed rows can be defeated
         * by unhashing a row.
         */
        UNCHAINED,

        /**
         * The chain is shorter than the anchor says it was, or the row the
         * anchor named is gone. The one break that chaining alone cannot see:
         * delete a chain's last rows and everything remaining still verifies.
         */
        TRUNCATED
    }

    static ChainBreak at(long ticketId, String table, Long rowId, Kind kind, String detail) {
        return new ChainBreak(ticketId, table, rowId, kind, detail);
    }

    @Override
    public String toString() {
        return kind + " " + table + (rowId == null ? "" : " row " + rowId)
                + " (ticket " + ticketId + "): " + detail;
    }
}
