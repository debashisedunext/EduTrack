package com.edunext.edutrack.api.security.permission;

import java.util.List;
import java.util.Set;

import static com.edunext.edutrack.api.security.permission.RolePermissions.ADMIN;
import static com.edunext.edutrack.api.security.permission.RolePermissions.PM;

/**
 * A-036 · the role × route matrix, written down by hand.
 *
 * <h2>Why this is data and not derived</h2>
 *
 * <p>Every expectation below could be computed: read the route's
 * {@code @PreAuthorize}, look the capability up in {@link RolePermissions}, and
 * emit allow or deny. That test would pass on every build and prove nothing at
 * all, because the annotation would be both the thing under test and the source
 * of the expectation. A route annotated {@code hasAuthority('ticket.create')}
 * that should have said {@code master.write} is exactly the mistake such a test
 * cannot see, and it is the mistake A-033's coverage check explicitly does not
 * look for — that one asserts a decision <em>exists</em>, not that it is right.
 *
 * <p>So these rows are authored from blueprint §2 and the screen each route
 * serves, independently of the annotation, and the assertion is that the two
 * agree. When they disagree, one of them is wrong and a human decides which —
 * which is the entire value of writing it twice.
 *
 * <h2>Allowed is stated; denied is the complement</h2>
 *
 * <p>Each entry names the roles that may reach the route. The other roles are
 * denied, computed rather than listed, so all six are still asserted for every
 * route — CLAUDE.md's "permission-matrix entries for all six roles" — without a
 * writer being able to produce a five-role row by forgetting a line. Listing
 * both sides was the first draft and its failure mode is a role that appears in
 * neither list and is therefore never asserted, which reads as coverage.
 *
 * <h2>Every route that takes a body carries one, allowed rows included</h2>
 *
 * <p>{@code @RequestBody} is resolved during handler-argument resolution, which
 * happens <em>before</em> method-invocation advice — so {@code @PreAuthorize}
 * has not run yet and a caller sending an absent or invalid body gets 400
 * rather than the authorisation outcome. {@code RouteAuthorizationTest}
 * documents that at length and pins it.
 *
 * <p>The consequence is that a row without a valid body asserts <em>nothing</em>:
 * the 400 arrives before the guard is consulted, and the case passes whatever
 * the annotation says. That was true of the first draft of this file, which
 * carried bodies only on rows with a denied role — and it was caught by
 * deliberately mis-stating a row and watching the suite stay green. Allowed rows
 * have exactly the same hole in the other direction: a route wrongly restricted
 * to Admin would answer 403 to a Developer, and a Developer sending no body
 * would never find out.
 *
 * <p>So every entry whose handler declares a required {@code @RequestBody}
 * carries a fixture satisfying that DTO's constraints, and
 * {@code PermissionMatrixTest.everyRouteWithARequiredBodyCarriesAFixture}
 * enforces it against the handler signatures rather than against this comment.
 *
 * <h2>Anonymous is not a column</h2>
 *
 * <p>Whether an unauthenticated caller can reach a route is
 * {@code RouteAuthorizationTest.onlyTheContractsPublicOperationsArePublic}'s
 * question, and it already asserts the public set is exactly the contract's six
 * {@code security: []} operations. Restating it as a seventh column here would
 * mean two places to update when a public route is added, and the one nobody
 * remembered would be the one that mattered. The five {@code permitAll} routes
 * below are entered as "every role", which is what they are once signed in.
 */
final class PermissionMatrix {

    enum Outcome {

        /**
         * Authorisation let the request through. Asserted as "not 403" rather
         * than as 200: these suites run without a database, so a handler that
         * was reached still fails in its body, and the claim being made is
         * about the guard and not about the feature.
         */
        ALLOW,

        /** Refused for want of a capability: 403, never 401 and never 404. */
        DENY
    }

    /** All six §2 roles. The common case — most routes are capability-free reads. */
    static final Set<String> EVERY_ROLE = RolePermissions.ROLE_CODES;

    /** {@code master.write}, {@code resource.manage} and {@code audit.view} are Admin's alone today. */
    static final Set<String> ADMIN_ONLY = Set.of(ADMIN);

    /**
     * {@code project.manage} — the one §2 capability that is not Admin's alone.
     *
     * <p>§2 row 2: "Create/edit projects, map resources to project — Admin ✅,
     * PM ✅ (own), Support ❌, Developer ❌, QA ❌, Deployment ❌". <b>The
     * "(own)" is a row-scope qualifier, not a capability one</b>, and that
     * distinction is why this set has two members rather than one: PM holds the
     * capability outright, and <em>which</em> projects a PM may edit is A-034's
     * question, asked of rows, after this one has been answered. Encoding "own"
     * here would mean answering a row question with a token claim, which is the
     * confusion §10.2 exists to prevent.
     *
     * <p>It also makes this the only pair of rows in the file where a wrong
     * answer is invisible from the Admin smoke test everybody runs: Support,
     * Developer, QA and Deployment must all be refused, and PM must not be.
     */
    static final Set<String> ADMIN_AND_PM = Set.of(ADMIN, PM);

