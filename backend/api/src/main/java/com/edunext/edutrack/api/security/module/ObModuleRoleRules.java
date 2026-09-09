package com.edunext.edutrack.api.security.module;

import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import org.springframework.http.server.PathContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A-122 · which onboarding module role may reach which route, from
 * Onboarding-Module-Plan.md §3.
 *
 * <h2>In main, because A-114's copy could not enforce anything</h2>
 *
 * <p>A-114 wrote this table in test sources and said so: by the time this
 * branch rebased, thirty-eight rules declared and four applied — skip, plus
 * A-117's three module-access routes — with {@code NOT_YET_ENFORCED} counting
 * the difference so it could not grow quietly.
 *
 * <p>Forty-two rules live here, not thirty-eight. The four extra are B-103's
 * three SPOC-contact writes and C-108's step PATCH, which reached develop with
 * no module-role rule at all — the coverage check was red on them before this
 * branch rebased onto it, which is the ratchet doing its job. A rule set living only where tests can see it
 * describes the application rather than constraining it — the same shape
 * {@code ModuleAccessGuard} was in for three weeks before A-111's second half.
 *
 * <p>So the table moves here and {@code ObPermissionMatrix} now reads it rather
 * than restating it. One home, and the test becomes what it should always have
 * been: a check that the shipped rules match §3, not a second opinion about
 * what they are.
 *
 * <h2>Patterns, not strings</h2>
 *
 * <p>The keys are {@link PathPattern}s so {@code /journey-templates/7/publish}
 * matches {@code /journey-templates/&#123;templateId&#125;/publish}. A-114's
 * copy could compare route <em>templates</em> because it read them from the
 * handler mapping; a filter sees concrete URIs and has to match.
 *
 * <h2>The five staff roles, and why Client is not among them</h2>
 *
 * <p>§3's table has six rows. The sixth — Client — is an external principal on
 * {@code /api/v1/portal/**} with its own token shape and its own resolver, and
 * it reaches none of the routes below. A-126 gives that tree its own gate,
 * which refuses a client on a staff route before this class is consulted. A
 * sixth column here would be a rule about a caller that cannot arrive.
 */
@Component
public class ObModuleRoleRules {

    public static final String OB_ADMIN = "OB_ADMIN";
    public static final String OB_MANAGER = "OB_MANAGER";
    public static final String OB_SALES = "OB_SALES";
    public static final String OB_STEP_OWNER = "OB_STEP_OWNER";
    public static final String OB_VIEWER = "OB_VIEWER";

    /** As {@code ck_user_module_access_module_role} spells them. */
    public static final Set<String> ALL_ROLES =
            Set.of(OB_ADMIN, OB_MANAGER, OB_SALES, OB_STEP_OWNER, OB_VIEWER);

    /** §3's Viewer sees everything read-only, so a read is every role's. */
    private static final Set<String> EVERY_ROLE = ALL_ROLES;
    /** §3 gives OB Admin the template catalogue and no other role a verb over it. */
    private static final Set<String> ADMIN_ONLY = Set.of(OB_ADMIN);
    /** §3's Manager column: reassign, escalate, override. Admin sees everything. */
    private static final Set<String> ADMIN_AND_MANAGER = Set.of(OB_ADMIN, OB_MANAGER);
    /** A step's owner acts on it; a moderator overrides it. Sales and Viewer hold no verb. */
    private static final Set<String> STEP_ACTORS = Set.of(OB_ADMIN, OB_MANAGER, OB_STEP_OWNER);
    /**
     * §3's Sales row is the only one with "board clients, capture sale
     * details". Manager's column is reassign, escalate, verify/skip, override
     * and client logins — it does not include creating or editing the client
     * record, so Manager is deliberately absent here despite seeing every
     * journey. Admin does everything.
     */
    private static final Set<String> ADMIN_AND_SALES = Set.of(OB_ADMIN, OB_SALES);

    /**
     * B-107 · the three roles {@code ObClientScope.mayWrite} lets edit a client
     * they can see. Used by the documents card, where the filter must be able to
     * reach every arm of the service's own check — see the block on the
     * attachment routes for why this is deliberately wider than
     * {@link #ADMIN_AND_SALES}.
     */
    private static final Set<String> CLIENT_DOCUMENT_ACTORS =
            Set.of(OB_ADMIN, OB_MANAGER, OB_SALES);

    private static final PathPatternParser PARSER = new PathPatternParser();

    /**
     * Insertion-ordered, and the order is load-bearing: {@link #rolesFor} takes
     * the first match, so a more specific pattern must precede the one that
     * would also match it. {@code /journey-templates/&#123;id&#125;/publish}
     * before any {@code /journey-templates/**} would be the case; none is
     * written today and the ordering is kept so none can be added wrongly.
     */
    private final List<Rule> rules = rules();

    /** One row of §3's table, as the filter needs it. */
    private record Rule(String method, PathPattern path, String routeKey, Set<String> roles) {
    }

    private static List<Rule> rules() {
        List<Rule> m = new ArrayList<>();
        // A-124 · the product catalogue. §3 gives OB Admin the catalogue
        // outright, on the same line that gives it journey templates: a product
        // is what a template binds to, and a role that may create one and not
        // the other can publish a template for a product it cannot name. So
        // both writes are Admin's alone.
        //
        // The reads are every role's, and Sales is the reason worth stating
        // rather than deriving. §3 gives Sales the OB-04 wizard, whose purchase
        // step is a multi-select over exactly this catalogue — a Sales user who
        // cannot list products cannot board a client at all. Viewer reads
        // everything, and a Step Owner needs the product's name to know which
        // journey their step belongs to.
        put(m, "GET", "/api/v1/onboarding/products", EVERY_ROLE);
        put(m, "GET", "/api/v1/onboarding/products/{obProductId}", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/products", ADMIN_ONLY);
        put(m, "PATCH", "/api/v1/onboarding/products/{obProductId}", ADMIN_ONLY);

        put(m, "GET", "/api/v1/onboarding/journey-templates", EVERY_ROLE);
        put(m, "GET", "/api/v1/onboarding/journey-templates/{templateId}", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/journey-templates", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/journey-templates/{templateId}/revisions", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/journey-templates/{templateId}/publish", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/journey-templates/{templateId}/steps", ADMIN_ONLY);
        put(m, "PUT", "/api/v1/onboarding/journey-templates/{templateId}/steps/order", ADMIN_ONLY);
        // C-123 · the Module Service catalogue's own two writes — the same
        // ADMIN_ONLY every other journey-templates write above already carries.
        put(m, "PUT", "/api/v1/onboarding/journey-templates/order", ADMIN_ONLY);
        put(m, "PUT", "/api/v1/onboarding/journey-templates/{templateId}/depends-on", ADMIN_ONLY);
        put(m, "DELETE", "/api/v1/onboarding/journey-template-steps/{stepId}", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/journey-template-steps/{stepId}/docs", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/journey-template-steps/{stepId}/items", ADMIN_ONLY);
        put(m, "DELETE", "/api/v1/onboarding/journey-template-step-docs/{docId}", ADMIN_ONLY);
        put(m, "DELETE", "/api/v1/onboarding/journey-template-step-items/{itemId}", ADMIN_ONLY);

        // B-124 · the prerequisites master (OB-14). §3 gives OB Admin
        // "prerequisites master" on the same line that gives it journey
        // templates, and gives no other role a verb over it, so every write
        // here is Admin's alone — the shape the block above already has.
        //
        // The read is every role's, and Manager and Step Owner are the reason
        // to say so rather than derive it. §3 does give them verbs over
        // prerequisite *instances* — "verify/skip prerequisites", "verify
        // prerequisite submissions routed to them" — which are B-125's routes,
        // not these. What a verifier needs from the master is the wording and
        // the TAT the client was actually asked against; unreadable, the
        // instance verbs become guesswork. Sales reads it for OB-04, where the
        // checklist is what a client is being signed up to.
        put(m, "GET", "/api/v1/onboarding/prereq-template", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/prereq-template/revisions", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/prereq-template/publish", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/prereq-template/tasks", ADMIN_ONLY);
        put(m, "PUT", "/api/v1/onboarding/prereq-template/tasks/order", ADMIN_ONLY);
        put(m, "PATCH", "/api/v1/onboarding/prereq-template-tasks/{templateTaskId}", ADMIN_ONLY);
        put(m, "DELETE", "/api/v1/onboarding/prereq-template-tasks/{templateTaskId}", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/prereq-template-tasks/{templateTaskId}/docs", ADMIN_ONLY);
        put(m, "DELETE", "/api/v1/onboarding/prereq-template-task-docs/{docId}", ADMIN_ONLY);

        // B-125 · the per-client instances. Where the master above is Admin's
        // alone, these are worked by several roles, and §3 names each:
        //
        //   read      — every role. Sales needs it for OB-04, a Step Owner to
        //               see why their journey has not started, Viewer because
        //               Viewer reads everything.
        //   submit    — "verify prerequisite submissions routed to them" gives
        //               Step Owner a verb here, and the staff submit path
        //               exists because a SPOC emails documents; STEP_ACTORS is
        //               the same set the step lifecycle uses.
        //   verify /
        //   return    — Manager's "verify/skip prerequisites", plus the Step
        //               Owner §3 routes submissions to. Sales does not verify
        //               what they sold.
        //   skip      — ADMIN_AND_MANAGER, and only them. Plan §5.3's only
        //               valve; the service refuses a *mandatory* task to
        //               everyone including these two.
        //   ad-hoc
        //   add/edit  — ADMIN_AND_MANAGER. Adding a mandatory task re-locks a
        //               gate, which is the same weight as waiving one.
        put(m, "GET", "/api/v1/onboarding/clients/{obClientId}/prereqs", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/clients/{obClientId}/prereq-tasks", ADMIN_AND_MANAGER);
        put(m, "GET", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}", EVERY_ROLE);
        put(m, "PATCH", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}", ADMIN_AND_MANAGER);
        put(m, "POST", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/submit", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/verify", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/return", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/skip", ADMIN_AND_MANAGER);
        put(m, "GET", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/comments", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/comments", STEP_ACTORS);
        put(m, "GET", "/api/v1/onboarding/prereq-tasks/{prereqTaskId}/history", EVERY_ROLE);

        // C-110 · OB-05's expanded ribbon. Every role, on the same footing as
        // the step read below it: a Viewer who may see the client's collapsed
        // strip inside getObClient may see the same journey expanded, and the
        // rows are the ones that strip already summarised.
        put(m, "GET", "/api/v1/onboarding/journeys/{journeyId}", EVERY_ROLE);

        put(m, "GET", "/api/v1/onboarding/journey-steps/{stepId}", EVERY_ROLE);
        put(m, "PATCH", "/api/v1/onboarding/journey-step-items/{itemId}", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/start", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/complete", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/block", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/resume", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/waiting-on-client", STEP_ACTORS);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/skip", ADMIN_AND_MANAGER);
        // C-108 · reassign or re-plan a live step. A moderator's, not a step
        // actor's, and ObJourneyStepLifecycleService#update already says so
        // through the same requireModerator that gates skip: an owner
        // reassigning the step off themselves is the one rewrite the route
        // must not grant silently. The rule is repeated here so it survives
        // that method rather than depending on it.
        put(m, "PATCH", "/api/v1/onboarding/journey-steps/{stepId}", ADMIN_AND_MANAGER);

        // B-102 · the client master. Reads are every role's — Viewer sees
        // everything read-only, and Sales sees the clients they created, which
        // is OnboardingScopeResolver's narrowing rather than this table's.
        put(m, "GET", "/api/v1/onboarding/clients", EVERY_ROLE);
        put(m, "GET", "/api/v1/onboarding/clients/{obClientId}", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/clients", ADMIN_AND_SALES);
        put(m, "PATCH", "/api/v1/onboarding/clients/{obClientId}", ADMIN_AND_SALES);

        // B-103 · the SPOC contacts, which are part of the client record
        // rather than of a journey, so they take the client's own rule.
        // There is no read here because there is no read route: contacts
        // arrive inside the client response, already covered above.
        put(m, "POST", "/api/v1/onboarding/clients/{obClientId}/contacts", ADMIN_AND_SALES);
        put(m, "PATCH", "/api/v1/onboarding/clients/{obClientId}/contacts/{contactId}", ADMIN_AND_SALES);
        put(m, "DELETE", "/api/v1/onboarding/clients/{obClientId}/contacts/{contactId}", ADMIN_AND_SALES);

        // B-104 · the purchases. Same rule as the SPOCs one block up and for
        // the same reason: ObApplicationService runs ObClientScope.mayWrite
        // against the parent client before it touches a purchase, so a role
        // that may not edit the client may not record what it bought either.
        //
        // No read route and no DELETE. Purchases arrive inside the client
        // response like contacts do, and there is no delete because
        // fk_ob_journeys_application is RESTRICT and every purchase carries a
        // journey from the moment it is made — ObApplicationService has the
        // argument in full.
        put(m, "POST", "/api/v1/onboarding/clients/{obClientId}/applications", ADMIN_AND_SALES);
        put(m, "PATCH", "/api/v1/onboarding/clients/{obClientId}/applications/{applicationId}",
                ADMIN_AND_SALES);

        // B-106 · the requirements list, on the client's rule for the third
        // time — ObRequirementService.requireWritableClient runs the same
        // ObClientScope.mayWrite before it touches a row, so raising, rewording
        // and ticking off a requirement all belong to whoever may edit the
        // client. There IS a DELETE here where the purchases have none: nothing
        // references ob_client_requirements, so removing one destroys no record
        // of work that was done.
        put(m, "POST", "/api/v1/onboarding/clients/{obClientId}/requirements", ADMIN_AND_SALES);
        put(m, "PATCH", "/api/v1/onboarding/clients/{obClientId}/requirements/{requirementId}",
                ADMIN_AND_SALES);
        put(m, "DELETE", "/api/v1/onboarding/clients/{obClientId}/requirements/{requirementId}",
                ADMIN_AND_SALES);

        // B-107 · OB-05's documents card. The read is every role's, on §3's
        // "Viewer sees everything, read-only".
        //
        // The two writes are NOT ADMIN_AND_SALES, which is the first block on
        // this client that is not, so the reason is written down rather than
        // left to look like a slip. ObClientAttachmentService decides both with
        // ObClientScope.mayWrite() — Admin, Manager, Sales — and the filter has
        // to be able to reach every arm of a check the service makes, or the
        // Manager arm is dead code that nothing exercises and nothing notices
        // rotting. The product reading agrees: what these routes carry is
        // evidence about the work (a signed acceptance, a returned form), not
        // the client record's own fields, and §3 gives the Manager oversight of
        // the work while giving Sales the record.
        //
        // Worth noting in passing rather than fixing here: the contract's
        // updateObClient names "OB Admin, Onboarding Manager, and Sales" as its
        // writers too, so the ADMIN_AND_SALES rows above are already narrower
        // than the contract they enforce. That is Stream A's call to reconcile,
        // and B-107 deliberately did not widen somebody else's rows to make its
        // own look consistent.
        put(m, "GET", "/api/v1/onboarding/clients/{obClientId}/attachments", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/clients/{obClientId}/attachments",
                CLIENT_DOCUMENT_ACTORS);
        put(m, "DELETE", "/api/v1/onboarding/clients/{obClientId}/attachments/{attachmentId}",
                CLIENT_DOCUMENT_ACTORS);

        // C-112 · the communication log, which landed on develop while this
        // branch was open. Reading one is every role's — Viewer sees
        // everything read-only and Sales "views progress". Recording one is a
        // verb over a running step, so it is the step actors': Sales and
        // Viewer hold none.
        //
        // Both sit a segment deeper than the reads above, and PathPattern
        // {id} matches exactly one segment, so neither
        // /clients/{obClientId} nor /journey-steps/{stepId} shadows them.
        put(m, "GET", "/api/v1/onboarding/clients/{obClientId}/communications", EVERY_ROLE);
        put(m, "GET", "/api/v1/onboarding/journey-steps/{stepId}/communications", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/journey-steps/{stepId}/communications", STEP_ACTORS);

        put(m, "GET", "/api/v1/onboarding/escalations", EVERY_ROLE);
        put(m, "POST", "/api/v1/onboarding/escalations/{escalationId}/acknowledge", ADMIN_AND_MANAGER);
        put(m, "POST", "/api/v1/onboarding/escalations/{escalationId}/resolve", ADMIN_AND_MANAGER);

        put(m, "GET", "/api/v1/onboarding/dashboard/summary", EVERY_ROLE);
        put(m, "GET", "/api/v1/onboarding/reports", EVERY_ROLE);
        put(m, "GET", "/api/v1/onboarding/reports/{reportKey}", EVERY_ROLE);

        put(m, "GET", "/api/v1/onboarding/notifications", EVERY_ROLE);
        put(m, "PATCH", "/api/v1/onboarding/notifications/read-all", EVERY_ROLE);
        put(m, "PATCH", "/api/v1/onboarding/notifications/{notificationId}/read", EVERY_ROLE);

        // A-117 · module access (OB-08), which landed on develop while this
        // branch was open. Admin alone on all three, the read included, and
        // the read is the one worth pausing on: §3 makes Viewer "everything,
        // read-only" and this is the single place that does not follow. Who
        // can reach a module is not onboarding data, it is the access-control
        // table for the module itself, and a Viewer able to enumerate every
        // administrator has been handed the list of accounts worth attacking.
        //
        // ObModuleAccessController already refuses a non-admin with 403 of its
        // own. The rule is stated here anyway: A-117's check is one service's
        // habit, and the point of this class is that the rule holds whether or
        // not the next route in the package remembers to repeat it. The two
        // agree on the status, so the filter refusing first changes nothing a
        // caller can see.
        put(m, "GET", "/api/v1/onboarding/module-access", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/module-access", ADMIN_ONLY);
        put(m, "POST", "/api/v1/onboarding/module-access/{grantId}/revoke", ADMIN_ONLY);

        return List.copyOf(m);
    }

    private static void put(List<Rule> m, String method, String path, Set<String> roles) {
        m.add(new Rule(method, PARSER.parse(path), method + " " + path, roles));
    }

    /**
     * The roles §3 permits on this request, if this class has an opinion.
     *
     * <p>Empty means <b>no rule</b>, not <b>no roles</b>. The two must not be
     * confused: a route with no entry is one nobody has decided about, and the
     * filter lets it through so that adding an onboarding endpoint does not
     * silently 403 everybody. {@code
     * ObPermissionMatrixTest#everyRouteIsCovered} is what refuses a route with
     * no entry — at build time, where somebody can fix it.
     */
    public Optional<Set<String>> rolesFor(String method, String requestPath) {
        if (method == null || requestPath == null) {
            return Optional.empty();
        }
        PathContainer path = PathContainer.parsePath(requestPath);
        for (Rule rule : rules) {
            if (rule.method().equalsIgnoreCase(method) && rule.path().matches(path)) {
                return Optional.of(rule.roles());
            }
        }
        return Optional.empty();
    }

    /** Every rule, for the test that checks them against the handler mapping. */
    public Map<String, Set<String>> asRouteKeys() {
        Map<String, Set<String>> byKey = new LinkedHashMap<>();
        rules.forEach(rule -> byKey.put(rule.routeKey(), rule.roles()));
        return Map.copyOf(byKey);
    }
}
