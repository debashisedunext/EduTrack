package com.edunext.edutrack.api.security.pan;

import javax.crypto.SecretKey;

/**
 * A-113 · where the two PAN keys come from, behind an interface, so that A-075
 * can change the answer without touching a stored byte.
 *
 * <h2>The seam exists because the vault is eight weeks late relative to this</h2>
 *
 * <p>The backlog entry for this task reads "key from the vault A-075 stood up",
 * and {@code V20260903_1210__ob_client_capture.sql} records that the vault is
 * not up until <b>5 November</b>. A-113 is due in September, and B-102's
 * duplicate-client guard is blocked on it. Waiting is not free and neither is
 * hardcoding: an implementation that reads configuration <em>directly</em> is
 * the thing A-075 would then have to find and rewrite in place, which is a
 * migration of live identity data dressed up as a refactor.
 *
 * <p>So the key is fetched through this interface from the first commit.
 * {@link ConfiguredPanKeySource} is the implementation today.
 * A vault-backed one is a new class and a bean swap in {@link PanConfig} —
 * <b>no schema change, no re-encryption, no reading and rewriting a column of
 * PANs.</b> That last point is the whole argument: the migration note says the
 * columns were created early precisely so that no later migration would ever
 * have to read, encrypt and rewrite live identity data in place, and a key path
 * that cannot be swapped would have re-introduced that same hazard one layer up.
 *
 * <h2>Two methods, not one with a parameter</h2>
 *
 * <p>An enum parameter would let a caller ask for "a key" and get whichever one
 * a typo named. The two keys are not interchangeable — encrypting with the
 * blind-index key produces ciphertext nothing can decrypt, and computing a blind
 * index under the AES key produces an index that collides with nothing and
 * silently disables the duplicate guard. Both mistakes leave a system that runs.
 */
interface PanKeySource {

    /** AES-256-GCM, for {@code pan_ciphertext}. Rotatable: rows can be re-encrypted. */
    SecretKey encryptionKey();

    /**
     * HMAC-SHA256, for {@code pan_blind_index}.
     *
     * <p><b>Effectively not rotatable.</b> A blind index cannot be recomputed
     * without the plaintext, so changing this key invalidates every stored index
     * at once and the UNIQUE constraint over them stops meaning anything.
     */
    SecretKey blindIndexKey();
}
