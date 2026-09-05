package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import org.springframework.security.core.Authentication;

/**
 * C-104 · resolves the numeric actor id the step-lifecycle routes check
 * ownership against and stamp onto {@code skippedBy}-style audit columns.
 *
 * <p>Same class, same reasoning, as {@code onboarding.journeys}'s own
 * {@code CallerIdentityAccess} — duplicated rather than shared across
 * packages because each {@code onboarding.*} sub-package is one controller
 * family's own surface (see {@code ObJourneyTemplateExceptionHandler}'s
 * {@code assignableTypes} note), and a package-private helper is not meant
 * to cross that line. Every route in this package sits behind {@code
 * SecurityConfig}'s blanket {@code authenticated()} (see
 * {@link ObJourneyStepLifecycleController}'s class javadoc for why nothing
 * stronger exists yet), so a caller reaching a handler here has already
 * proven a live token — {@link IllegalStateException} rather than a silent
 * {@code null} or a made-up system id is deliberate: {@code
 * CallerIdentity.of} returning empty at this point means the security chain
 * accepted a token this class cannot read, which is a bug worth a loud 500.
 */
final class CallerIdentityAccess {

    private CallerIdentityAccess() {
    }

    static long requireUserId(Authentication caller) {
        return CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding step-lifecycle route reached with no resolvable caller identity"));
    }

    /**
     * C-107 · the caller's role <em>within</em> the onboarding module — {@code
     * OB_MANAGER}, {@code OB_ADMIN}, and so on — for {@link
     * ObJourneyStepLifecycleService#skip} to check against. Distinct from
     * {@link #requireUserId}'s hard failure: an absent role is a legitimate
     * answer here (a caller with no onboarding standing at all), refused by
     * {@link NotAnOnboardingModeratorException} rather than by a 500, so this
     * returns {@code null} instead of throwing.
     */
    static String onboardingModuleRole(Authentication caller) {
        return CallerIdentity.of(caller)
                .flatMap(identity -> identity.moduleRole(ModuleAccessGuard.ONBOARDING))
                .orElse(null);
    }
}
