package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-027 · §4B.4's "all configurable in system settings", and the one place that
 * decides what the caps actually are.
 *
 * <p>C-025 enforced the three caps from {@code edutrack.attachments.*}. A
 * property is configurable by whoever can restart the process; §4B.4 asks for
 * something an administrator can change on a running system, which is what
 * {@code attachment_settings} adds. The properties did not go away — they are
 * the fallback below, and the reason is worth stating.
 *
 * <h2>Why absence falls back rather than fails</h2>
 *
 * <p>The migration seeds the row, so {@link AttachmentSettingsRepository#load()}
 * returning empty means somebody deleted it. The two options are "refuse every
 * upload" and "use the properties". Refusing turns a stray {@code DELETE} into a
 * product outage on a surface all six roles reach, and the failure would name a
 * settings table nobody was thinking about. Falling back costs the
 * customisation, which is recoverable in one {@code PUT}, and is logged at WARN
 * so it does not stay invisible. Nothing else in the application branches on
 * which of the two it got — {@link #effective()} is total.
 *
 * <h2>The container ceiling, and why it is checked here</h2>
 *
 * <p>{@code spring.servlet.multipart.max-file-size} refuses an oversized body
 * during parsing, before any of this feature's code runs. A configured
 * {@code maxFileBytes} above it is therefore a limit that does not work: files
 * between the two are refused by the container with a generic 413 rather than
 * §4B.4's worded one, and an administrator who raised the cap would watch the
 * setting save successfully and change nothing. So the write refuses, naming the
 * ceiling — and {@link #effective()} <b>clamps</b> rather than refusing, because
 * a read that threw would take the upload path down over a configuration
 * problem, and clamping down is the safe direction.
 *
 * <p>{@code max-request-size} bounds the same upload — the request carries the
 * part plus its headers — so the effective ceiling is the smaller of the two.
 * Checking only {@code max-file-size} would let a deployment that raised it and
 * left the request size alone through, which is the more likely of the two
 * mistakes because the file limit is the one anybody thinks about.
 */
@Service
class AttachmentSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentSettingsService.class);

    private final AttachmentSettingsRepository settings;
    private final AttachmentProperties properties;
    private final MultipartProperties multipart;

    AttachmentSettingsService(AttachmentSettingsRepository settings,
                              AttachmentProperties properties,
                              MultipartProperties multipart) {
        this.settings = settings;
        this.properties = properties;
        this.multipart = multipart;
    }

    /**
     * The caps in force right now. Never throws, never null.
     *
     * <p>Every enforcement and every read goes through this, so there is exactly
     * one answer to "what is the limit" in the application. That matters more
     * than it looks: {@link AttachmentService#enforceLimits} and the
     * {@code GET} the client validates against would otherwise be two
     * derivations of the same rule, and a client that refuses a file the server
     * would have taken is indistinguishable from a broken upload.
     */
    @Transactional(readOnly = true)
    AttachmentLimits effective() {
        AttachmentLimits configured = settings.load()
                .map(AttachmentSettingsRepository.Stored::limits)
                .orElseGet(this::fromProperties);
        return clampToContainer(configured);
    }

    /**
     * The most this server would accept for one file whatever the setting says.
     *
     * <p>Reported alongside the limits so an administrator learns the bound
     * before a save is refused by it rather than after, and so the client can
     * say "up to X" without a second round trip.
     */
    long ceilingBytes() {
        return containerCeilingBytes();
    }

    /**
     * Replace all three caps.
     *
     * <p>Wholesale, not per-field. The three are only meaningful together — see
     * {@link AttachmentLimits} — and a {@code PATCH} that moved one at a time
     * would let a caller pass through the inconsistent state in two individually
     * valid steps.
     *
     * @throws InvalidAttachmentLimitsException 422; the combination cannot be
     *                                          honoured, or the per-file cap is
     *                                          above what this server can accept
     */
    @Transactional
    AttachmentLimits replace(Authentication caller, long maxFileBytes, long maxTicketBytes, int maxFiles) {
        AttachmentLimits requested = AttachmentLimits.of(maxFileBytes, maxTicketBytes, maxFiles);

        long ceiling = containerCeilingBytes();
        if (requested.maxFileBytes() > ceiling) {
            // Refused rather than clamped, unlike the read path. A write is a
            // person stating an intent, and silently storing a smaller number
            // than they typed is how a settings screen loses someone's trust.
            throw new InvalidAttachmentLimitsException(
                    "This server accepts at most " + AttachmentLimits.Bytes.human(ceiling)
                            + " in one upload, so a per-file limit of "
                            + AttachmentLimits.Bytes.human(requested.maxFileBytes())
                            + " could not take effect. Raise spring.servlet.multipart.max-file-size first.");
        }

        Long updatedBy = CallerIdentity.of(caller).map(CallerIdentity::userId).orElse(null);
        settings.replace(requested, updatedBy);
        log.info("attachment limits set to {} per file, {} per ticket, {} files by user {}",
                AttachmentLimits.Bytes.human(requested.maxFileBytes()),
                AttachmentLimits.Bytes.human(requested.maxTicketBytes()),
                requested.maxFiles(), updatedBy);
        return requested;
    }

    /**
     * The properties, as they stood before this task.
     *
     * <p>Read through {@link AttachmentLimits#of} like every other source, so a
     * deployment whose {@code application.yml} holds an inconsistent trio fails
     * on the first upload with a sentence naming the offending value rather than
     * enforcing something incoherent.
     */
    private AttachmentLimits fromProperties() {
        log.warn("attachment_settings holds no row; falling back to edutrack.attachments.* "
                + "(the migration seeds one — a missing row means it was deleted)");
        return AttachmentLimits.of(properties.maxFileBytes(), properties.maxTicketBytes(), properties.maxFiles());
    }

    /**
     * The most this server can physically accept for one file, whatever the
     * settings say.
     *
     * <p>Spring expresses "no limit" as a negative {@code DataSize}; either
     * bound being unlimited simply drops out of the minimum, and both being
     * unlimited leaves {@link AttachmentLimits#MAX_FILE_BYTES_CEILING} as the
     * only ceiling — which is the right answer, because the byte array is still
     * held in heap.
     */
    private long containerCeilingBytes() {
        long ceiling = AttachmentLimits.MAX_FILE_BYTES_CEILING;
        long perFile = multipart.getMaxFileSize() == null ? -1 : multipart.getMaxFileSize().toBytes();
        long perRequest = multipart.getMaxRequestSize() == null ? -1 : multipart.getMaxRequestSize().toBytes();
        if (perFile > 0) {
            ceiling = Math.min(ceiling, perFile);
        }
        if (perRequest > 0) {
            ceiling = Math.min(ceiling, perRequest);
        }
        return ceiling;
    }

    /**
     * Bring a stored per-file cap down under the container's, if a deployment
     * has been reconfigured since it was written.
     *
     * <p>{@code maxTicketBytes} comes down with it only as far as it must, so
     * the invariant {@link AttachmentLimits#of} enforces still holds — lowering
     * the per-file cap must not turn a perfectly good per-ticket total into an
     * illegal combination.
     */
    private AttachmentLimits clampToContainer(AttachmentLimits limits) {
        long ceiling = containerCeilingBytes();
        if (limits.maxFileBytes() <= ceiling) {
            return limits;
        }
        log.warn("configured per-file attachment limit {} exceeds what this server accepts ({}); enforcing the lower",
                AttachmentLimits.Bytes.human(limits.maxFileBytes()), AttachmentLimits.Bytes.human(ceiling));
        return AttachmentLimits.of(ceiling, Math.max(limits.maxTicketBytes(), ceiling), limits.maxFiles());
    }
}
