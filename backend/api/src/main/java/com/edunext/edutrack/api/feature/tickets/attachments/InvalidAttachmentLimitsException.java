package com.edunext.edutrack.api.feature.tickets.attachments;

import java.io.Serial;

/**
 * C-027 · 422. A settings write the product cannot honour.
 *
 * <p>Not 400: the body parsed, every field is the right type and within its
 * declared range, and the request is refused for what the numbers <em>mean</em>
 * together or against the running configuration. That is exactly what
 * {@code CONVENTIONS.md} §3 reserves 422 for.
 *
 * <p>The message is written for the administrator on the settings form and
 * always names the value that is wrong and what it would have to be. "Invalid
 * limits" would leave them changing one of three numbers at random.
 */
class InvalidAttachmentLimitsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    InvalidAttachmentLimitsException(String message) {
        super(message);
    }
}