    // ── request bodies that satisfy their DTO's constraints ──────────────────
    //
    // None of these has to succeed. They have to be *valid*, so that argument
    // resolution passes the request on to the guard, which is the only thing
    // being asserted. A password that no account holds is exactly right here.

    /** {@code LoginRequest}: username and password are both {@code @NotBlank}. */
    private static final String LOGIN = """
            {"username":"matrix.fixture","password":"Correct-Horse-1!"}""";

    /** {@code ForgotPasswordRequest}: a syntactically valid, untrimmed-free address. */
    private static final String FORGOT_PASSWORD = """
            {"email":"matrix.fixture@edunext.test"}""";

    /** {@code ResetPasswordRequest}: the token is {@code @Size(min = 32)}. */
    private static final String RESET_PASSWORD = """
            {"token":"matrix-fixture-token-0123456789abcdef","newPassword":"Correct-Horse-1!"}""";

    /** {@code ChangePasswordRequest}: both fields {@code @ValidPassword}-shaped. */
    private static final String CHANGE_PASSWORD = """
            {"currentPassword":"Correct-Horse-1!","newPassword":"Correct-Horse-2!"}""";

    /** {@code TwoFactorRequests.ConfirmRequest}: six digits exactly. */
    private static final String TOTP_CODE = """
            {"code":"123456"}""";

    /** {@code TwoFactorRequests.DisableRequest}: the account password. */
    private static final String TOTP_DISABLE = """
            {"password":"Correct-Horse-1!"}""";

    /** {@code NotificationDtos.DeliveredRequest}. */
    private static final String DELIVERED = """
            {"ids":[1]}""";

    /** {@code PreferenceDtos.PreferenceUpdateRequest}: an empty list is valid. */
    private static final String PREFERENCES = """
            {"preferences":[]}""";

    /** {@code PushDtos.PushSubscriptionRequest}. */
    private static final String PUSH_SUBSCRIPTION = """
            {"endpoint":"https://push.example.test/matrix-fixture",\
            "keys":{"p256dh":"matrix-p256dh","auth":"matrix-auth"}}""";

    /** {@code ChatDtos.PostMessage} and {@code EditMessage}: a non-blank body. */
    private static final String CHAT_MESSAGE = """
            {"body":"matrix fixture"}""";

    /**
     * The two mail webhooks take {@code @RequestBody byte[]} — the signature
     * over the raw bytes is their real gate, and any bytes satisfy argument
     * resolution.
     */
    private static final String RAW_BYTES = "{}";

    /** {@code HolidayWrite}: date and name are required. */
    private static final String HOLIDAY = """
            {"date":"2026-01-26","name":"Republic Day"}""";

    /** {@code WorkingWeekUpdate}: all four fields required, weeklyOff at most six days. */
    private static final String WORKING_WEEK = """
            {"weeklyOff":[7],"workDayStart":"09:30:00","workDayEnd":"18:30:00","timezone":"Asia/Kolkata"}""";

    /** {@code ResourceLeaveWrite}: userId and both dates required. */
    private static final String LEAVE = """
            {"userId":1,"startDate":"2026-09-01","endDate":"2026-09-02"}""";

    /** {@code RoleWrite}: code must match the identifier pattern. */
    private static final String ROLE = """
            {"code":"MATRIX_FIXTURE","name":"Matrix Fixture"}""";

    /** {@code RolePermissionsWrite}: the array must be present; empty is valid. */
    private static final String ROLE_PERMISSIONS = """
            {"permissionCodes":[]}""";

    /**
     * {@code PriorityWrite}: level, name and colour are all required, the colour
     * must be a {@code #RRGGBB} token, and <b>the level must be one of the
     * contract's four</b> — a fifth is refused by {@code PriorityService}. It
     * does not matter that {@code HIGH} already exists: an allowed row is
     * entitled to reach the handler and get its 409, and a denied row never gets
     * that far, which is the only distinction this matrix measures.
     */
    private static final String PRIORITY = """
            {"level":"HIGH","name":"Matrix Fixture","colour":"#F59E0B"}""";

    /**
     * {@code TaskTypeWrite}: code, name, colour and defaultLevel are all
     * required, and the colour must be a {@code #RRGGBB} token.
     */
    private static final String TASK_TYPE = """
            {"code":"MATRIX_FIXTURE","name":"Matrix Fixture","colour":"#4F46E5","defaultLevel":"LOW"}""";

    /**
     * {@code NotificationTemplateDtos.TemplateWrite}: four required fields, and
     * the event and channel both have to be in their enums or the handler
     * answers 400.
     *
     * <p>The pair deliberately names an event that <b>already has an</b>
     * {@code EMAIL} <b>template</b>, so an allowed row earns a 409. Same reason
     * {@code PRIORITY} reuses {@code HIGH}: an allowed row is entitled to reach
     * the handler and be refused on the merits, and a denied row never gets that
     * far, which is the only distinction this matrix measures.
     */
    private static final String NOTIFICATION_TEMPLATE = """
            {"eventCode":"TICKET_ASSIGNED","channel":"EMAIL","recipients":["ASSIGNEE"],\
            "subjectTemplate":"Matrix fixture","bodyTemplate":"<p>Matrix fixture</p>"}""";

