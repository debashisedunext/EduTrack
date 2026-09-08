package com.edunext.edutrack.api.security.permission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A-114 · the onboarding module's five staff roles against every onboarding
 * route, taken from Onboarding-Module-Plan.md §3.
 *
 * <h2>A second matrix, not more rows in the first one</h2>
 *
 * <p>{@link PermissionMatrix} speaks blueprint §2's six <em>platform</em> roles
 * — ADMIN, PM, DEVELOPER and so on — which is what {@code @PreAuthorize} and
 * {@code RolePermissions} are written in. The onboarding module has its own
 * vocabulary in {@code user_module_access.module_role}, and the two are
 * orthogonal: a platform ADMIN may hold {@code OB_VIEWER} or no onboarding
 * standing at all. Folding them into one table would need a nullable dimension
 * and a predicate on every lookup, and the first person to forget the predicate
 * gets a matrix that answers confidently about the wrong vocabulary.
 *
 * <p>The sixth role in §3's table — <b>Client</b> — is deliberately absent. It
 * is an external principal on {@code /api/v1/portal/**} with its own token
 * shape ({@code ClientPrincipal}, A-125) and its own resolver (A-126), and it
 * reaches none of the routes below. Encoding it here would assert a rule about
 * a caller that cannot arrive.
 *
 * <h2>What is declared here is the plan, not the code</h2>
 *
 * <p>This matters more than usual, so it is stated rather than discovered:
 * <b>today the application enforces exactly one of these rules.</b>
 * {@code POST /journey-steps/{stepId}/skip} checks the module role through
 * {@code CallerIdentityAccess.onboardingModuleRole} and refuses with
 * {@code NotAnOnboardingModeratorException}; the dashboard and report routes
 * read the module role to <em>scope</em> what they return, which is a different
 * thing; and the remaining routes do not consult it at all.
 *
 * <p>So {@link #NOT_YET_ENFORCED} enumerates that gap explicitly, and
 * {@code ObPermissionMatrixTest} pins its size. A matrix that declared
 * twenty-six rules while the code applied one, with nothing recording the
 * difference, would be the same failure this module already produced once —
 * {@code ModuleAccessGuard} shipped written, unit-tested and uncalled for three
 * weeks. Declared-but-unenforced is a legitimate state; declared-but-unenforced
 * <em>and unrecorded</em> is not. A-122 is the task that empties the set.
 */
final class ObPermissionMatrix {

    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";
    static final String OB_VIEWER = "OB_VIEWER";

    /** The five staff roles, as {@code ck_user_module_access_module_role} spells them. */
    static final Set<String> ALL_ROLES =
            Set.of(OB_ADMIN, OB_MANAGER, OB_SALES, OB_STEP_OWNER, OB_VIEWER);

    /** Read-only reach: §3 gives Viewer "everything, read-only". */
    private static final Set<String> EVERY_ROLE = ALL_ROLES;
    /** §3's OB Admin column owns the template catalogue outright. */
    private static final Set<String> ADMIN_ONLY = Set.of(OB_ADMIN);
    /** §3's Manager column: reassign, escalate, override. Admin sees everything. */
    private static final Set<String> ADMIN_AND_MANAGER = Set.of(OB_ADMIN, OB_MANAGER);
    /** A step's own owner acts on it; a moderator overrides it. */
    private static final Set<String> STEP_ACTORS = Set.of(OB_ADMIN, OB_MANAGER, OB_STEP_OWNER);

    /**
     * §3's Sales column — "Board clients, capture sale details, view progress"
     * — beside the two roles that see everything.
     *
     * <p>The client record is the one resource Sales <b>writes</b>, and the only
     * place §3 gives a verb to a role that is not Admin or Manager. Viewer is
     * read-only and a Step Owner owns steps rather than the client the steps
     * hang off, so neither appears here.
     *
     * <p>Sales needs no extra clause for "clients they created": {@code
     * ObClientScope.predicate} has already reduced their visible set to exactly
     * those, so the two rules meet rather than overlap — that class's own note.
     */
    private static final Set<String> CLIENT_WRITERS = Set.of(OB_ADMIN, OB_MANAGER, OB_SALES);

    private ObPermissionMatrix() {
    }

    /**
     * Route key to the module roles §3 permits, keyed exactly as
     * {@link RouteInventory#routeKeys} spells it.
     */
    static final Map<String, Set<String>> ENTRIES = entries();

    private static Map<String, Set<String>> entries() {
        Map<String, Set<String>> m = new LinkedHashMap<>();

        // --- the journey-template catalogue ---------------------------------
        //
        // §3 gives OB Admin "journey templates (create per product, version,
        // publish)" and gives no other role a verb over them. Every write below
        // is therefore Admin's alone. The read is not: Viewer sees "everything,
        // read-only", and a Step Owner who cannot read the template cannot see
        // what their own step is supposed to produce.
        m.put("GET /api/v1/onboarding/journey-templates/{templateId}", EVERY_ROLE);
        m.put("POST /api/v1/onboarding/journey-templates", ADMIN_ONLY);
        m.put("POST /api/v1/onboarding/journey-templates/{templateId}/revisions", ADMIN_ONLY);
        m.put("POST /api/v1/onboarding/journey-templates/{templateId}/publish", ADMIN_ONLY);
        m.put("POST /api/v1/onboarding/journey-templates/{templateId}/steps", ADMIN_ONLY);
        m.put("PUT /api/v1/onboarding/journey-templates/{templateId}/steps/order", ADMIN_ONLY);
        m.put("DELETE /api/v1/onboarding/journey-template-steps/{stepId}", ADMIN_ONLY);
        m.put("POST /api/v1/onboarding/journey-template-steps/{stepId}/docs", ADMIN_ONLY);
        m.put("POST /api/v1/onboarding/journey-template-steps/{stepId}/items", ADMIN_ONLY);
        m.put("DELETE /api/v1/onboarding/journey-template-step-docs/{docId}", ADMIN_ONLY);
        m.put("DELETE /api/v1/onboarding/journey-template-step-items/{itemId}", ADMIN_ONLY);

        // --- the live journey's steps ---------------------------------------
        //
        // §3: Step Owner may "update only their own steps"; Manager may
        // "override steps with logged reason". Both reach these routes and the
        // *row* rule that separates them is OnboardingScopeResolver's (A-112),
        // not this matrix's — a Step Owner passing here still sees only
        // journeys containing their own steps. Sales and Viewer hold no verb
        // over a running step: Sales "views progress", Viewer is read-only.
        // C-111 · OB-06's step panel, added by PR #405 six minutes before the
        // matrix itself merged — so develop was briefly red on
        // everyRouteIsCovered, which is the ratchet doing exactly its job.
        //
        // The read is EVERY_ROLE for the reason the template read is: Viewer
        // sees everything read-only, and a Step Owner who cannot open their own
        // step's panel cannot do the one thing §3 gives them.
        m.put("GET /api/v1/onboarding/journey-steps/{stepId}", EVERY_ROLE);
        // Ticking a checklist entry is working the step, so it is the step
        // actors' — Sales views progress and Viewer is read-only, and neither
        // holds a verb over a running step.
        m.put("PATCH /api/v1/onboarding/journey-step-items/{itemId}", STEP_ACTORS);

        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/start", STEP_ACTORS);
        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/complete", STEP_ACTORS);
        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/block", STEP_ACTORS);
        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/resume", STEP_ACTORS);
        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/waiting-on-client", STEP_ACTORS);
        // Skip is the exception and the only rule the code already applies:
        // it is a moderator action, refused for a Step Owner acting on their
        // own step. ObJourneyStepLifecycleService#skip checks it and answers
        // 404 rather than 403, on the guard's own not-found reasoning.
        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/skip", ADMIN_AND_MANAGER);

        // --- the client master (B-102, B-103, B-104) -------------------------
        //
        // A-114 landed before these routes existed, so it had none of them; they
        // are added here by the branch that brings them, which is what
        // `everyRouteIsCovered` is for.
        //
        // FOUR OF THE NINE ARE B-102's AND ARE ALREADY ON DEVELOP (PR #399),
        // which means develop fails this test on its own — verified on a clean
        // checkout. B-102 merged without them because A-114's matrix and B-102's
        // routes were in flight at the same time and neither PR could see the
        // other; `cb04fdeb` had already repaired the identical collision for
        // C-111's two. They are covered here rather than left for a third repair
        // commit, because a branch cannot go green while its base is red and
        // this branch is the next thing through.
        //
        // The reads are EVERY_ROLE and the writes are not, which is §3's own
        // split: Viewer is "everything, read-only" and a Step Owner reads the
        // journeys containing their steps, so both reach the client they are
        // working against — but neither has a verb over the record itself. WHICH
        // clients each of them sees is ObClientScope.predicate's answer (A-112),
        // not this matrix's: a Sales user reaching the read still sees only the
        // clients they created.
        m.put("GET /api/v1/onboarding/clients", EVERY_ROLE);
        m.put("GET /api/v1/onboarding/clients/{obClientId}", EVERY_ROLE);
        m.put("POST /api/v1/onboarding/clients", CLIENT_WRITERS);
        m.put("PATCH /api/v1/onboarding/clients/{obClientId}", CLIENT_WRITERS);

        // The SPOC panel and the purchases panel inherit the rule of the client
        // they hang off — ObContactService and ObApplicationService both run
        // ObClientScope.mayWrite against the client before touching a child row,
        // so a role that may not edit the client may not edit its contacts or
        // its purchases either. Stated per route rather than by reference,
        // because a reader checking one of these should not have to find the
        // parent to learn the answer.
        m.put("POST /api/v1/onboarding/clients/{obClientId}/contacts", CLIENT_WRITERS);
        m.put("PATCH /api/v1/onboarding/clients/{obClientId}/contacts/{contactId}", CLIENT_WRITERS);
        m.put("DELETE /api/v1/onboarding/clients/{obClientId}/contacts/{contactId}", CLIENT_WRITERS);
        m.put("POST /api/v1/onboarding/clients/{obClientId}/applications", CLIENT_WRITERS);
        m.put("PATCH /api/v1/onboarding/clients/{obClientId}/applications/{applicationId}",
                CLIENT_WRITERS);

        // --- C-112/C-116's communications timeline ---------------------------
        //
        // ALSO NOT THIS BRANCH'S ROUTES. They are already on develop (PR #409)
        // and, like B-102's four above and C-111's two before them, they merged
        // without a matrix entry — so develop fails everyRouteIsCovered on them
        // on its own. Third time in the same window; see the PR body, because
        // the pattern is the finding rather than these three lines.
        //
        // The reads are EVERY_ROLE: Viewer is "everything, read-only", and a
        // Step Owner who cannot read what was said to the client about their own
        // step is missing the context the step is worked from. Posting one is a
        // step action and takes STEP_ACTORS, exactly as start/complete/block do
        // — Sales "views progress" (§3) and holds no verb here.
        //
        // Stream C owns both calls. Flagged for their confirmation.
        m.put("GET /api/v1/onboarding/clients/{obClientId}/communications", EVERY_ROLE);
        m.put("GET /api/v1/onboarding/journey-steps/{stepId}/communications", EVERY_ROLE);
        m.put("POST /api/v1/onboarding/journey-steps/{stepId}/communications", STEP_ACTORS);

        // --- escalations -----------------------------------------------------
        //
        // §3 gives Manager "escalate" and Admin the escalation matrix. The list
        // is readable by Viewer too — it is a dashboard-shaped read and §3's
        // Viewer sees everything read-only — but acknowledging and resolving
        // are the Manager's verbs.
        m.put("GET /api/v1/onboarding/escalations", EVERY_ROLE);
        m.put("POST /api/v1/onboarding/escalations/{escalationId}/acknowledge", ADMIN_AND_MANAGER);
        m.put("POST /api/v1/onboarding/escalations/{escalationId}/resolve", ADMIN_AND_MANAGER);

        // --- dashboard and reports -------------------------------------------
        //
        // §3 names dashboards and reports for Manager and Viewer explicitly,
        // Admin sees everything, and Sales "views progress" for their own
        // clients. So every role reaches them and ObDashboardScope narrows what
        // each one is shown — reach here, scope there, and the distinction is
        // why these are EVERY_ROLE rather than a shorter set.
        m.put("GET /api/v1/onboarding/dashboard/summary", EVERY_ROLE);
        m.put("GET /api/v1/onboarding/reports", EVERY_ROLE);
        m.put("GET /api/v1/onboarding/reports/{reportKey}", EVERY_ROLE);

        // --- the notification centre ------------------------------------------
        //
        // A caller's own bell. Every role has one, and the rows are already
        // filtered to the recipient, so there is no role rule to make here
        // beyond holding the module at all.
        m.put("GET /api/v1/onboarding/notifications", EVERY_ROLE);
        m.put("PATCH /api/v1/onboarding/notifications/read-all", EVERY_ROLE);
        m.put("PATCH /api/v1/onboarding/notifications/{notificationId}/read", EVERY_ROLE);

        return Map.copyOf(m);
    }

    /**
     * The routes whose rule above the application does <b>not</b> yet apply.
     *
     * <p>Every entry here is a declaration waiting for an enforcement point.
     * The list is exact rather than approximate on purpose: {@code
     * ObPermissionMatrixTest} asserts that these and only these are unenforced,
     * so a new onboarding route arriving without a module-role check has to be
     * added here deliberately, in a diff somebody reviews, rather than joining
     * a vague backlog nobody counts.
     *
     * <p><b>A-122 is the task that empties this set.</b> It shrinks; it must
     * never grow silently.
     */
    static final Set<String> NOT_YET_ENFORCED = Set.copyOf(List.of(
            "GET /api/v1/onboarding/journey-templates/{templateId}",
            "POST /api/v1/onboarding/journey-templates",
            "POST /api/v1/onboarding/journey-templates/{templateId}/revisions",
            "POST /api/v1/onboarding/journey-templates/{templateId}/publish",
            "POST /api/v1/onboarding/journey-templates/{templateId}/steps",
            "PUT /api/v1/onboarding/journey-templates/{templateId}/steps/order",
            "DELETE /api/v1/onboarding/journey-template-steps/{stepId}",
            "POST /api/v1/onboarding/journey-template-steps/{stepId}/docs",
            "POST /api/v1/onboarding/journey-template-steps/{stepId}/items",
            "DELETE /api/v1/onboarding/journey-template-step-docs/{docId}",
            "DELETE /api/v1/onboarding/journey-template-step-items/{itemId}",
            "GET /api/v1/onboarding/journey-steps/{stepId}",
            "PATCH /api/v1/onboarding/journey-step-items/{itemId}",
            "POST /api/v1/onboarding/journey-steps/{stepId}/start",
            "POST /api/v1/onboarding/journey-steps/{stepId}/complete",
            "POST /api/v1/onboarding/journey-steps/{stepId}/block",
            "POST /api/v1/onboarding/journey-steps/{stepId}/resume",
            "POST /api/v1/onboarding/journey-steps/{stepId}/waiting-on-client",
            "GET /api/v1/onboarding/escalations",
            "POST /api/v1/onboarding/escalations/{escalationId}/acknowledge",
            "POST /api/v1/onboarding/escalations/{escalationId}/resolve",
            "GET /api/v1/onboarding/dashboard/summary",
            "GET /api/v1/onboarding/reports",
            "GET /api/v1/onboarding/reports/{reportKey}",
            "GET /api/v1/onboarding/notifications",
            "PATCH /api/v1/onboarding/notifications/read-all",
            "PATCH /api/v1/onboarding/notifications/{notificationId}/read",

            // B-102/B-104 · the two client READS. Their declared rule is
            // EVERY_ROLE, and nothing applies it: ModuleAccessGuard is still
            // unwired, so a caller holding no onboarding grant at all reaches
            // them. What such a caller gets is zero rows — ObClientScope
            // .deniesEverything answers the `1 = 0` predicate — which is a row
            // rule (A-112) rather than the module-role reach this set counts.
            "GET /api/v1/onboarding/clients",
            "GET /api/v1/onboarding/clients/{obClientId}",

            // C-112/C-116 · not this branch's routes either. All three carry
            // `@PreAuthorize("isAuthenticated()")` and nothing else, so no
            // module-role rule is applied to any of them today.
            "GET /api/v1/onboarding/clients/{obClientId}/communications",
            "GET /api/v1/onboarding/journey-steps/{stepId}/communications",
            "POST /api/v1/onboarding/journey-steps/{stepId}/communications"));
}
