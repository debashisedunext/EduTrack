package com.edunext.edutrack.api.security.pan;

import com.edunext.edutrack.api.feature.audit.PanRevealAudit;
import com.edunext.edutrack.api.security.CallerIdentity;

/**
 * A-113 · the only exported way in or out of {@code pan_ciphertext}.
 *
 * <h2>Three operations, and only one of them is a disclosure</h2>
 *
 * <p>Blueprint §11 audits <em>unmasked reads</em>. That phrase does a lot of
 * work and this class is where it is made precise, because the obvious reading
 * — "audit every decryption" — is wrong in both directions.
 *
 * <ul>
 *   <li>{@link #seal} encrypts on write. Nothing is read; nothing is audited
 *       here. The route itself is a mutation, so {@code AuditInterceptor}
 *       already records that the client was created or updated.</li>
 *   <li>{@link #masked} decrypts, keeps the last four characters and discards
 *       the rest. A PAN is decrypted in memory and <b>no unmasked value leaves
 *       this method</b>, so there is nothing to disclose and nothing to audit.
 *       Auditing it would file a row for every client list ever rendered, and a
 *       log in which routine list traffic outnumbers deliberate reveals by four
 *       orders of magnitude is a log nobody reads — which is how the events
 *       that matter get lost.</li>
 *   <li>{@link #reveal} is the disclosure, and it records one before returning.</li>
 * </ul>
 *
 * <h2>Why revealing is an action rather than a role check</h2>
 *
 * <p>PHASE-2-BUILD-PLAN.md finding 10: the prototype unmasked <em>automatically
 * by role</em>, so §11's audit rule "has nothing to log". A role-based unmask
 * has no event in it — the PAN is simply present in a payload an Admin
 * requested for some other reason, indistinguishable from the twenty other
 * fields on the same screen. There is no moment to record, no subject to record
 * it against, and no way afterwards to answer "who looked at this client's
 * PAN", which is the only question §11 exists to answer.
 *
 * <p>So a reveal is its own call, deliberately taken, and it produces exactly
 * one {@code PAN_REVEALED} row. Role still gates it — that is the caller's
 * {@code @PreAuthorize} — but role is no longer <em>how</em> it happens.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not load clients and it holds no repository. B-102 owns
 * {@code ob_clients} and its CRUD; this takes and returns bytes so that the two
 * tasks do not both need to be finished before either works. The entity is not
 * pre-empted here for the reason {@code ObClient}'s own note gives.
 */
public class PanService {

    private final PanCipher cipher;
    private final PanBlindIndex blindIndex;
    private final PanRevealAudit audit;

    PanService(PanCipher cipher, PanBlindIndex blindIndex, PanRevealAudit audit) {
        this.cipher = cipher;
        this.blindIndex = blindIndex;
        this.audit = audit;
    }

    /**
     * The two column values for a PAN being written, produced together.
     *
     * @param ciphertext {@code ob_clients.pan_ciphertext}
     * @param blindIndex {@code ob_clients.pan_blind_index} — 32 bytes, the
     *                   UNIQUE one
     */
    public record SealedPan(byte[] ciphertext, byte[] blindIndex) {
    }

    /**
     * Encrypt and index a PAN for storage.
     *
     * <p>Both values come back from one call because writing one without the
     * other is always a bug: ciphertext without an index is a row the duplicate
     * guard cannot see, and an index without ciphertext is a PAN that can be
     * matched but never read back.
     *
     * @throws IllegalArgumentException if the value is not a structurally valid
     *         PAN. Validating here rather than trusting the caller means the
     *         blind index is only ever computed over something PAN-shaped —
     *         otherwise a typo becomes a permanent UNIQUE row that a corrected
     *         entry then collides with.
     */
    public SealedPan seal(String rawPan) {
        if (!PanFormat.isValid(rawPan)) {
            throw new IllegalArgumentException(
                    "Not a valid PAN. Expected five letters, four digits and a letter.");
        }
        String normalised = PanFormat.normalise(rawPan);
        return new SealedPan(cipher.encrypt(normalised), blindIndex.of(normalised));
    }

    /**
     * Compute the blind index for a lookup, without touching ciphertext.
     *
     * <p>This is the duplicate guard's query value. It decrypts nothing, so it
     * is not a read in §11's sense and writes no audit row.
     */
    public byte[] blindIndexOf(String rawPan) {
        return blindIndex.of(rawPan);
    }

    /**
     * The display form — last four characters only.
     *
     * <p>Null-safe on the way in: {@code pan_ciphertext} is nullable and most
     * rows hold no PAN yet, and those must render as absent rather than as a
     * row of bullets claiming a withheld value.
     */
    public String masked(byte[] ciphertext) {
        return ciphertext == null ? null : PanFormat.mask(cipher.decrypt(ciphertext));
    }

    /**
     * The unmasked PAN, and the audit row that §11 requires for it.
     *
     * <h2>Decrypt, then audit, then return — and the middle step is the guarantee</h2>
     *
     * <p>The obvious ordering is to audit first, on the reasoning that a
     * disclosure must never happen unlogged. <b>That reasoning is wrong, and a
     * mutation test is what showed it.</b> Auditing first and auditing second
     * behave identically in the case it was meant to protect: if the audit
     * write throws, the exception propagates either way and the caller receives
     * nothing. Nothing is disclosed, because the value is returned only by the
     * last line.
     *
     * <p>The two orderings differ in the case nobody was thinking about. If
     * <em>decryption</em> fails — a rotated key, a row altered outside the
     * application — auditing first has already recorded a {@code PAN_REVEALED}
     * against a client whose PAN was never read. That is a false entry in the
     * one log that is supposed to answer "who saw this", and false entries are
     * worse there than nowhere else: an investigation cannot tell it from a real
     * one, and the person it names cannot disprove it.
     *
     * <p>So the value is produced first, the disclosure is recorded second, and
     * it is still impossible to receive an unaudited PAN because the return
     * happens third. Both properties are pinned by tests, and each fails under
     * the other ordering.
     *
     * @param clientId {@code ob_clients.id}, the subject of the audit row
     * @param caller   who is looking; the actor on the audit row
     */
    public String reveal(long clientId, CallerIdentity caller, byte[] ciphertext) {
        if (ciphertext == null) {
            // Nothing was disclosed, so nothing is recorded. A PAN_REVEALED row
            // for a client that holds no PAN would be a false positive in the
            // one report that must not have any.
            return null;
        }
        // Decrypt before recording, so a failed decryption cannot leave a
        // PAN_REVEALED row for a disclosure that never happened. The return is
        // last, so a failed audit still yields no PAN.
        String plaintext = cipher.decrypt(ciphertext);
        audit.revealed(clientId, caller);
        return plaintext;
    }
}