    /**
     * {@code ProjectDtos.ProjectWrite}: {@code projectCode} matches
     * {@code ^[A-Za-z][A-Za-z0-9]{1,9}$}, {@code name} is {@code @NotBlank} and
     * {@code projectManagerId} is {@code @NotNull}. The manager id need not
     * exist — resolving it happens in the handler, which an allowed row is
     * entitled to reach and a denied row never gets to.
     */
    private static final String PROJECT = """
            {"projectCode":"MTX","name":"Matrix Fixture","projectManagerId":1}""";

    /** {@code ResourceWriteRequest}: five required fields, role from the §2 set. */
    private static final String RESOURCE = """
            {"displayName":"Matrix Fixture","employeeCode":"MTX001",\
            "email":"matrix.fixture@edunext.test","username":"matrix.fixture","role":"QA"}""";

    /** {@code BulkStatusRequest}: a non-empty selection and an explicit direction. */
    private static final String BULK_STATUS = """
            {"userIds":[1],"isActive":false}""";

    /**
     * {@code TeamMemberWrite}: {@code userId} is the only required field —
     * {@code projectRole} null means "same as their global role" and
     * {@code allocationPct} null means "not stated".
     */
    private static final String PROJECT_MEMBER = """
            {"userId":1,"projectRole":"DEVELOPER","allocationPct":50}""";

    /**
     * {@code List<SlaPolicyWrite>}: a bare array, and every element is
     * validated.
     *
     * <p>One valid override is enough. It has to be <em>valid</em> so that
     * argument resolution passes the request on to the guard —
     * {@code resolutionHrs} is {@code @Positive}, so the obvious empty-ish
     * fixture would 400 before {@code @PreAuthorize} ran and the row would
     * assert nothing at all. {@code SlaPolicyBodyValidationTest} is what proves
     * element validation reaches this far; without it this fixture's validity
     * would be an assumption.
     *
     * <p>Task type 2 is {@code PRODUCTION_BUG} in the migration seed. The
     * request never gets far enough to look it up — these suites run without a
     * database — but a fixture naming a plausible row is one fewer thing to
     * reason about if it ever does.
     */
    private static final String SLA_MATRIX = """
            [{"taskTypeId":2,"level":"HIGH","responseHrs":2,"resolutionHrs":8}]""";

    /**
     * {@code ProjectSettingsWrite}: all three fields are {@code @NotNull},
     * including the two lists.
     *
     * <p>Both arrays are non-empty so the fixture exercises a real request
     * rather than the degenerate one. An empty {@code allowedTaskTypeIds} would
     * also be valid — it is the request that removes the restriction — but a
     * fixture that is valid for the reason "this list may be empty" proves less
     * than one that is valid with something in it.
     */
    private static final String PROJECT_SETTINGS = """
            {"autoAssignRule":"ROUND_ROBIN","mandatoryFields":["MODULE"],\
            "allowedTaskTypeIds":[1,2]}""";

    /**
     * {@code AttachmentSettingsController.LimitsWrite}: all three boxed and
     * {@code @NotNull}, so omitting any of them is a 400 that would pre-empt the
     * guard this file is asserting.
     *
     * <p>§4B.4's own numbers, which are also what the migration seeds — a body
     * that changed the limits would leave whichever role ran last having
     * reconfigured the fixture for every test after it.
     */
    private static final String ATTACHMENT_LIMITS = """
            {"maxFileBytes":10485760,"maxTicketBytes":52428800,"maxFiles":20}""";

    /** {@code StatusRequest}: isActive is boxed so omitting it is a 400. */
    private static final String USER_STATUS = """
            {"isActive":false}""";

    /** {@code ClientDtos.StatusRequest} — the same shape, one feature over. */
    private static final String CLIENT_STATUS = """
            {"isActive":false}""";

    /**
     * {@code ClientDtos.BulkStatusRequest}: {@code clientIds} is {@code @NotEmpty},
     * so the list carries an id. An empty one is a 400 that would arrive before
     * the guard runs, and the row would then assert nothing — the hole this
     * class's header describes.
     */
    private static final String CLIENT_BULK_STATUS = """
            {"clientIds":[1],"isActive":false}""";

    /**
     * The {@code *Patch} DTOs have every field optional — deliberately, so a
     * partial update is possible at all — so an empty object is valid and
     * reaches authorisation.
     */
    private static final String EMPTY_PATCH = "{}";

