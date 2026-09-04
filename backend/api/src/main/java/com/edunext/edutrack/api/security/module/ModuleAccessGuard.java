package com.edunext.edutrack.api.security.module;

import com.edunext.edutrack.api.security.CallerIdentity;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A-111 · the module gate. Onboarding plan §2.1.
 *
 * <h2>Why this is not called {@code ModuleGuard}</h2>
 *
 * <p>Because that name is taken, by
 * {@code feature.tickets.ModuleGuard} (C-067), and it means something
 * else entirely: a ticket's {@code moduleId} is a functional area of a
 * product from the {@code product_modules} master, and that guard refuses a
 * deactivated one on write with a <b>400</b>.
 *
 * <p>The collision was predicted — A-109's migration declines to put a
 * foreign key on {@code user_module_access.module} for exactly this reason,
 * "a collision of two unrelated meanings on one word" — and then found at
 * runtime anyway, as a Spring {@code ConflictingBeanDefinitionException},
 * because both classes are {@code @Component} and Spring names beans by
 * simple class name.
 *
 * <p>{@code ModuleAccessGuard} matches the table it reads from
 * ({@code user_module_access}) rather than the plan's shorter word. Worth
 * the divergence: the two guards answer different questions with different
 * status codes, and a reader who conflates them would be looking at a 400
 * where this file is careful to give a 404.
 *
 * <blockquote>a <b>ModuleGuard</b> before RolesGuard on every
 * {@code /api/onboarding/**} route; no entitlement → <b>404</b></blockquote>
 *
 * <h2>Why 404 and not 403, on a whole module</h2>
 *
 * <p>CONVENTIONS.md §7 already answers 404 for an out-of-scope <em>row</em>,
 * because a 403 on {@code /tickets/CRM-26-00347} confirms that ticket exists.
 * This is the same argument one level up and it gets larger, not smaller: a
 * 403 on {@code /api/v1/onboarding/clients} tells a ticketing-only user that
 * the onboarding module is deployed — which is a fact about what the
 * organisation bought, disclosed to somebody the organisation decided should
 * not have it.
 *
 * <p>So the module is indistinguishable from a typo. That is also why the
 * refusal happens <b>before</b> RolesGuard rather than after: a role check
 * that runs first would answer 403 for the wrong reason and leak the same
 * fact, and running it second means the caller never reaches a handler that
 * could tell them a module role exists.
 *
 * <h2>Built now, enforced by the chain</h2>
 *
 * <p><b>Nothing calls this yet</b>, and that is the shape this codebase
 * already uses twice: {@code PasswordChangeGate} (A-026) and A-025's
 * blacklist were both written with their tests and wired later by the task
 * that owns the filter chain. There are no {@code /api/v1/onboarding/**}
 * handlers to guard until B and C build them, so a gate wired into the chain
 * today would be a filter with nothing behind it.
 *
 * <p>Calling it out because a decision that is written and never asked looks
 * like dead code on reading, and is instead the first half of a two-task
 * change.
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not authorisation <em>within</em> the module. A caller who holds
 * {@code ONBOARDING} passes this gate and is then subject to the module's own
 * six roles (plan §3) and to {@code OnboardingScopeResolver} (A-112), which
 * decides which clients and journeys they see. This answers exactly one
 * question — may this caller reach this module at all — and answering more
 * would put two different refusals behind one status code.
 */
@Component
public class ModuleAccessGuard {

    /** Plan §2.1's module codes, and {@code user_module_access}' CHECK (A-109). */
    public static final String ONBOARDING = "ONBOARDING";
    public static final String TICKETING = "TICKETING";

    /**
     * Path prefixes the onboarding module answers on, with the {@code /api/v1}
     * that {@code HttpServletRequest#getRequestURI} hands the chain — the same
     * full-path convention {@code PasswordChangeGate.ALWAYS_ALLOWED} uses, and
     * for the same reason.
     *
     * <p>{@code /api/v1/portal/} is here as well as {@code /api/v1/onboarding/}
     * because plan §2.3 puts the client principal's routes in their own tree,
     * and those are onboarding routes too. A staff caller without the module
     * must not reach them either.
     */
    private static final String[] GUARDED_PREFIXES = {
            "/api/v1/onboarding/",
            "/api/v1/portal/",
    };

    /**
     * Whether {@code requestPath} is one this gate has an opinion about.
     *
     * <p>Prefix matching here, where {@code PasswordChangeGate} matches
     * exactly, and the difference is not an oversight: that gate holds a
     * three-entry allowlist with no path parameters, while this one covers
     * every route under two trees including ids. The traversal risk that made
     * exact matching right there does not arise, because a prefix match that
     * is wrong here <em>widens</em> what is guarded rather than what is
     * allowed — {@code /api/v1/onboarding/../tickets} is guarded when it need
     * not be, which costs a 404 to a caller who could have had a 200 and
     * discloses nothing.
     */
    public boolean guards(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        for (String prefix : GUARDED_PREFIXES) {
            if (requestPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this request must be refused with
     * {@link ModuleNotEntitledException}, which the chain renders as 404.
     *
     * <p><b>An absent caller blocks.</b> {@link CallerIdentity#of} returns
     * empty for no authentication, an anonymous token, an unrecognised
     * principal or an unreadable {@code sub}, and its own javadoc requires
     * callers to treat that as "sees nothing". A guard that let an
     * unidentifiable caller past on the grounds that authentication should
     * have caught them first is a guard that depends on another guard being
     * present, which is the assumption every layered defence exists to avoid.
     *
     * @param caller      the caller, or empty when nobody identifiable is authenticated
     * @param requestPath the servlet path, e.g. {@code /api/v1/onboarding/clients}
     */
    public boolean blocks(Optional<CallerIdentity> caller, String requestPath) {
        if (!guards(requestPath)) {
            return false;
        }
        return caller.map(identity -> !identity.hasModule(ONBOARDING)).orElse(true);
    }
}
