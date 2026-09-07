package com.edunext.edutrack.api.security.pan;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * A-113 · the deterministic HMAC-SHA256 that {@code pan_blind_index} stores and
 * the duplicate-client guard matches on.
 *
 * <h2>This is the column that carries the UNIQUE key</h2>
 *
 * <p>{@code PanCipher} is randomised, so a constraint over its output would
 * never collide. The blind index is deterministic over the normalised PAN, so
 * two rows for one legal entity produce identical 32 bytes and the database
 * refuses the second — which is what plan §1.1 item 6 and the
 * {@code ob-client-pan-duplicate} contract error actually rest on.
 *
 * <p>The plaintext is not derivable from the index. Looking a client up by PAN
 * means hashing the query the same way and matching bytes, never decrypting a
 * column and comparing strings — so the duplicate guard runs without any PAN
 * being decrypted, and therefore without an audit event, because <b>no unmasked
 * value is read</b>. That is the point of a blind index rather than a
 * convenience: B-102 checks for duplicates on every create, and routing that
 * through decryption would either produce an audit row per keystroke or an
 * exemption that hollows out §11.
 *
 * <h2>Public, unlike the cipher</h2>
 *
 * <p>Computing an index discloses nothing — it is a one-way function of a value
 * the caller already holds. B-102 needs it directly, on create and on lookup,
 * and gating it behind an audited service would attach a reveal event to an
 * operation that reveals nothing.
 */
public final class PanBlindIndex {

    private static final String ALGORITHM = "HmacSHA256";

    private final PanKeySource keys;

    PanBlindIndex(PanKeySource keys) {
        this.keys = keys;
    }

    /**
     * @param rawPan as typed; normalised here so that callers cannot disagree
     *               about what "the same PAN" means
     * @return exactly 32 bytes, for {@code ob_clients.pan_blind_index}
     */
    public byte[] of(String rawPan) {
        String normalised = PanFormat.normalise(rawPan);
        if (normalised == null || normalised.isEmpty()) {
            throw new IllegalArgumentException("A blank PAN has no blind index");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keys.blindIndexKey());
            return mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JRE", e);
        }
    }
}