    /**
     * One row per routed handler.
     *
     * <p>Grouped by controller, in route order, so this file can be read
     * alongside the source rather than searched.
     */
    static final List<Entry> ENTRIES = List.of(

            // ── auth · the four public operations plus logout ────────────────
            // permitAll, so every signed-in role reaches them too. The
            // unauthenticated question is RouteAuthorizationTest's.
            everyRole("POST", "/api/v1/auth/login", LOGIN),
            everyRole("POST", "/api/v1/auth/refresh"),
            everyRole("POST", "/api/v1/auth/forgot-password", FORGOT_PASSWORD),
            everyRole("POST", "/api/v1/auth/reset-password", RESET_PASSWORD),
            // Authenticated, no capability: a role that could not end its own
            // session would be a role that cannot sign out.
            everyRole("POST", "/api/v1/auth/logout"),

            // ── me · every operation is about the caller's own account ───────
            // §2 grants no capability for these and must not: gating a password
            // change on a permission would lock a role out of its own security.
            everyRole("PATCH", "/api/v1/me/password", CHANGE_PASSWORD),
            everyRole("POST", "/api/v1/me/2fa/setup"),
            everyRole("POST", "/api/v1/me/2fa/confirm", TOTP_CODE),
            everyRole("POST", "/api/v1/me/2fa/disable", TOTP_DISABLE),

            // ── notifications · pinned to the caller, so no capability ───────
            // Receiving notifications is not a capability. A role that could not
            // read its own bell would never learn a ticket was assigned to it.
            everyRole("GET", "/api/v1/notifications"),
            everyRole("GET", "/api/v1/notifications/pending"),
            everyRole("PATCH", "/api/v1/notifications/{notificationId}/read"),
            everyRole("PATCH", "/api/v1/notifications/read-all"),
            everyRole("POST", "/api/v1/notifications/delivered", DELIVERED),
            everyRole("GET", "/api/v1/me/notification-preferences"),
            everyRole("PUT", "/api/v1/me/notification-preferences", PREFERENCES),
            everyRole("GET", "/api/v1/push/public-key"),
            everyRole("POST", "/api/v1/me/push-subscriptions", PUSH_SUBSCRIPTION),
            everyRole("DELETE", "/api/v1/me/push-subscriptions"),

            // ── chat · membership is a row question, not a capability ────────
            // Whether the caller is in this thread is decided per-row inside the
            // feature, the way §10.2 decides ticket visibility. A capability here
            // would be the wrong instrument: it would answer the same for every
            // thread.
            everyRole("GET", "/api/v1/chat/threads"),
            everyRole("GET", "/api/v1/chat/threads/{threadId}/messages"),
            everyRole("GET", "/api/v1/chat/messages/search"),
            everyRole("POST", "/api/v1/chat/threads/{threadId}/messages", CHAT_MESSAGE),
            everyRole("PATCH", "/api/v1/chat/threads/{threadId}/messages/{messageId}", CHAT_MESSAGE),
            everyRole("DELETE", "/api/v1/chat/threads/{threadId}/messages/{messageId}"),
            // D-054. Scoped per row inside the feature like the rest of chat, and
            // more strictly than it reads: the codes come from the caller, and
            // every one of them goes through A-034 — a ticket they may not see is
            // absent from the answer, indistinguishable from one that was never
            // issued. A capability here would answer the same for every code,
            // which is the wrong instrument for the same reason as above.
            everyRole("GET", "/api/v1/chat/ticket-cards"),

            // ── status requests · S-25, and the same reasoning ───────────────
            everyRole("POST", "/api/v1/tickets/{ticketId}/ask-status"),
            everyRole("GET", "/api/v1/tickets/{ticketId}/status-requests"),
            everyRole("GET", "/api/v1/me/awaiting-response"),

            // ── attachments · C-025, §4B.4 ──────────────────────────────────
            // Both open to all six, and the argument is §2's rather than
            // convenience. §4B.4 lists the handoff dialog and the quick update
            // panel among the upload surfaces, and "Hand off to next stage" and
            // "Update status / log effort" are ✅ for all six — so a Developer,
            // QA or Deployment resource who could not attach a screenshot could
            // not complete a move §2 explicitly grants them. Upload asserts
            // ticket.update_progress; a seventh role without that grant would be
            // denied here.
            //
            // *Which* tickets is a different question and is not this file's:
            // ScopedTickets applies the caller's row scope inside the service,
            // so a ticket the caller may not see is 404 (A-035) whatever their
            // capability. The upload row carries no body fixture because the
            // handler takes a multipart part rather than a @RequestBody, so
            // argument resolution does not pre-empt the guard.
            everyRole("POST", "/api/v1/tickets/{ticketId}/attachments"),
            everyRole("GET", "/api/v1/tickets/{ticketId}/attachments"),

            // ── ticket detail · every role, because permission is not scope ──
            //
            // A-052. All six reach it, and that is blueprint §2's answer rather
            // than a permissive one: what differs per role is the *rows*, not
            // the right to ask. ScopedTickets answers 404 rather than 403 for a
            // ticket outside the caller's scope, so a permission denial here
            // would confirm the ticket exists — the existence leak A-035
            // removed. Denying the capability would be the wrong tool for that
            // job and would break the roles it is meant to protect.
            everyRole("GET", "/api/v1/tickets/{ticketId}/full"),

            // The list, for the same reason and one more: a Developer who could
            // not call it would have no ticket screen at all. ScopeResolver
            // narrows the rows — Admin unrestricted, PM and Support to their
            // projects, Developer/QA/Deployment to assigned_to = me — and a
            // caller's own ?projectId= is ANDed underneath that, so a filter
            // can only narrow what they already see and never widen it.
            everyRole("GET", "/api/v1/tickets"),

            // ── dashboard · A-054, and the same reasoning one step further ───
            //
            // Every role has a dashboard; denying the capability would leave a
            // Developer with no landing screen at all. Role-awareness here is
            // not "who may ask" but *which summary table answers*: Admin and PM
            // read daily_ticket_stats, Developer/QA/Deployment read
            // resource_daily_stats keyed by their own user id, because a
            // project-keyed table cannot express "assigned to me" however it is
            // filtered. DashboardService holds that decision.
            everyRole("GET", "/api/v1/dashboard/summary"),

            // ── attachment limits · C-027, §4B.4 ────────────────────────────
            // Read open to all six, write to master.write.
            //
            // The GET returns the three caps the upload form has always printed
            // on screen ("10 MB per file · 20 per ticket"), and every role has an
            // upload surface — a role that could not read them would be left
            // validating against a guess, which is the one outcome §4B.4's
            // published limits exist to prevent. Nothing about the org, its
            // projects or its tickets is reachable through it.
            //
            // The write is Admin's alone, because master.write is granted to
            // Admin alone (B-001's §2 grant matrix). The org-wide shape is why
            // it is master.write rather than project.manage: there is nothing
            // here scoped to a project for a project grant to bound.
            everyRole("GET", "/api/v1/attachments/limits"),
            adminOnly("PUT", "/api/v1/attachments/limits", ATTACHMENT_LIMITS),

            // ── planned close date · every role holds ticket.create ──────────
            // Allowed for all six because all six may raise a ticket (§2), not
            // because the route is unguarded — it asserts ticket.create, and a
            // seventh role without that grant would be denied here.
            everyRole("GET", "/api/v1/tickets/planned-close-date"),

            // ── working calendar · reads open, writes Admin ──────────────────
            // Every role reads it: the calendar is what every SLA and duration
            // figure in the product is computed against, and a Developer whose
            // due date landed on a holiday needs to see why.
            everyRole("GET", "/api/v1/masters/holidays"),
            everyRole("GET", "/api/v1/masters/working-calendar"),
            everyRole("GET", "/api/v1/masters/leaves"),
            // Writes are master.write, which only Admin holds. Deleting one org
            // holiday moves every SLA figure computed afterwards, retroactively
            // and silently — this is the row that mattered most before A-033,
            // when a Developer could do it.
            adminOnly("POST", "/api/v1/masters/holidays", HOLIDAY),
            adminOnly("PATCH", "/api/v1/masters/holidays/{holidayId}", EMPTY_PATCH),
            adminOnly("DELETE", "/api/v1/masters/holidays/{holidayId}"),
            adminOnly("PUT", "/api/v1/masters/working-calendar", WORKING_WEEK),
            adminOnly("POST", "/api/v1/masters/leaves", LEAVE),
            // NOTE the open question A-033 raised and did not decide: this route
            // is "edit *or approve* a leave record", and approval reads like a
            // reporting-manager action. §2 has no leave row and there is no
            // leave.approve code, so it is master.write and leave approval is
            // Admin-only. This row records that as the current answer, not as
            // the settled one; changing it is a §2 row, a permission code and a
            // migration, not a widened check.
            adminOnly("PATCH", "/api/v1/masters/leaves/{leaveId}", EMPTY_PATCH),
            adminOnly("DELETE", "/api/v1/masters/leaves/{leaveId}"),

            // ── roles and permissions · S-09 ─────────────────────────────────
            // The catalogue is readable by everyone: the resource form renders a
            // role dropdown, and a PM creating a ticket sees owner roles on the
            // ribbon. Reading which roles exist leaks nothing.
            everyRole("GET", "/api/v1/masters/permissions"),
            everyRole("GET", "/api/v1/masters/roles"),
            everyRole("GET", "/api/v1/masters/roles/{roleId}"),
            // Editing them is resource.manage. This is the route that can grant
            // a capability to a role, so anybody who can reach it can grant
            // themselves anything — the one route where a wrong row is total.
            adminOnly("POST", "/api/v1/masters/roles", ROLE),
            adminOnly("PATCH", "/api/v1/masters/roles/{roleId}", EMPTY_PATCH),
            adminOnly("DELETE", "/api/v1/masters/roles/{roleId}"),
            adminOnly("PUT", "/api/v1/masters/roles/{roleId}/permissions", ROLE_PERMISSIONS),

            // ── priorities · S-12 (B-021) ───────────────────────────────────
            // Both reads open to all six, on §2's argument rather than
            // convenience: every role may raise a ticket (§2 row 3), a ticket
            // must carry a level, and the create form's LevelPicker is this
            // route. A role that could not list levels could not raise a ticket
            // at all. The detail read carries nothing the list does not; it
            // exists to emit the ETag the PATCH requires.
            everyRole("GET", "/api/v1/masters/priorities"),
            everyRole("GET", "/api/v1/masters/priorities/{priorityId}"),
            // The writes are master.write — Admin alone — and unlike the task
            // type master one row over, §2 does not name priorities in its
            // parenthetical ("task types, SLA, workflow, holidays"). The list
            // is illustrative: S-11 and S-12 are consecutive screens in the same
            // Masters section of §7, a level's defaultSlaHrs is rung 4 of the
            // same §6 ladder that row's "SLA" refers to, and the alternative
            // B-018 weighed and discarded — project.manage — is about a project
            // rather than the organisation's vocabulary.
            //
            // There is no DELETE row because there is no DELETE route, and here
            // the absence matters more than it does for task types: nothing has
            // a foreign key to `priorities`, so a delete would *succeed* and
            // leave every historical ticket rendering a level nothing resolves.
            // Retiring is the PATCH.
            adminOnly("POST", "/api/v1/masters/priorities", PRIORITY),
            adminOnly("PATCH", "/api/v1/masters/priorities/{priorityId}", EMPTY_PATCH),

            // ── task types · S-11 (B-020) ────────────────────────────────────
            // Both reads open to all six, and the argument is §2's rather than
            // convenience: every role may raise a ticket (§2 row 3), a ticket
            // must name a task type, and the create form's type picker is this
            // route. A role that could not list task types could not raise a
            // ticket at all — which would contradict a row §2 does state. The
            // detail read carries nothing the list does not; it exists to emit
            // the ETag the PATCH requires.
            everyRole("GET", "/api/v1/masters/task-types"),
            everyRole("GET", "/api/v1/masters/task-types/{taskTypeId}"),
            // The writes are master.write — Admin alone — and unlike B-018's SLA
            // tab this one needs no argument at all. §2's row reads "Master data
            // (task types, SLA, workflow, holidays)" and task types are the
            // first two words of it; B-001's own description of the capability
            // opens the same way.
            //
            // There is no DELETE row because there is no DELETE route, and its
            // absence is the design: three foreign keys point at `task_types`
            // without cascades, and B-019's migration named this screen as the
            // reason they can stay that way. Retiring a type is the PATCH.
            adminOnly("POST", "/api/v1/masters/task-types", TASK_TYPE),
            adminOnly("PATCH", "/api/v1/masters/task-types/{taskTypeId}", EMPTY_PATCH),
            // ── notification templates · S-15 (B-022) ────────────────────────
            // **Admin on the reads too, and this is the only master where that
            // is true.** Task types, levels, roles and the calendar open their
            // reads to all six on an argument from §2 row 3: every role may
            // raise a ticket, a ticket must carry a level and a type, so a role
            // that could not read those masters could not raise a ticket at
            // all. Nothing on a screen a non-Admin sees is built from this one.
            //
            // And the content is not neutral. The seeded rows include the mail
            // that goes to a **client contact**, the escalation naming the
            // Reporting Manager, and A-044's chain-verification alarm. §2 gives
            // the audit log to Admin alone on that reasoning; a catalogue of who
            // gets told what, when something goes wrong, belongs on the same
            // side of the line. Reasoned rather than read off §2, the way B-018
            // and B-021 flagged theirs.
            adminOnly("GET", "/api/v1/masters/notification-templates"),
            adminOnly("GET", "/api/v1/masters/notification-templates/vocabulary"),
            adminOnly("GET", "/api/v1/masters/notification-templates/{templateId}"),
            adminOnly("POST", "/api/v1/masters/notification-templates", NOTIFICATION_TEMPLATE),
            adminOnly("PATCH", "/api/v1/masters/notification-templates/{templateId}",
                    EMPTY_PATCH),
            // There is no DELETE row because there is no DELETE route. Deleting
            // a template does not orphan a reference the way deleting a level
            // would — it removes the *wording* for an event that goes on firing,
            // and the failure shows up as a mail that never arrives.

            // ── projects · S-10 ─────────────────────────────────────────────
            // Reads open to all six. §2 has no "view projects" row, so this is
            // reasoned rather than read off: every role may create a ticket
            // (§2 row 3), a ticket must name a project, and the create form's
            // project picker is this route. A role that could not list projects
            // could not raise a ticket at all — which would contradict a row
            // §2 does state. The detail read carries the manager, dates, SLA
            // policy and colour tag; none of it is a credential and all of it
            // is already on any ticket belonging to the project.
            everyRole("GET", "/api/v1/projects"),
            everyRole("GET", "/api/v1/projects/{projectId}"),
            // Writes are project.manage — Admin and PM, per §2 row 2. This is
            // the only capability in the product that PM holds and Support does
            // not, which makes Support the row that matters here: Support runs
            // the desk for its own projects and reads like a privileged role,
            // and §2 says plainly it may not create or edit one.
            //
            // ⚠ NOTE the qualifier §2 puts on PM that this matrix cannot state:
            // "✅ (own)". A row here is a role-scope claim — did authorisation
            // let the request through — and "own" is row scope. For tickets that
            // is ScopeResolver's job; for projects it is nobody's yet. The
            // handler takes no Authentication and ProjectService.update sees
            // only (projectId, patch), so **a PM today may edit any project,
            // not only the ones they manage** — wider than §2 specifies, and
            // not visible from an Admin smoke test because Admin is entitled to
            // all of them anyway.
            //
            // Recorded as the current answer rather than the settled one, like
            // the leave-approval row above. Closing it means scoping the write
            // by projects.manager_id, which is a Stream A decision about where
            // row scope lives and not a narrowed annotation on a masters
            // branch. Found by Ayush on the parallel B-016 fix; carried here so
            // it does not die with the branch that is being closed.
            adminAndPm("POST", "/api/v1/projects", PROJECT),
            adminAndPm("PATCH", "/api/v1/projects/{projectId}", EMPTY_PATCH),

            // ── the project team · S-10's Team tab (B-017) ───────────────────
            // The three writes are the second half of the very row above —
            // "Create/edit projects, **map resources to project**" — so they
            // take the same two roles from the same sentence, and the "(own)"
            // note above applies here unchanged: a PM may today edit the team of
            // any project, not only their own.
            //
            // The roster read is every role, and the argument is §2's rather
            // than convenience: "Hand off to next stage" is ✅ for all six, and
            // §4A's handoff modal fills its "assign to" from *members of the
            // receiving role on that project*. A Developer who cannot read this
            // cannot hand off — a move §2 explicitly grants them. The create
            // form's assignee picker is filtered the same way, and "Create
            // ticket" is likewise ✅ for all six.
            everyRole("GET", "/api/v1/projects/{projectId}/members"),
            adminAndPm("POST", "/api/v1/projects/{projectId}/members", PROJECT_MEMBER),
            adminAndPm("PATCH", "/api/v1/projects/{projectId}/members/{userId}", EMPTY_PATCH),
            adminAndPm("DELETE", "/api/v1/projects/{projectId}/members/{userId}"),

            // ── the SLA matrix · S-10's SLA tab (B-018) ──────────────────────
            // ⚠ The write is Admin ALONE, and it sits one tab away from three
            // rows that are Admin and PM. That is not an inconsistency to be
            // tidied up, and it is the reason this comment is long.
            //
            // §2 has two separate rows here. Row 2 — "Create/edit projects, map
            // resources to project" — is ✅ for PM, and that is the General and
            // Team tabs above. Row 5 — "Master data (task types, **SLA**,
            // workflow, holidays)" — is ✅ for Admin and ❌ for all five others,
            // and that is this one. B-001's own description of master.write
            // names SLA in it, and B-023 already annotates the working calendar,
            // the other master under this feature, exactly this way.
            //
            // The distinction is real: staffing your own project is project
            // management; setting the response target a client is contractually
            // held to, and deciding whose manager's manager gets woken on a
            // breach, is master data. The obvious "consistency" fix is to widen
            // this row to Admin and PM, and it would be widening the wrong one.
            //
            // The read is every role, and this one is not a convenience either.
            // This grid is what gives every ticket its planned close date; the
            // same figures already reach all six roles one at a time through
            // C-012's planned-close-date preview on the create form, and a
            // Developer who cannot see the matrix cannot find out why their
            // ticket is due Thursday.
            everyRole("GET", "/api/v1/projects/{projectId}/sla-policies"),
            adminOnly("PUT", "/api/v1/projects/{projectId}/sla-policies", SLA_MATRIX),

            // ── the Settings tab · S-10's fourth tab (B-019) ─────────────────
            // ⚠ The write is Admin AND PM — the opposite call from the two rows
            // directly above, on the tab directly beside it. Read the SLA
            // comment first; this one only makes sense against it.
            //
            // §2's row 2, "Create/edit projects, map resources to project", is
            // ✅ for PM and covers the General, Team and Settings tabs. Row 5,
            // "Master data (task types, SLA, workflow, holidays)", is Admin's
            // alone and covers the SLA tab. Choosing which task types a project
            // accepts and which fields its create form requires is configuring
            // one project; setting the response target a client is
            // contractually held to is master data.
            //
            // The decisive half is that narrowing this to Admin would take a
            // capability away from PMs rather than withhold a new one:
            // `auto_assign_rule` is one of the three settings here and has been
            // PM-writable through PATCH /projects/{projectId} since B-016. A
            // read-only Settings tab for the role that can already change one
            // of its three fields on another screen would be a regression
            // wearing a consistency argument.
            //
            // The read is every role, and not as a convenience: all six can
            // raise a ticket, and the create form cannot mark a field mandatory
            // or filter its task-type picker without this.
            everyRole("GET", "/api/v1/projects/{projectId}/settings"),
            adminAndPm("PUT", "/api/v1/projects/{projectId}/settings", PROJECT_SETTINGS),

            // ── resources · S-07 and S-08 ───────────────────────────────────
            // The directory is the assignee picker and the @mention source, so
            // it must answer for all six roles. Over-restricting a read is the
            // direction a permission model gets wrong quietly — nobody notices
            // until a Developer opens a screen and the dropdown is empty.
            everyRole("GET", "/api/v1/users"),
            everyRole("GET", "/api/v1/users/export"),
            everyRole("GET", "/api/v1/users/{userId}"),
            // Creating, editing and deactivating a resource is resource.manage,
            // Admin's alone. Support holds ten permissions and none of them is
            // this one, which is why the matrix asserts Support separately from
            // Developer: the refusal must come from the absence of
            // resource.manage, not from the caller being broadly unprivileged.
            adminOnly("POST", "/api/v1/users", RESOURCE),
            adminOnly("PATCH", "/api/v1/users/{userId}", RESOURCE),
            adminOnly("PATCH", "/api/v1/users/{userId}/status", USER_STATUS),
            adminOnly("POST", "/api/v1/users/bulk-status", BULK_STATUS),

            // ── clients · S-32 (B-025) ──────────────────────────────────────
            // Both reads open to all six, and the argument is §2's rather than
            // convenience: blueprint §4B.2 puts a client dropdown on the ticket
            // create form, every role may raise a ticket (§2 row 3), and this
            // route is that dropdown. The contacts read is the dependent second
            // dropdown beside it — the individual who reported the issue — so it
            // carries exactly the same reasoning and no more information than a
            // ticket raised against the client already shows.
            everyRole("GET", "/api/v1/clients"),
            everyRole("GET", "/api/v1/clients/{clientId}/contacts"),
            // The writes are master.write — Admin alone. §2 row 51 reads "Master
            // data (task types, SLA, workflow, holidays)" ✅ Admin / ❌ the other
            // five, and §7.4 heads the module "Master data module (Admin only)"
            // with the Client Master inside it. Unlike B-018's SLA tab there is
            // nothing to argue against project.manage here: an SLA policy hangs
            // off a project and a client does not, so PM is denied like the rest.
            //
            // There is no DELETE row because there is no DELETE route, and its
            // absence is the design: tickets, contacts and project mappings all
            // point at `clients`, and blueprint §4B.2 says deactivating must
            // never hide historical tickets. Going away is the status PATCH.
            adminOnly("PATCH", "/api/v1/clients/bulk-status", CLIENT_BULK_STATUS),
            adminOnly("PATCH", "/api/v1/clients/{clientId}/status", CLIENT_STATUS),

            // ── mail webhooks · signature-authenticated, not user-authenticated ──
            // permitAll because the sender is a mail provider with no EduTrack
            // account; the actual gate is the X-Webhook-Signature HMAC inside
            // the handler. Every role therefore reaches them too, which is
            // harmless and is not the property that protects these routes.
            everyRole("POST", "/api/v1/webhooks/email/bounce", RAW_BYTES),
            everyRole("POST", "/api/v1/webhooks/email/inbound", RAW_BYTES));

