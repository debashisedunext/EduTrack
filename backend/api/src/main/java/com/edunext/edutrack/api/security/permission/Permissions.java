package com.edunext.edutrack.api.security.permission;

import java.util.Set;

/**
 * A-033 · the eighteen capability codes of blueprint §2, as Java constants.
 *
 * <p>The authority is the database — {@code V20260806_0900__seed_roles_permissions.sql}
 * seeds the {@code permissions} table and A-022's JWT carries whatever that table
 * says. This class is the compile-time mirror of it, and
 * {@code PermissionCatalogTest} parses the migration to prove the two have not
 * drifted. A code that exists in one and not the other fails the build rather
 * than producing a {@code @PreAuthorize} that can never be satisfied — which is
 * a permission check that denies everybody, and reads in review exactly like one
 * that works.
 *
 * <h2>Why the annotations use string literals and not these constants</h2>
 *
 * <p>{@code @PreAuthorize} takes SpEL, so referring to a constant means
 * {@code hasAuthority(T(com.edunext.edutrack.api.security.permission.Permissions).TICKET_ASSIGN)}
 * — 80 characters that say what {@code hasAuthority('ticket.assign')} says in
 * 30, on every one of forty-odd routes. The compile-time safety that buys is
 * recovered instead by {@code RouteAuthorizationTest}, which extracts every
 * literal from every annotation in the application context and asserts it names
 * a code in {@link #ALL}. A typo fails the build either way; only one of the two
 * is readable at the point of use.
 *
 * <p>These constants exist for Java callers — {@link RolePermissions}, the
 * authority converter, and A-036's matrix.
 *
 * <h2>Permission is not scope</h2>
 *
 * <p>Every code here answers "may this role do this thing at all". It does not
 * answer "to which rows" — the §2 matrix's {@code ✅ (own)}, "Limited" and
 * "Own perf." qualifiers are row scope, which is A-034's {@code ScopeResolver}
 * and belongs nowhere near an annotation. The seed migration's header makes the
 * same split, deliberately.
 */
public final class Permissions {

    // --- admin -------------------------------------------------------------

    /** Create/edit resources, roles and reporting-manager assignment. Admin only. */
    public static final String RESOURCE_MANAGE = "resource.manage";

    /** Create/edit projects; map resources to a project. Admin and PM. */
    public static final String PROJECT_MANAGE = "project.manage";

    // --- ticket ------------------------------------------------------------

    /** Raise a new ticket. All six roles. */
    public static final String TICKET_CREATE = "ticket.create";

    /** Assign or reassign a ticket. Admin, PM, Support. */
    public static final String TICKET_ASSIGN = "ticket.assign";

    /** Move a ticket forward along the ribbon. All six roles. */
    public static final String TICKET_HANDOFF = "ticket.handoff";

    /** Return a ticket to a prior stage for rework. All six roles. */
    public static final String TICKET_REWORK = "ticket.rework";

    /** Skip a ribbon stage, with a mandatory reason. Admin and PM. */
    public static final String TICKET_SKIP_STAGE = "ticket.skip_stage";

    /** Force-move the ribbon backwards outside the normal flow. Admin and PM. */
    public static final String TICKET_FORCE_MOVE = "ticket.force_move";

    /**
     * View tickets beyond only those assigned to the caller. Admin, PM, Support.
     *
     * <p>Holding this is not "see everything" — a PM holds it and still sees only
     * their own projects. It says the caller's ticket queries are <em>not</em>
     * hard-pinned to {@code assigned_to = me}; where the ceiling actually falls
     * is A-034's.
     */
    public static final String TICKET_VIEW_ALL = "ticket.view_all";

    /** View tickets assigned to the caller. Developer, QA, Deployment. */
    public static final String TICKET_VIEW_ASSIGNED = "ticket.view_assigned";

    /** Update status or log effort on an assigned ticket. All six roles. */
    public static final String TICKET_UPDATE_PROGRESS = "ticket.update_progress";

    /** Close a ticket. Admin, PM, Support — never Developer (PLAN.md G-3). */
    public static final String TICKET_CLOSE = "ticket.close";

    /** Reopen a closed ticket. Admin, PM, Support. */
    public static final String TICKET_REOPEN = "ticket.reopen";

    // --- history -----------------------------------------------------------

    /**
     * Mutate {@code ticket_history}, {@code ticket_effort_logs} or
     * {@code ticket_stage_transitions}. <b>Granted to nobody, by design.</b>
     *
     * <p>Blueprint §2: "Edit / delete history or ribbon — ❌ (nobody can)". The
     * code exists so S-09's Role &amp; Permission Master can render the row,
     * greyed out, rather than silently omitting a capability and leaving an Admin
     * to wonder whether it was forgotten. {@link RolePermissions} asserts it has
     * zero grants; CLAUDE.md's append-only rule is what would break if it gained
     * one, and three further layers would still refuse.
     */
    public static final String HISTORY_EDIT_DELETE = "history.edit_delete";

    /** View ticket history belonging to other team members. Admin, PM, Support. */
    public static final String HISTORY_VIEW_TEAM = "history.view_team";

    // --- reports, masters, audit -------------------------------------------

    /** View the reports section. All six roles — the §2 qualifiers are row scope. */
    public static final String REPORTS_VIEW = "reports.view";

    /** Create/edit task types, SLA, workflow, holidays and other masters. Admin only. */
    public static final String MASTER_WRITE = "master.write";

    /** View the audit log. Admin only. */
    public static final String AUDIT_VIEW = "audit.view";

    /** Every code above, for validating annotations and building A-036's matrix. */
    public static final Set<String> ALL = Set.of(
            RESOURCE_MANAGE,
            PROJECT_MANAGE,
            TICKET_CREATE,
            TICKET_ASSIGN,
            TICKET_HANDOFF,
            TICKET_REWORK,
            TICKET_SKIP_STAGE,
            TICKET_FORCE_MOVE,
            TICKET_VIEW_ALL,
            TICKET_VIEW_ASSIGNED,
            TICKET_UPDATE_PROGRESS,
            TICKET_CLOSE,
            TICKET_REOPEN,
            HISTORY_EDIT_DELETE,
            HISTORY_VIEW_TEAM,
            REPORTS_VIEW,
            MASTER_WRITE,
            AUDIT_VIEW);

    private Permissions() {
    }
}
