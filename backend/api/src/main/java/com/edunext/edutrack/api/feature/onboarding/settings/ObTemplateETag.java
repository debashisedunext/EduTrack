package com.edunext.edutrack.api.feature.onboarding.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-113 · the tag OB-12's {@code PATCH} preconditions on.
 *
 * <p>{@link ObSettingsETag}'s shape and {@code ObClientETag}'s before it. Kept
 * as a separate class rather than generified over {@code Object}: the two tags
 * cover different resources, and one helper taking anything would let a
 * settings tag satisfy a template's precondition — which is a hash collision
 * away from being a real lost update rather than a theoretical one.
 *
 * <p>Derived from the whole DTO, so a change to any editable field moves it.
 * That deliberately includes the two derived fields: {@code isDeliverable}
 * changes when a deployment gains an adapter, and an admin whose screen still
 * says "not yet sending" is looking at a template whose meaning has changed
 * under them.
 */
final class ObTemplateETag {

    private ObTemplateETag() {
    }

    static String of(ObTemplateDtos.Template template) {
        return Integer.toHexString(template.hashCode());
    }

    /**
     * {@code If-Match} is required, not optional — 428 without one.
     *
     * <p>The contract calls this the strongest case in either module: the field
     * most likely to be edited is a long body two admins can plausibly be
     * rewording at once, and a lost update here is silent and outward-facing.
     */
    static void require(String ifMatch, ObTemplateDtos.Template current) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the template first and send back its ETag.");
        }
        if (!matches(ifMatch, of(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This template changed since you read it. Reload and reapply your edit.");
        }
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