    /**
     * One route and what each role may do with it.
     *
     * @param method      the HTTP method, as {@code RequestMappingInfo} reports it
     * @param pattern     the mapping pattern, {@code {placeholders}} included, so
     *                    this joins to {@link RouteInventory#describe} without
     *                    either side normalising
     * @param body        a request body that satisfies the DTO's constraints, or
     *                    {@code null} where the route has none to satisfy
     * @param allowedRoles the role codes that may reach it; the rest are denied
     */
    record Entry(String method, String pattern, String body, Set<String> allowedRoles) {

        /** The join key: {@code DELETE /api/v1/masters/holidays/{holidayId}}. */
        String key() {
            return method + " " + pattern;
        }

        /**
         * The pattern as a requestable path. Every path variable in this
         * application is a numeric id, so one substitution serves all of them —
         * and the request only has to reach authorisation, not find a row.
         */
        String requestPath() {
            return pattern.replaceAll("\\{[^}]+}", "1");
        }

        Outcome expectedFor(String roleCode) {
            return allowedRoles.contains(roleCode) ? Outcome.ALLOW : Outcome.DENY;
        }

        /** What JUnit shows in the parameterised test's name. */
        @Override
        public String toString() {
            return key();
        }
    }

    private static Entry everyRole(String method, String pattern) {
        return new Entry(method, pattern, null, EVERY_ROLE);
    }

    private static Entry everyRole(String method, String pattern, String body) {
        return new Entry(method, pattern, body, EVERY_ROLE);
    }

    private static Entry adminOnly(String method, String pattern) {
        return new Entry(method, pattern, null, ADMIN_ONLY);
    }

    /** B-017 · the roster DELETE is the only Admin-and-PM route with no body. */
    private static Entry adminAndPm(String method, String pattern) {
        return new Entry(method, pattern, null, ADMIN_AND_PM);
    }


    private static Entry adminAndPm(String method, String pattern, String body) {
        return new Entry(method, pattern, body, ADMIN_AND_PM);
    }

    private static Entry adminOnly(String method, String pattern, String body) {
        return new Entry(method, pattern, body, ADMIN_ONLY);
    }

    private PermissionMatrix() {
    }
}
