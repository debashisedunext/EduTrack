package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.upload.UploadPipeline;
import org.springframework.stereotype.Service;

/**
 * B-118 · renders the support handover note and stores it, on {@code
 * ObSignoffCertificateService}'s own shape.
 *
 * <h2>Storage, not {@code ob_attachments}</h2>
 *
 * <p>The same argument B-116 already made for the acceptance certificate
 * applies unchanged: this document has no uploader (staff or client) to name
 * — {@code ob_attachments.uploaded_by_type} accepts only those two — and the
 * vetting half of {@link UploadPipeline} exists for bytes somebody else
 * handed us, not bytes this service generated and already knows the shape of.
 * So it goes straight to {@link UploadPipeline#put} under
 * {@link ObGoLiveHandoverKey}'s own namespace, the same port
 * {@code ObSignoffCertificateService} calls rather than a second one invented
 * here.
 *
 * <h2>Called only when the flip actually fired, and never fails it</h2>
 *
 * <p>{@code ObSignoffAcceptService.wentLive} calls {@link #generate} after
 * {@code ObClientGoLiveService.flipIfEarned} has already returned true and
 * the client row is already saved — the flip is the fact that matters, and a
 * PDF renderer or object-storage outage afterwards must not cost the client
 * their go-live the way {@code ObSignoffCertificateService}'s own javadoc
 * argues a signature must not be lost to one. The caller catches and logs any
 * failure; this class does not need to.
 */
@Service
class ObGoLiveHandoverService {

    private final ObGoLiveHandoverReader reader;
    private final ObGoLiveHandoverRenderer renderer;
    private final UploadPipeline uploads;

    ObGoLiveHandoverService(ObGoLiveHandoverReader reader, ObGoLiveHandoverRenderer renderer,
                            UploadPipeline uploads) {
        this.reader = reader;
        this.renderer = renderer;
        this.uploads = uploads;
    }

    /**
     * Renders and stores the note for a client that has just gone live.
     *
     * @return the storage key it was written under
     */
    String generate(long obClientId) {
        ObGoLiveHandoverReader.Data data = reader.read(obClientId);
        byte[] pdf = renderer.render(data);
        ObGoLiveHandoverKey key = ObGoLiveHandoverKey.mint(obClientId);
        uploads.put(key, pdf, "application/pdf");
        return key.value();
    }
}
