package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * A checked Module Service that is not an active service of the project's
 * product.
 *
 * <p>The ordinary cause is a New Project form built against a catalogue that
 * has since changed — a service retired, or republished under a new version —
 * between the page loading and the create being submitted. Refused rather than
 * skipped, because silently dropping a checked row would create a project
 * missing a service somebody asked for and say nothing about it.
 */
public class UnknownModuleServiceException extends RuntimeException {

    private final long templateId;

    UnknownModuleServiceException(long productId, long templateId) {
        super("module service " + templateId + " is not an active service of product " + productId);
        this.templateId = templateId;
    }

    public long getTemplateId() {
        return templateId;
    }
}
