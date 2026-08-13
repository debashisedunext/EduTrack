package com.edunext.edutrack.api.security.permission;

import java.util.List;
import java.util.Set;

import static com.edunext.edutrack.api.security.permission.RolePermissions.ADMIN;

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

    /** {@code ResourceWriteRequest}: five required fields, role from the §2 set. */
    private static final String RESOURCE = """
            {"displayName":"Matrix Fixture","employeeCode":"MTX001",\
            "email":"matrix.fixture@edunext.test","username":"matrix.fixture","role":"QA"}""";

    /** {@code BulkStatusRequest}: a non-empty selection and an explicit direction. */
    private static final String BULK_STATUS = """
            {"userIds":[1],"isActive":false}""";

    /** {@code StatusRequest}: isActive is boxed so omitting it is a 400. */
    private static final String USER_STATUS = """
            {"isActive":false}""";

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

            // ── status requests · S-25, and the same reasoning ───────────────
            everyRole("POST", "/api/v1/tickets/{ticketId}/ask-status"),
            everyRole("GET", "/api/v1/tickets/{ticketId}/status-requests"),
            everyRole("GET", "/api/v1/me/awaiting-response"),

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

    private static Entry adminOnly(String method, String pattern, String body) {
        return new Entry(method, pattern, body, ADMIN_ONLY);
    }

    private PermissionMatrix() {
    }
}
