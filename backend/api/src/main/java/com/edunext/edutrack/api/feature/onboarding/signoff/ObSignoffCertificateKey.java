package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.upload.UploadKey;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * B-116 · {@code onboarding/signoff-certificates/{signoffId}/{uuid}} — where
 * the archived acceptance PDF lives in the bucket.
 *
 * <p>A namespace of its own rather than a fifth arm of
 * {@code ObAttachmentStorageKey}'s {@code onboarding/{owner}/{ownerId}/{uuid}}
 * shape. That key addresses {@code ob_attachments} rows read back through
 * {@code ObAttachmentPipeline.signedUrlFor}; this one is read back through
 * {@code ob_signoffs.pdf_storage_key} directly, and the contract says so in as
 * many words — "this route is the only way to reach it, the key is not on any
 * response". Two different read paths sharing one key shape is how a
 * malformed row of one kind ends up parsing as a well-formed key of the
 * other; keeping the segment disjoint from {@code signoffs} (the attachments
 * arm's own name for {@code ObAttachmentOwner.SIGNOFF}) is what
 * {@link #belongsTo} would otherwise have to defend against.
 *
 * <p>{@link UUID#randomUUID()} rather than the bare {@code signoffId} for
 * {@code ObAttachmentStorageKey}'s own reason: the key is minted once, on
 * acceptance, but predictability is the property that matters for a bucket
 * that is ever misconfigured public, not how often a value changes.
 */
record ObSignoffCertificateKey(long signoffId, UUID objectId) implements UploadKey {

    private static final Pattern SHAPE = Pattern.compile(
            "^onboarding/signoff-certificates/(\\d+)/"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    static ObSignoffCertificateKey mint(long signoffId) {
        if (signoffId <= 0) {
            throw new IllegalArgumentException("signoffId must be positive, was " + signoffId);
        }
        return new ObSignoffCertificateKey(signoffId, UUID.randomUUID());
    }

    static ObSignoffCertificateKey parse(String key) {
        var matcher = SHAPE.matcher(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a signoff certificate storage key");
        }
        return new ObSignoffCertificateKey(Long.parseLong(matcher.group(1)), UUID.fromString(matcher.group(2)));
    }

    /** Answers false rather than throwing — {@code ObAttachmentStorageKey.belongsTo}'s own rule. */
    static boolean belongsTo(String key, long signoffId) {
        try {
            return parse(key).signoffId() == signoffId;
        } catch (IllegalArgumentException notOurs) {
            return false;
        }
    }

    @Override
    public String value() {
        return toString();
    }

    @Override
    public String toString() {
        return "onboarding/signoff-certificates/" + signoffId + "/" + objectId;
    }
}
