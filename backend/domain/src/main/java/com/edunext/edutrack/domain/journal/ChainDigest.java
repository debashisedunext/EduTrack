package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.common.canonical.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * A-042 · {@code row_hash = SHA256(prev_hash ‖ canonical_json(payload))}, and
 * nothing else.
 *
 * <p>PLAN.md §3.7 writes that expression once. This is the only place it is
 * evaluated, for the reason {@link ChainPayloads} gives about the payload: two
 * programs compute it — the journal at the moment of the append, and A-044's
 * nightly verifier months later from a row read back out of MySQL — and if they
 * ever disagree by one byte the verifier reports tampering that did not happen.
 * A second implementation is not a risk to be managed, it is the bug.
 *
 * <h2>The genesis row: {@code prev_hash} is NULL and nothing is prepended</h2>
 *
 * <p>The first row of a ticket's chain has no predecessor, so its digest is
 * taken over the payload alone. That is literally §3.7's pseudocode — the tail
 * {@code SELECT} returns no row and {@code prev_hash} is the absence — and it
 * keeps the column honest: NULL means "there is nothing before this", which is
 * true, rather than a constant standing in for it.
 *
 * <p><b>A sentinel was considered and is not used.</b> The usual argument for
 * one (64 zeros, say) is that it makes "I am the first row" an explicit claim
 * rather than an absence. It buys nothing here, because the two states are
 * already distinguishable in the columns: a genesis row has {@code prev_hash}
 * NULL and {@code row_hash} <em>set</em>, while an unchained row — a legacy row,
 * or one written through {@code @DirectAppend} — has both NULL. A-044 can tell
 * them apart without a constant, and a constant would need its own migration for
 * the rows already written.
 *
 * <p>Concatenation stays unambiguous without a separator because the two halves
 * cannot be confused for one another: {@code prev_hash} is exactly 64 characters
 * of lowercase hex, and canonical JSON always begins with <code>{</code>, which
 * is not a hex digit. No byte can be shifted from one field to the other to
 * produce the same input from different values.
 *
 * <h2>Why the hash is hex text rather than the raw digest</h2>
 *
 * <p>{@code prev_hash} is stored as {@code CHAR(64) ascii_bin}, so what the next
 * append reads back is the hex text. Hashing the raw 32 bytes instead would mean
 * the verifier had to decode the column before it could reproduce anything, and
 * the two representations would differ in the one place nobody would look. The
 * text that is stored is the text that is hashed.
 */
public final class ChainDigest {

    /** SHA-256, per PLAN.md §3.7. Every Java platform is required to have it. */
    private static final String ALGORITHM = "SHA-256";

    /** {@code CHAR(64)} — SHA-256 as lowercase hex. */
    public static final int HASH_LENGTH = 64;

    private ChainDigest() {
    }

    /**
     * The chain link for one row.
     *
     * @param prevHash the {@code row_hash} of this ticket's previous row in the
     *                 same table, or {@code null} for the first row of the chain
     * @param payload  from {@link ChainPayloads}
     * @return 64 lowercase hex characters
     * @throws IllegalArgumentException if {@code prevHash} is present but is not
     *         a hash this class could have produced
     * @throws com.edunext.edutrack.common.canonical.CanonicalJsonException if the
     *         payload has no canonical form — most often an {@code Instant} with
     *         sub-microsecond precision, which {@code DATETIME(6)} cannot store
     */
    public static String rowHash(String prevHash, Map<String, Object> payload) {
        MessageDigest digest = newDigest();
        if (prevHash != null) {
            requireWellFormed(prevHash);
            // US-ASCII rather than UTF-8 only to say what the column says
            // (CHAR(64) CHARACTER SET ascii); for validated hex they are the
            // same bytes.
            digest.update(prevHash.getBytes(StandardCharsets.US_ASCII));
        }
        digest.update(CanonicalJson.bytes(payload));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * A tail that is not a hash is refused rather than hashed over.
     *
     * <p>The value comes from a {@code CHAR(64) ascii_bin} column, so in a healthy
     * database it cannot be anything else and this never fires. It exists for the
     * unhealthy one: A-044 reads these back from rows it did not write, and a
     * chain built on top of a corrupted link would verify perfectly against its
     * own corruption. Refusing turns that into a finding at the point it is
     * noticed.
     */
    private static void requireWellFormed(String prevHash) {
        if (prevHash.length() != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "prev_hash must be " + HASH_LENGTH + " hex characters, got " + prevHash.length()
                            + ". The column is CHAR(64), so a shorter value means the row it was "
                            + "read from is not one this chain wrote.");
        }
        for (int i = 0; i < prevHash.length(); i++) {
            char c = prevHash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(
                        "prev_hash is not lowercase hex at index " + i + " ('" + c + "'). "
                                + "HexFormat writes lowercase, so an uppercase or non-hex character "
                                + "did not come from ChainDigest.");
            }
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required by every Java platform", e);
        }
    }
}
