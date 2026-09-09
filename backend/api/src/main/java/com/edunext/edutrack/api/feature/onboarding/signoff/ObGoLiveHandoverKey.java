package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientGoLiveService;
import com.edunext.edutrack.api.upload.UploadKey;

/**
 * B-118 · {@code onboarding/go-live-handover/{obClientId}/handover.pdf} —
 * where the support handover note lives in the bucket.
 *
 * <p><b>Deterministic, not {@link ObSignoffCertificateKey}'s random one.</b>
 * That key is unpredictable on purpose because a certificate is evidence of
 * one specific acceptance and a bucket misconfigured public must not leak it
 * by a guessable path. A handover note carries no such record — it names no
 * consent, no signature, nothing anyone could dispute — and it needs no
 * second table column to remember where it went, because
 * {@link ObClientGoLiveService}'s own guarantee that a client goes live
 * <b>at most once in its life</b> means "generate the note again" and
 * "generate the note for the first time" write the exact same key, and the
 * second write is the honest regeneration a support team asks for when the
 * first copy is lost — not a collision to guard against.
 *
 * <p>No {@code parse}/{@code belongsTo}: unlike the certificate, this key is
 * never read back from a value stored on a row, so there is nothing to
 * validate it against.
 */
record ObGoLiveHandoverKey(long obClientId) implements UploadKey {

    static ObGoLiveHandoverKey mint(long obClientId) {
        if (obClientId <= 0) {
            throw new IllegalArgumentException("obClientId must be positive, was " + obClientId);
        }
        return new ObGoLiveHandoverKey(obClientId);
    }

    @Override
    public String value() {
        return toString();
    }

    @Override
    public String toString() {
        return "onboarding/go-live-handover/" + obClientId + "/handover.pdf";
    }
}
