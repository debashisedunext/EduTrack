package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.upload.UploadPipeline;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-116 · renders the acceptance PDF and stores it, and answers the download
 * route from the same key.
 *
 * <h2>Storage, not {@code ob_attachments}</h2>
 *
 * <p>{@code ob_signoffs.pdf_storage_key} is its own column (A-107), and the
 * contract's own words for {@code getObSignoffCertificate} settle how it is
 * read back: "streamed from object storage… this route is the only way to
 * reach it — the key is not on any response". That is a direct
 * {@link UploadPipeline#put}/{@link UploadPipeline#read} pair addressed by
 * {@link ObSignoffCertificateKey}, not a row filed through
 * {@code ObAttachmentPipeline} — this document has no uploader (staff or
 * client) to name, {@code ob_attachments.uploaded_by_type} accepts only those
 * two, and the vetting half of that pipeline (sniff, strip, allow-list) exists
 * for bytes somebody else handed us, not bytes this service generated and
 * already knows the shape of. What <em>is</em> reused is the storage seam
 * itself — the same {@code UploadPipeline} port {@code ObAttachmentPipeline}
 * calls, so there remains one object-storage client for the whole module
 * rather than a second one invented here.
 *
 * <h2>Archiving never fails the acceptance</h2>
 *
 * <p>{@link ObSignoffAcceptService#accept} calls {@link #archive} after the
 * signature is already recorded and saved — {@code SIGNED} first,
 * unconditionally, on that method's own two-outcome reasoning: the client
 * accepted, and a fault on our side afterwards must not cost them the
 * signature. A storage or rendering failure here is caught by the caller,
 * logged, and leaves {@code pdfStorageKey} null; the certificate can be
 * regenerated later, an accepted-but-uncertified sign-off cannot be un-lost.
 */
@Service
class ObSignoffCertificateService {

    private final ObSignoffRepository signoffs;
    private final ObSignoffCertificateVisibility visibility;
    private final ObSignoffPageReader pages;
    private final ObSignoffCertificateRenderer renderer;
    private final UploadPipeline uploads;

    /**
     * <p><b>{@code @Autowired} is not decorative</b> — every constructor
     * pair in this package carries the same note: two constructors with no
     * annotation is not an ambiguity Spring resolves, it is a context that
     * fails to start.
     */
    @Autowired
    ObSignoffCertificateService(ObSignoffRepository signoffs,
                                ObSignoffCertificateVisibility visibility,
                                ObSignoffPageReader pages,
                                ObSignoffCertificateRenderer renderer,
                                UploadPipeline uploads) {
        this.signoffs = signoffs;
        this.visibility = visibility;
        this.pages = pages;
        this.renderer = renderer;
        this.uploads = uploads;
    }

    /**
     * Renders and stores the certificate for a just-signed row.
     *
     * @return the storage key to set on {@code ObSignoff.pdfStorageKey}
     */
    String archive(ObSignoff signoff) {
        ObSignoffPageReader.Page header = pages.read(signoff);

        byte[] pdf = renderer.render(new ObSignoffCertificateRenderer.Data(
                signoff.getId(),
                signoff.getKind(),
                header.clientName(),
                header.productName(),
                header.stepTitle(),
                signoff.getSignedName(),
                signoff.getSignedAt(),
                signoff.getSignedIp(),
                signoff.getSignedUserAgent(),
                humanize(ObSignoffOtpService.OTP_CHANNEL),
                signoff.getAcceptanceNote()));

        ObSignoffCertificateKey key = ObSignoffCertificateKey.mint(signoff.getId());
        uploads.put(key, pdf, "application/pdf");
        return key.value();
    }

    /**
     * The stored bytes, for the download route.
     *
     * @throws ObSignoffCertificateNotFoundException the row does not exist,
     *         is outside the caller's A-112 scope, has not been signed yet
     *         (so nothing was ever archived), or the object behind its key
     *         is gone — all indistinguishable, on
     *         {@code ObClientNotFoundException}'s own reasoning for why that
     *         is a 404 rather than a 403
     */
    @Transactional(readOnly = true)
    byte[] read(ObClientScope scope, long signoffId) {
        ObSignoff signoff = signoffs.findById(signoffId)
                .filter(row -> visibility.isVisible(scope, row.getObClientId()))
                .orElseThrow(() -> new ObSignoffCertificateNotFoundException(signoffId));

        String key = signoff.getPdfStorageKey();
        if (key == null) {
            throw new ObSignoffCertificateNotFoundException(signoffId);
        }
        return uploads.read(ObSignoffCertificateKey.parse(key))
                .orElseThrow(() -> new ObSignoffCertificateNotFoundException(signoffId));
    }

    private static String humanize(ObChannel channel) {
        return switch (channel) {
            case EMAIL -> "Email";
            case WHATSAPP -> "WhatsApp";
            case IN_APP -> "In-app";
        };
    }
}
