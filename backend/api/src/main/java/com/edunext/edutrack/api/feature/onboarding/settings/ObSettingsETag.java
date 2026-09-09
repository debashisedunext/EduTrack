package com.edunext.edutrack.api.feature.onboarding.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-113 · the tag OB-11's {@code PUT} preconditions on, and the read that
 * produces it.
 *
 * <p>{@code ObClientETag}'s shape, and the same argument the contract makes:
 * {@code getObSettings} "is the only source of the tag {@code PUT
 * /onboarding/settings} requires — the pairing CONVENTIONS §5 says to make by
 * hand, and the gap B-016 closed on {@code /projects/id} after the precondition
 * had been declared for months with nowhere to satisfy it".
 *
 * <p>Derived from the whole settings object rather than from {@code updated_at}
 * alone. The ladder lives in a second table with its own stamps, so a tag over
 * the parent row would not move when somebody changed only a rung — and two
 * admins editing different halves of one form is exactly the lost update this
 * precondition exists to catch.
 */
final class ObSettingsETag {

    private ObSettingsETag() {
    }

    static String of(ObSettingsDtos.Settings settings) {
        return Integer.toHexString(settings.hashCode());
    }

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>428 rather than allowed through: treating a missing precondition as
     * "no conflict" means the guard protects only the callers that already
     * opted in, which is the set that needed it least. The same status and the
     * same reasoning as B-016's project form and B-026's client form.
     */
    static void require(String ifMatch, ObSettingsDtos.Settings current) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the settings first and send back their ETag.");
        }
        if (!matches(ifMatch, of(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "These settings changed since you read them. Reload and reapply your edit.");
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
