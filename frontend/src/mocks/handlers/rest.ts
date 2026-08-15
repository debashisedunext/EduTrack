import { http, HttpResponse } from 'msw';
import { getDb, nextId } from '../db';
import type { Db, Holiday, Level, ProjectRoleCode, Role, User } from '../db';
import { resolveSla, workingMinutesBetween } from './sla';
import { round, statusRequestDto } from './tickets';
import {
  clientRef, currentUser, noContent, notFound, ok, paginate, problem, projectRef,
  scopedTickets, ticketDto, url, userRef, validationFailed,
} from './util';

/** Everything outside tickets and the ribbon: auth, masters, clients, the rest. */

const me = () => {
  const db = getDb();
  const u = currentUser(db);
  return {
    id: u.id, displayName: u.displayName, avatarUrl: u.avatarUrl, role: u.role,
    username: u.username, email: u.email,
    permissions: permissionsOf(u.role),
    projectIds: u.projectIds,
    reporteeIds: db.users.filter((x) => x.reportingManagerId === u.id).map((x) => x.id),
    timezone: u.timezone,
  };
};

/**
 * D-042. The server builds this from `NotificationEvent`; the mock keeps a
 * short representative slice rather than all 25 — enough to cover a locked
 * category, an unlocked one, and each tab — because a full copy of the enum
 * here would be a second vocabulary to keep in step, which is the drift the
 * server's "send the whole catalogue" rule exists to avoid.
 */
const NOTIFICATION_EVENTS = [
  { eventKey: 'TICKET_ASSIGNED', category: 'ASSIGNMENT' },
  { eventKey: 'HANDOFF_RECEIVED', category: 'ASSIGNMENT' },
  { eventKey: 'SLA_BREACHED', category: 'ESCALATION' },
  { eventKey: 'MENTIONED', category: 'MENTION' },
  { eventKey: 'STATUS_REQUESTED', category: 'STATUS_REQUEST' },
  { eventKey: 'COMMENT_ADDED', category: 'OTHER' },
  { eventKey: 'DAILY_DIGEST', category: 'OTHER' },
] as const;

/** D-036 — the same rule as `NotificationEvent.isMandatoryMail()`. */
const isMandatoryMail = (category: string) =>
  category === 'ASSIGNMENT' || category === 'ESCALATION';

function preferenceMatrix(db: ReturnType<typeof getDb>) {
  const stored = db.notificationPreferences ?? [];
  return NOTIFICATION_EVENTS.map((event) => {
    const row = stored.find(
      (p) => p.userId === db.currentUserId && p.eventKey === event.eventKey,
    );
    const locked = isMandatoryMail(event.category);
    return {
      eventKey: event.eventKey,
      category: event.category,
      inApp: row?.inApp ?? true,
      email: locked || (row?.email ?? true),
      emailLocked: locked,
      // D-045. Never locked: §7.7 gives the guarantee to mail, and push depends
      // on a permission the user can revoke in their browser without telling us.
      push: row?.push ?? true,
    };
  });
}

/**
 * The caller's own grants, read from the same matrix S-09 edits.
 *
 * **This used to be a hardcoded map of a different vocabulary** —
 * `ticket.read`, `ticket.write`, `report.read`, `audit.read` — none of which
 * are among the eighteen codes B-001 seeds or the ones A-033's
 * `@PreAuthorize` expressions name. Nothing read them, so nothing broke; but
 * once S-09 renders the real matrix, `/me` claiming a role holds
 * `ticket.write` while the Role Master shows `ticket.update_progress` is two
 * answers to one question in the same session. B-015 made it one.
 *
 * A role S-09 has never touched still answers here — `roleGrants` is seeded
 * from the §2 matrix for all six.
 */
const permissionsOf = (roleCode: string): string[] => {
  const db = getDb();
  const role = db.roles.find((r) => r.code === roleCode);
  return role ? [...(db.roleGrants[role.id] ?? [])].sort() : [];
};

const LANDING: Record<string, string> = {
  ADMIN: '/dashboard', PM: '/dashboard', DEVELOPER: '/my-tasks',
  SUPPORT: '/tickets', QA: '/stages/queue', DEPLOYMENT: '/stages/queue',
};

// ── working calendar · S-14 (B-023) ─────────────────────────────────────────
/**
 * State lives in `db.ts` so `resetDb()` clears it between tests. A module-level
 * `let` here would survive the reset, and one test's saved working week would
 * silently become the next test's starting point.
 */
const calendarState = () => getDb().calendar;

const nextCalendarId = (key: string) => nextId(getDb(), key);

/** `09:30` → `09:30:00`, the shape a Java LocalTime serialises to. */
const withSeconds = (time: string) => (time.length === 5 ? `${time}:00` : time);

/** The three fields the SLA read model carries alongside the holiday list. */
const workingWeek = () => {
  const { weeklyOff, workDayStart, workDayEnd } = calendarState().week;
  return { weeklyOff, workDayStart, workDayEnd };
};

/** The settings resource, which also carries the zone those bounds are read in. */
const workingWeekFull = () => ({ ...calendarState().week });

// ── roles & permissions · S-09 (B-015) ──────────────────────────────────────

/**
 * Sorted by code, so a save and a reload compare byte for byte.
 *
 * The real service sorts by `(category, code)` — a different order, and
 * deliberately not mirrored: the screen builds a `Set` from this, so nothing
 * depends on the order, and a second copy of the catalogue's categories here
 * would be a second thing to keep in step.
 */
const grantsOf = (roleId: number) => [...(getDb().roleGrants[roleId] ?? [])].sort();

const roleDto = (role: Role) => ({
  ...role,
  userCount: getDb().users.filter((u) => u.role === role.code).length,
  permissionCount: grantsOf(role.id).length,
});

const roleDetailDto = (role: Role) => ({
  ...roleDto(role),
  permissionCodes: grantsOf(role.id),
});

/**
 * Content-derived, and it covers `permissionCodes` — so a colleague's matrix
 * save invalidates a rename in progress, which is correct: they are two edits
 * to the same screen.
 */
const roleEtag = (role: Role) =>
  `"${Math.abs([...JSON.stringify(roleDetailDto(role))]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * The mock enforces `If-Match` too. A guard the real backend has and the mock
 * waves through is a guard the frontend never gets to exercise.
 */
function rolePrecondition(role: Role, ifMatch: string | null) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the role first and send back its ETag.');
  }
  if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== roleEtag(role).replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'This role changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

/**
 * Content-derived, like the real controller's. A timestamp would change on a
 * save that rewrote identical values and fail an `If-Match` for an edit that
 * conflicts with nothing.
 */
const calendarEtag = () =>
  `"${Math.abs([...JSON.stringify(calendarState().week)]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

// ── the SLA matrix · S-10's SLA tab (B-018) ─────────────────────────────────

/** One element of the `PUT` body: a project-level override, never a resolved cell. */
interface SlaOverride {
  taskTypeId: number;
  level: Level;
  responseHrs: number | null;
  resolutionHrs: number;
  escalateToL1?: boolean;
  escalateToL2?: boolean;
}

/**
 * The resolved grid — every task type × every level, with the rung that
 * answered.
 *
 * The escalation flags are **flags and not recipients**, matching the columns:
 * §6 fixes who each level means (L1 the reporting manager, L2 that manager's
 * manager), so the matrix only says whether the level applies. The contract
 * carried `l1EscalationUserId` / `l2EscalationUserId` until B-018 and this mock
 * answered a hardcoded `2` and `1` for every cell, which nothing read and
 * nothing could have used.
 *
 * `db.slaPolicies` has no per-row flags, so the seeded ones read as A-007's
 * column defaults — L1 on, L2 off, except Critical, which is the level §6
 * escalates to. Overrides written through the `PUT` keep what was sent.
 */
function slaMatrix(projectId: number, db: Db) {
  const levels: Level[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

  return db.taskTypes.flatMap((tt) =>
    levels.map((level) => {
      const sla = resolveSla(projectId, tt.id, level, db);
      return {
        taskTypeId: tt.id,
        taskTypeName: tt.name,
        level,
        responseHrs: sla.responseHrs,
        resolutionHrs: sla.resolutionHrs,
        escalateToL1: true,
        escalateToL2: level === 'CRITICAL',
        source: sla.source,
        isOverride: sla.source === 'PROJECT_TASK_TYPE',
      };
    }),
  );
}

/**
 * Content-derived over the **resolved** grid, like the real controller's.
 *
 * Over the resolved grid and not this project's own rows, which is the part
 * worth stating: a change to the org-wide default moves this project's tag even
 * though nothing on the project changed. That is correct — the administrator
 * was shown inherited figures and is deciding which to override, so if those
 * moved underneath them the decision was made against numbers that are no
 * longer true.
 */
const slaMatrixEtag = (grid: ReturnType<typeof slaMatrix>) =>
  `"${Math.abs([...JSON.stringify(grid)]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * B-019 · the contract's `TicketFieldCode` — exactly the optional fields of
 * `TicketCreateRequest`.
 */
const TICKET_FIELD_CODES: string[] = [
  'DESCRIPTION', 'MODULE', 'SCREEN_NAME', 'FEATURE', 'STEPS_TO_GENERATE',
  'CLIENT', 'CLIENT_CONTACT', 'ASSIGNEE', 'ESTIMATED_HRS', 'PLANNED_CLOSE_DATE',
];

/**
 * B-019 · one project's Settings tab, resolved.
 *
 * **`restricts` is derived from whether any row exists, and everything else
 * follows from it.** An unrestricted project answers `isAllowed: true` for
 * every active type, because that is what unrestricted means — not because the
 * rows are there.
 *
 * The list is every active task type **plus any inactive one this project still
 * allows**. The second half is the case a read filtering on `isActive` gets
 * wrong: the `PUT` is assembled from these rows, so an allowed-but-unrendered
 * type would be dropped by the next save through a screen that never showed it.
 *
 * `code` is omitted, like `slaMatrix`'s `taskTypeCode` — `db.taskTypes` has no
 * code column, and the contract makes the field optional. Inventing one by
 * upper-casing the name would put a value on the wire that matches nothing in
 * the real master.
 */
function projectSettings(project: import('../db').Project, db: Db) {
  const allowedIds = new Set(
    db.projectTaskTypes.filter((r) => r.projectId === project.id).map((r) => r.taskTypeId),
  );
  const restricts = allowedIds.size > 0;

  return {
    projectId: project.id,
    autoAssignRule: project.autoAssignRule,
    mandatoryFields: project.mandatoryFields ?? [],
    restrictsTaskTypes: restricts,
    taskTypes: db.taskTypes
      .filter((t) => t.isActive || allowedIds.has(t.id))
      .map((t) => ({
        taskTypeId: t.id,
        name: t.name,
        isAllowed: !restricts || allowedIds.has(t.id),
        isActive: t.isActive,
      })),
  };
}

/** Content-derived over the resolved document, like the real controller's. */
const projectSettingsEtag = (settings: ReturnType<typeof projectSettings>) =>
  `"${Math.abs([...JSON.stringify(settings)]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * The mock enforces `If-Match` too. A guard the real backend has and the mock
 * waves through is a guard the frontend never gets to exercise — and this is
 * the one write in the feature where losing the race erases somebody's whole
 * matrix rather than one field of it.
 */
function slaPrecondition(projectId: number, ifMatch: string | null, db: Db) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the SLA matrix first and send back its ETag.');
  }
  const current = slaMatrixEtag(slaMatrix(projectId, db));
  if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== current.replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'This matrix changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

// ── two-factor · S-04 (A-029) ───────────────────────────────────────────────
/** Enrolment for whoever is signed in, or undefined if they never started. */
const twoFactorState = () => getDb().twoFactor[currentUser().id];

/**
 * Base32, and a real one — the alphabet excludes 0, 1, 8 and 9.
 *
 * Fixed rather than random so the QR code in a Storybook story or a visual
 * regression test is the same image every run. It is a mock secret for a mock
 * server; there is nothing here to protect.
 */
const MOCK_TOTP_SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';

/** The one code the mock accepts. Anything else exercises the error state. */
const MOCK_TOTP_CODE = '123456';

/**
 * Ten codes, in the server's alphabet — no I, L, O or U, so nothing reads as a
 * 1 or a 0 when a user copies one off a screen under pressure.
 */
const MOCK_RECOVERY_CODES = [
  '4KDP-9TXM', '7WQR-2HJN', 'B3VZ-6MCT', 'X8ND-5RFK', 'Q2HT-7WPB',
  'M9CX-3KDV', 'Z5RT-8NQJ', 'H6PW-4BXM', 'T7KN-9VDR', 'C4MZ-6HTP',
];

/**
 * The `otpauth://` URI an authenticator scans.
 *
 * The label is `issuer:account` and the issuer is repeated as a parameter —
 * both, because older apps read one and newer ones the other, and an entry
 * labelled only with an email address is unidentifiable once a user has four.
 */
const otpauthUri = (secret: string) =>
  `otpauth://totp/EduTrack:${encodeURIComponent(currentUser().email)}`
  + `?secret=${secret}&issuer=EduTrack&algorithm=SHA1&digits=6&period=30`;

export const restHandlers = [
  // ── auth ──────────────────────────────────────────────────────────────────
  http.post(url('/auth/login'), async ({ request }) => {
    const db = getDb();
    const { username, password } = (await request.json()) as { username: string; password: string };
    const user = db.users.find((u) => u.username === username && u.isActive);
    // Wrong username, wrong password and unknown user all return the same
    // problem. Saying which field was wrong is a username-enumeration oracle.
    if (!user || !password) {
      return problem(401, 'invalid-credentials', 'Username or password is incorrect');
    }
    db.currentUserId = user.id;
    return ok({
      accessToken: `mock.${user.username}.token`,
      expiresIn: 900,
      mustChangePassword: false,
      landingRoute: LANDING[user.role],
      user: me(),
    });
  }),
  http.post(url('/auth/refresh'), () =>
    ok({
      accessToken: `mock.${currentUser().username}.refreshed`,
      expiresIn: 900,
      mustChangePassword: false,
      landingRoute: LANDING[currentUser().role],
      user: me(),
    }),
  ),
  http.post(url('/auth/logout'), () => noContent()),
  // Always 202, known address or not — a different answer for unknown addresses
  // is a user-enumeration oracle.
  http.post(url('/auth/forgot-password'), () => new HttpResponse(null, { status: 202 })),
  http.post(url('/auth/reset-password'), () => noContent()),
  http.get(url('/me'), () => ok(me())),
  http.patch(url('/me/password'), () => noContent()),

  // ── two-factor · S-04 (A-029) ─────────────────────────────────────────────
  // Enrol, then confirm. Setup alone enables nothing, so a user who scans the
  // QR and closes the tab is not locked out of an account with a second factor
  // they never finished adding.
  http.post(url('/me/2fa/setup'), () => {
    const state = twoFactorState();
    if (state?.enabled) {
      return problem(409, 'already-enrolled', 'Two-factor authentication is already enabled');
    }
    // Re-running setup issues a *new* secret and discards the old one, matching
    // the server. Keeping the first would mean a half-finished enrolment could
    // still be completed from a QR code shown on some other machine.
    const secret = MOCK_TOTP_SECRET;
    getDb().twoFactor[currentUser().id] = { secret, enabled: false };
    return ok({ secret, otpauthUri: otpauthUri(secret) });
  }),
  http.post(url('/me/2fa/confirm'), async ({ request }) => {
    const { code } = (await request.json()) as { code?: string };
    const state = twoFactorState();
    if (!state) {
      return problem(409, 'not-enrolling', 'Start enrolment before confirming it');
    }
    if (state.enabled) {
      return problem(409, 'already-enrolled', 'Two-factor authentication is already enabled');
    }
    // The mock cannot run the clock, so it accepts one fixed code rather than
    // any six digits. Accepting anything would let a screen ship that never
    // renders the "that code is wrong" state — the one users actually hit.
    if (code !== MOCK_TOTP_CODE) {
      return problem(400, 'invalid-code', 'That code is not valid. Check your authenticator.');
    }
    state.enabled = true;
    return ok({ recoveryCodes: MOCK_RECOVERY_CODES });
  }),
  http.post(url('/me/2fa/disable'), async ({ request }) => {
    const { password } = (await request.json()) as { password?: string };
    // The password is the point of this endpoint, not a formality — a stolen
    // access token must not be enough to strip the second factor. Mocking it as
    // optional would let the screen ship without the re-authentication step.
    if (!password) {
      return problem(401, 'invalid-credentials', 'Password is incorrect');
    }
    // Idempotent: turning off something already off is not an error, so a
    // double-submit does not surface as one.
    delete getDb().twoFactor[currentUser().id];
    return noContent();
  }),

  // ── users ─────────────────────────────────────────────────────────────────
  http.get(url('/users'), ({ request }) => {
    const requestUrl = new URL(request.url);
    const { page, meta } = paginate(filteredUsers(requestUrl.searchParams), requestUrl);
    return ok(page.map(userDto), meta);
  }),
  /**
   * B-010 · the whole filtered set as a file, ignoring cursor and limit.
   *
   * Its own route rather than `?export=` on the list: one operation declaring
   * both JSON and a binary body generates `Blob | UserListResponse`, and every
   * caller of `useListUsers` then has to narrow a union it has no interest in.
   */
  http.get(url('/users/export'), ({ request }) => {
    const q = new URL(request.url).searchParams;
    const format = q.get('format');
    if (format !== 'xlsx' && format !== 'csv') {
      return validationFailed({ format: ["must be 'xlsx' or 'csv'"] });
    }
    return usersExport(filteredUsers(q), format);
  }),
  /**
   * B-010 · the bulk half of `PATCH /users/{userId}/status`.
   *
   * Answers 200 with a per-resource outcome. A selection of forty in which two
   * hold open tickets is the normal case: failing the batch punishes the
   * thirty-eight, and succeeding quietly hides the two.
   */
  http.post(url('/users/bulk-status'), async ({ request }) => {
    const db = getDb();
    const { userIds, isActive } = (await request.json()) as {
      userIds: number[];
      isActive: boolean;
    };

    const results = [...new Set(userIds)].map((id) => {
      const u = db.users.find((x) => x.id === id);
      if (!u) return { userId: id, displayName: null, outcome: 'NOT_FOUND' as const };
      if (u.isActive === isActive) {
        return { userId: id, displayName: u.displayName, outcome: 'UNCHANGED' as const };
      }
      const open = db.tickets.filter((t) => t.assigneeId === id && t.status !== 'CLOSED').length;
      if (!isActive && open > 0) {
        return {
          userId: id, displayName: u.displayName,
          outcome: 'BLOCKED_OPEN_TICKETS' as const, openTicketCount: open,
        };
      }
      u.isActive = isActive;
      return { userId: id, displayName: u.displayName, outcome: 'CHANGED' as const };
    });

    const count = (outcome: string) => results.filter((r) => r.outcome === outcome).length;
    const blocked = count('BLOCKED_OPEN_TICKETS');
    return ok({
      results,
      changed: count('CHANGED'),
      unchanged: count('UNCHANGED'),
      blocked,
      notFound: count('NOT_FOUND'),
      ...(blocked > 0 ? { reassignUrl: '/api/v1/tickets/bulk-reassign' } : {}),
    });
  }),
  /**
   * B-011 · the S-08 form's read, with the `ETag` the `PATCH` requires.
   *
   * Registered **after** `/users/export` and `/users/bulk-status` above, so
   * those literal paths win. MSW matches in registration order and
   * `:userId` would otherwise swallow both.
   */
  http.get(url('/users/:userId'), ({ params }) => {
    const u = getDb().users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');
    return ok(userDetailDto(u), undefined, { headers: { ETag: userEtag(u) } });
  }),
  http.post(url('/users'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, unknown>;

    const conflicts = userConflicts(db.users, body, null);
    if (conflicts) return conflicts;

    const u: User = {
      id: Math.max(0, ...db.users.map((x) => x.id)) + 1,
      displayName: String(body.displayName),
      username: String(body.username),
      email: String(body.email),
      role: body.role as never,
      employeeCode: String(body.employeeCode ?? ''),
      avatarUrl: (body.avatarUrl as string | null) ?? null,
      reportingManagerId: (body.reportingManagerId as number | null) ?? null,
      projectIds: [],
      isActive: body.isActive == null ? true : Boolean(body.isActive),
      timezone: String(body.timezone ?? 'Asia/Kolkata'),
      // B-010 columns. `lastLoginAt` is null because a resource created a
      // moment ago has not logged in — the grid renders that as "Never".
      department: (body.department as string | null) ?? null,
      designation: (body.designation as string | null) ?? null,
      lastLoginAt: null,
      // B-011 columns.
      mobile: (body.mobile as string | null) ?? null,
      dateOfJoining: (body.dateOfJoining as string | null) ?? null,
      location: (body.location as string | null) ?? null,
      dailyCapacityHrs: Number(body.dailyCapacityHrs ?? 8),
      weeklyOff: (body.weeklyOff as number[] | null) ?? null,
      skills: (body.skills as string[]) ?? [],
      projectRoles: {},
      // B-017 columns. Empty, not filled in: S-08's Projects section has no
      // allocation input, so a resource created here has memberships with no
      // stated allocation — exactly like every row written before the Team tab
      // existed. Seeding 100 would be inventing a figure nobody entered.
      projectAllocations: {},
      projectMemberSince: {},
      // S-08's force-change-on-first-login, and never a request field.
      mustChangePassword: true,
    };
    applyProjects(u, body.projects);
    db.users.push(u);

    // `meta.temporaryPassword`, matching `UserCreatedResponse`. Fixed rather
    // than random so a test can assert on it — the real one is 16 random
    // characters from `TemporaryPasswords`.
    return ok(userDetailDto(u), { temporaryPassword: 'Mock7#TempPass9x' }, {
      status: 201,
      headers: { ETag: userEtag(u) },
    });
  }),
  http.patch(url('/users/:userId'), async ({ params, request }) => {
    const db = getDb();
    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');

    // The precondition, mirrored from `ResourceController`. Absent is 428 and
    // not "allowed through": a guard that only applies to callers who opted in
    // protects the set that needed it least.
    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the resource first and send back its ETag.');
    }
    if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== userEtag(u)) {
      return problem(412, 'precondition-failed',
        'This resource changed since you read it. Reload and reapply your edit.');
    }

    const body = (await request.json()) as Record<string, unknown>;

    const conflicts = userConflicts(db.users, body, u.id);
    if (conflicts) return conflicts;

    // A→B→C→A is as broken as A→A, and the database CHECK only catches the
    // latter. The mock refused this before the server could — it was written
    // against the contract while B-012 was still open — and the server now
    // agrees. `errors` was added when it landed, so the two produce the same
    // document and the form highlights the picker under either.
    if (body.reportingManagerId != null && createsCycle(u.id, Number(body.reportingManagerId))) {
      return problem(409, 'manager-cycle', 'That would create a reporting cycle', {
        errors: { reportingManagerId: ['That would create a reporting cycle'] },
      });
    }

    if (body.isActive === false && u.isActive) {
      const open = db.tickets.filter((t) => t.assigneeId === u.id && t.status !== 'CLOSED').length;
      if (open > 0) {
        return problem(409, 'open-tickets', 'Reassign this resource’s open tickets first', {
          openTicketCount: open, reassignUrl: '/api/v1/tickets/bulk-reassign',
        });
      }
    }

    const { projects, ...scalars } = body;
    Object.assign(u, scalars);
    applyProjects(u, projects);

    return ok(userDetailDto(u), undefined, { headers: { ETag: userEtag(u) } });
  }),
  http.patch(url('/users/:userId/status'), async ({ params, request }) => {
    const db = getDb();
    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');
    const { isActive } = (await request.json()) as { isActive: boolean };
    const open = db.tickets.filter((t) => t.assigneeId === u.id && t.status !== 'CLOSED').length;
    if (!isActive && open > 0) {
      return problem(409, 'open-tickets', 'Reassign this resource’s open tickets first', {
        openTicketCount: open, reassignUrl: '/api/v1/tickets/bulk-reassign',
      });
    }
    u.isActive = isActive;
    return noContent();
  }),
  http.get(url('/users/:userId/profile-360'), ({ params }) => {
    const db = getDb();
    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');
    const mine = db.tickets.filter((t) => t.assigneeId === u.id);
    return ok({
      user: userDto(u),
      openTickets: mine.filter((t) => t.status !== 'CLOSED').length,
      closedThisMonth: mine.filter((t) => t.status === 'CLOSED').length,
      effortHoursThisMonth: round(
        db.effortLogs.filter((e) => e.userId === u.id).reduce((s, e) => s + e.hours, 0),
      ),
      slaCompliancePct: 87.5,
      reworkRatePct: 12.0,
      currentStages: [...new Set(mine.map((t) => t.currentStageCode).filter(Boolean))],
    });
  }),
  http.get(url('/users/:userId/reportees'), ({ params, request }) => {
    const db = getDb();
    const rows = db.users.filter((u) => u.reportingManagerId === Number(params.userId));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(userDto), meta);
  }),

  // ── projects (B-016 · S-10) ───────────────────────────────────────────────
  http.get(url('/projects'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    let rows = db.projects;

    const status = q.get('status');
    if (status) rows = rows.filter((p) => p.status === status.toUpperCase());

    // `isActive` is `status !== 'CLOSED'` — see projectDto. An On Hold project
    // stays in the pickers; only a closed one leaves.
    const isActive = q.get('isActive');
    if (isActive != null) {
      const want = isActive === 'true';
      rows = rows.filter((p) => (p.status !== 'CLOSED') === want);
    }

    const managerId = q.get('managerId');
    if (managerId) rows = rows.filter((p) => p.projectManagerId === Number(managerId));

    const text = q.get('q')?.trim().toLowerCase();
    if (text) {
      rows = rows.filter((p) =>
        p.name.toLowerCase().includes(text) || p.projectCode.toLowerCase().includes(text));
    }

    // The server sorts by (name, id), which is what its keyset cursor pages
    // over. Sorting here too means the mock and the real backend put the same
    // row at the top of the grid.
    rows = [...rows].sort((a, b) => a.name.localeCompare(b.name) || a.id - b.id);

    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(projectDto), meta);
  }),
  http.get(url('/projects/:projectId'), ({ params }) => {
    const p = getDb().projects.find((x) => x.id === Number(params.projectId));
    if (!p) return notFound('Project');
    return ok(projectDetailDto(p), undefined, { headers: { ETag: projectEtag(p) } });
  }),
  http.post(url('/projects'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, string>;
    const code = String(body.projectCode ?? '').trim().toUpperCase();

    if (db.projects.some((p) => p.projectCode === code)) {
      return problem(409, 'duplicate', 'Already in use',
        { detail: `${code} is already the prefix of another project`,
          errors: { projectCode: [`${code} is already the prefix of another project`] } });
    }
    const manager = db.users.find((u) => u.id === Number(body.projectManagerId));
    if (!manager) {
      return problem(400, 'validation', 'Validation failed',
        { errors: { projectManagerId: ['no such resource'] } });
    }
    if (!manager.isActive) {
      return problem(400, 'validation', 'Validation failed',
        { errors: { projectManagerId: [`${manager.displayName} is deactivated and cannot be a project manager`] } });
    }
    if (body.startDate && body.endDate && body.endDate < body.startDate) {
      return problem(400, 'validation', 'Validation failed',
        { errors: { endDate: ['the target end date cannot be before the start date'] } });
    }

    const p: import('../db').Project = {
      id: Math.max(0, ...db.projects.map((x) => x.id)) + 1,
      projectCode: code,
      name: body.name,
      description: body.description || null,
      clientName: body.clientName || null,
      projectManagerId: Number(body.projectManagerId),
      colourTag: body.colourTag ?? '#4F46E5',
      status: (body.status as import('../db').ProjectStatus) ?? 'ACTIVE',
      startDate: body.startDate ?? null,
      endDate: body.endDate ?? null,
      autoAssignRule: (body.autoAssignRule as import('../db').AutoAssignRule) ?? 'MANUAL',
      ticketSeq: 0,
      // B-019 · a new project requires nothing beyond the fields every ticket
      // requires, and restricts no task type — it has no `project_task_types`
      // rows either. Both are the unconfigured state the Settings tab starts
      // from, and neither is something a project should acquire at creation.
      mandatoryFields: null,
    };
    db.projects.push(p);
    return ok(projectDetailDto(p), undefined,
      { status: 201, headers: { ETag: projectEtag(p) } });
  }),
  http.patch(url('/projects/:projectId'), async ({ params, request }) => {
    const db = getDb();
    const p = db.projects.find((x) => x.id === Number(params.projectId));
    if (!p) return notFound('Project');

    // If-Match is required, not opt-in — treating a missing precondition as
    // "no conflict" protects only the clients that already sent one.
    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch?.trim()) {
      return problem(428, 'precondition-required', 'If-Match is required',
        { detail: 'GET the project first and send back its ETag.' });
    }
    if (ifMatch.trim() !== '*' && ifMatch.trim() !== projectEtag(p)) {
      return problem(412, 'precondition-failed', 'This project changed since you read it',
        { detail: 'Reload and reapply your edit.' });
    }

    const body = (await request.json()) as Record<string, unknown>;

    if (body.projectCode != null) {
      const code = String(body.projectCode).trim().toUpperCase();
      if (code !== p.projectCode) {
        // The test is `ticketSeq > 0`, not "a ticket row exists": the counter
        // records codes ISSUED, and a ticket created and later deleted still
        // had its code quoted in mail. Same rule as the server's.
        if (p.ticketSeq > 0) {
          const detail = `this project has issued ${p.ticketSeq} ticket `
            + `${p.ticketSeq === 1 ? 'ID' : 'IDs'} under ${p.projectCode}, `
            + 'so its code can no longer change';
          return problem(409, 'immutable-project-code',
            'The project code can no longer change',
            { detail, ticketsIssued: p.ticketSeq, errors: { projectCode: [detail] } });
        }
        if (db.projects.some((x) => x.id !== p.id && x.projectCode === code)) {
          return problem(409, 'duplicate', 'Already in use',
            { errors: { projectCode: [`${code} is already the prefix of another project`] } });
        }
      }
      p.projectCode = code;
    }

    const startDate = (body.startDate as string) ?? p.startDate;
    const endDate = (body.endDate as string) ?? p.endDate;
    if (startDate && endDate && endDate < startDate) {
      return problem(400, 'validation', 'Validation failed',
        { errors: { endDate: ['the target end date cannot be before the start date'] } });
    }

    if (body.projectManagerId != null) {
      const manager = db.users.find((u) => u.id === Number(body.projectManagerId));
      if (!manager?.isActive) {
        return problem(400, 'validation', 'Validation failed',
          { errors: { projectManagerId: [manager ? 'that resource is deactivated' : 'no such resource'] } });
      }
      p.projectManagerId = manager.id;
    }

    if (body.name != null) p.name = String(body.name);
    if (body.description !== undefined) p.description = (body.description as string) || null;
    if (body.clientName !== undefined) p.clientName = (body.clientName as string) || null;
    if (body.colourTag !== undefined) p.colourTag = (body.colourTag as string) ?? p.colourTag;
    if (body.status != null) p.status = body.status as import('../db').ProjectStatus;
    if (body.autoAssignRule != null) p.autoAssignRule = body.autoAssignRule as import('../db').AutoAssignRule;
    p.startDate = startDate;
    p.endDate = endDate;

    return ok(projectDetailDto(p), undefined, { headers: { ETag: projectEtag(p) } });
  }),
  /**
   * B-017 · the S-10 Team tab.
   *
   * These four replace two stubs — a bare `201` and a bare `204` that answered
   * without looking at anything. Two of the four did not exist in the contract
   * at all until B-017: there was no way to *read* a project's team, and no way
   * to change an allocation except by removing the member and adding them back,
   * which would have deactivated and reactivated the row and reset `addedAt`.
   *
   * Memberships live on the user in this database (`projectIds` plus the two
   * per-project maps), which is `project_members` transposed. Same rows, read
   * from the other side.
   */
  http.get(url('/projects/:projectId/members'), ({ params }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('Project');

    const members = db.users
      .filter((u) => u.projectIds.includes(projectId))
      .map((u) => projectMemberDto(u, projectId))
      // By name, with the id as the tiebreak — two people with one name is a
      // normal thing for an organisation to contain, and the server orders the
      // same way for the same reason.
      .sort((a, b) => a.displayName.localeCompare(b.displayName) || a.userId - b.userId);

    // No `meta`, deliberately. CONVENTIONS.md §6: its absence is how a client
    // knows an unpaginated list is complete.
    return ok(members);
  }),
  http.post(url('/projects/:projectId/members'), async ({ params, request }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('Project');

    const body = (await request.json()) as {
      userId: number; projectRole?: ProjectRoleCode | null; allocationPct?: number | null;
    };
    const u = db.users.find((x) => x.id === Number(body.userId));
    if (!u) return validationFailed({ userId: ['no such resource'] });
    if (!u.isActive) {
      // Same refusal and same reason as the project manager: a deactivated
      // resource is somebody who has left, and putting them on a team means
      // every capacity figure counts somebody who will never pick the work up.
      return validationFailed({
        userId: [`${u.displayName} is deactivated and cannot be added to a project team`],
      });
    }
    if (u.projectIds.includes(projectId)) {
      return problem(409, 'already-on-team', 'Already on this team', {
        errors: { userId: ["that resource is already on this project's team"] },
      });
    }

    // Re-adding somebody who was removed is an add, not a conflict. This mock
    // has no deactivated-membership state to reactivate — dropping the id is
    // how a removal is recorded here — so the two paths look the same from
    // outside, which is what the contract promises anyway.
    u.projectIds = [...u.projectIds, projectId];
    u.projectRoles = { ...u.projectRoles, [projectId]: body.projectRole ?? null };
    u.projectAllocations = { ...u.projectAllocations, [projectId]: body.allocationPct ?? null };
    u.projectMemberSince = { ...u.projectMemberSince, [projectId]: new Date().toISOString() };

    return ok(projectMemberDto(u, projectId), undefined, { status: 201 });
  }),
  http.patch(url('/projects/:projectId/members/:userId'), async ({ params, request }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('Project');

    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u || !u.projectIds.includes(projectId)) return notFound('Member');

    const body = (await request.json()) as Record<string, unknown>;
    // `in` rather than `!= null`, and it is the whole point of the operation:
    // an omitted key keeps the stored value, an explicit null clears it. This
    // is the only way back to "same as their global role" and to "not stated",
    // and `body.projectRole != null` would make both states write-once. The
    // server needed a POJO with `Optional` fields to express the same thing —
    // a record collapses the two cases.
    if ('projectRole' in body) {
      u.projectRoles = { ...u.projectRoles, [projectId]: (body.projectRole as ProjectRoleCode) ?? null };
    }
    if ('allocationPct' in body) {
      u.projectAllocations = {
        ...u.projectAllocations,
        [projectId]: body.allocationPct == null ? null : Number(body.allocationPct),
      };
    }

    return ok(projectMemberDto(u, projectId));
  }),
  http.delete(url('/projects/:projectId/members/:userId'), ({ params }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('Project');

    const u = db.users.find((x) => x.id === Number(params.userId));
    // Removing somebody who is not on the team succeeds. It is a setter, and a
    // client retrying after a dropped response has to converge.
    if (!u || !u.projectIds.includes(projectId)) return noContent();

    const open = openTicketsOnProject(u.id, projectId);
    if (open > 0) {
      return problem(409, 'open-tickets', 'They still hold open tickets here', {
        openTicketCount: open, reassignUrl: '/api/v1/tickets/bulk-reassign',
      });
    }

    u.projectIds = u.projectIds.filter((id) => id !== projectId);
    return noContent();
  }),
  /**
   * The matrix S-10's SLA tab edits, resolved cell by cell.
   *
   * Each cell is **resolved** through `resolveSla` rather than derived from the
   * task type's default. Deriving it (`resolutionHrs: tt.defaultSlaHrs` for
   * every level, as this did until C-012) made the matrix disagree with both
   * the preview and the create path, and gave every level identical hours.
   *
   * B-018 adds `source` and `isOverride`, and they are what makes it a screen:
   * without them an inherited figure and a figure this project set are
   * indistinguishable, and the `PUT` below cannot be called correctly, because
   * it takes the overrides and not the grid.
   *
   * Every cell the mock produces resolves to something, because `resolveSla`'s
   * rung 4 has a figure for all four levels. `NONE` is unreachable here and is
   * still handled — the real backend reaches it whenever an Admin clears a
   * priority's `default_sla_hours`.
   */
  http.get(url('/projects/:projectId/sla-policies'), ({ params }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('Project');

    const grid = slaMatrix(projectId, db);
    return ok(grid, undefined, { headers: { ETag: slaMatrixEtag(grid) } });
  }),

  /**
   * B-018 · replace this project's overrides.
   *
   * Until now this echoed the request body back and wrote nothing, which is the
   * shape of mock that makes a save look like it worked. It writes real rows
   * into `db.slaPolicies` now, and that matters beyond this screen: the create
   * form's planned-close-date preview reads the same store through the same
   * `resolveSla`, so tightening the Production Bug policy here really does move
   * the date the ticket form quotes.
   *
   * The two rules that are easy to get wrong are enforced here rather than
   * assumed, because the mock is what the frontend's own tests run against:
   *
   * - **only rung-1 rows are replaced.** A project-level default
   *   (`taskTypeId: null`) has no cell in the grid, so dropping it would delete
   *   configuration through a screen that never displayed it. The seed puts one
   *   on PAY, so this is exercised rather than hypothetical.
   * - **cleared rows are deactivated, not spliced out.** `clients.slaPolicyId`
   *   is a foreign key onto them in the real schema, and `isActive: false` is
   *   what `resolveSla` already reads, so a cleared cell falls through to the
   *   next rung instead of leaving a hole.
   */
  http.put(url('/projects/:projectId/sla-policies'), async ({ params, request }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('Project');

    const stale = slaPrecondition(projectId, request.headers.get('If-Match'), db);
    if (stale) return stale;

    const overrides = (await request.json()) as SlaOverride[];

    const seen = new Set<string>();
    for (const o of overrides) {
      const key = `${o.taskTypeId}/${o.level}`;
      if (seen.has(key)) {
        return validationFailed({
          taskTypeId: [`task type ${o.taskTypeId} is listed twice at ${o.level}`],
        });
      }
      seen.add(key);

      if (!db.taskTypes.some((t) => t.id === o.taskTypeId)) {
        return validationFailed({ taskTypeId: [`no such task type: ${o.taskTypeId}`] });
      }
      if (!(o.resolutionHrs > 0)) {
        return validationFailed({ resolutionHrs: ['resolutionHrs must be greater than zero'] });
      }
      if (o.responseHrs != null && o.responseHrs > o.resolutionHrs) {
        return validationFailed({
          responseHrs: [
            `response target (${o.responseHrs}h) cannot be longer than the resolution target (${o.resolutionHrs}h)`,
          ],
        });
      }
    }

    for (const p of db.slaPolicies) {
      if (p.projectId === projectId && p.taskTypeId != null) p.isActive = false;
    }
    for (const o of overrides) {
      const existing = db.slaPolicies.find(
        (p) => p.projectId === projectId && p.taskTypeId === o.taskTypeId && p.level === o.level,
      );
      if (existing) {
        existing.responseHrs = o.responseHrs ?? null;
        existing.resolutionHrs = o.resolutionHrs;
        existing.isActive = true;
      } else {
        db.slaPolicies.push({
          // Off the highest id in the store, not `nextId`. `db.seq` starts
          // empty, so `nextId` would answer 1 — colliding with the seeded
          // org-wide LOW policy — and `PlannedCloseDatePreview.slaPolicyId`
          // puts these ids on the wire, so two rows sharing one would make the
          // preview's explanation point at the wrong policy. The contacts
          // handler dodges the same collision with `+ 100`; this is the version
          // that stays right as the store grows.
          id: Math.max(0, ...db.slaPolicies.map((p) => p.id)) + 1,
          projectId,
          taskTypeId: o.taskTypeId,
          level: o.level,
          responseHrs: o.responseHrs ?? null,
          resolutionHrs: o.resolutionHrs,
          isActive: true,
        });
      }
    }

    const grid = slaMatrix(projectId, db);
    return ok(grid, undefined, { headers: { ETag: slaMatrixEtag(grid) } });
  }),

  /**
   * B-019 · S-10's Settings tab.
   *
   * The mock holds the rule the whole screen turns on as literally as the
   * database does: **no `projectTaskTypes` rows for a project means every
   * active task type is allowed, not none.** `restrictsTaskTypes` is what
   * distinguishes the two, and a mock that answered `isAllowed: false`
   * everywhere for an unconfigured project would make the frontend's own tests
   * agree with a reading that would have stopped ticket creation everywhere.
   */
  http.get(url('/projects/:projectId/settings'), ({ params }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    const project = db.projects.find((p) => p.id === projectId);
    if (!project) return notFound('Project');

    const settings = projectSettings(project, db);
    return ok(settings, undefined, { headers: { ETag: projectSettingsEtag(settings) } });
  }),

  /**
   * B-019 · replace all three settings.
   *
   * Writes real rows rather than echoing the body — the shape of mock that
   * makes a save look like it worked. The two rules that are easy to get wrong
   * are enforced here rather than assumed, because this mock is what the
   * frontend's own tests run against:
   *
   * - **an empty `allowedTaskTypeIds` clears the rows**, returning the project
   *   to unrestricted. It is not refused as a probable mistake; it is the only
   *   way to remove a restriction.
   * - **an empty `mandatoryFields` is stored as `null`**, like the repository,
   *   so the two representations of "requires nothing extra" cannot drift
   *   apart in the fixture the way they could in the column.
   */
  http.put(url('/projects/:projectId/settings'), async ({ params, request }) => {
    const db = getDb();
    const projectId = Number(params.projectId);
    const project = db.projects.find((p) => p.id === projectId);
    if (!project) return notFound('Project');

    // The mock enforces If-Match too. A guard the real backend has and the mock
    // waves through is a guard the frontend never gets to exercise.
    const ifMatch = request.headers.get('If-Match');
    const current = projectSettingsEtag(projectSettings(project, db));
    if (!ifMatch?.trim()) {
      return problem(428, 'precondition-required', 'If-Match is required',
        { detail: 'GET the settings first and send back its ETag.' });
    }
    if (ifMatch.trim() !== '*' && ifMatch.trim() !== current) {
      return problem(412, 'precondition-failed', 'These settings changed since you read them',
        { detail: 'Reload and reapply your edit.' });
    }

    const body = (await request.json()) as {
      autoAssignRule?: string;
      mandatoryFields?: string[];
      allowedTaskTypeIds?: number[];
    };

    const rules = ['ROUND_ROBIN', 'LEAST_LOADED', 'MANUAL'];
    const rule = (body.autoAssignRule ?? 'MANUAL').toUpperCase();
    if (!rules.includes(rule)) {
      return validationFailed({ autoAssignRule: [`autoAssignRule must be one of ${rules}`] });
    }

    const fields = body.mandatoryFields ?? [];
    for (const code of fields) {
      if (!TICKET_FIELD_CODES.includes(code)) {
        return validationFailed({ mandatoryFields: [`no such ticket field: ${code}`] });
      }
      if (fields.filter((f) => f === code).length > 1) {
        return validationFailed({ mandatoryFields: [`${code} is listed twice`] });
      }
    }

    const allowed = body.allowedTaskTypeIds ?? [];
    for (const id of allowed) {
      if (!db.taskTypes.some((t) => t.id === id)) {
        return validationFailed({ allowedTaskTypeIds: [`no such task type: ${id}`] });
      }
      if (allowed.filter((x) => x === id).length > 1) {
        return validationFailed({ allowedTaskTypeIds: [`task type ${id} is listed twice`] });
      }
    }

    project.autoAssignRule = rule as import('../db').AutoAssignRule;
    project.mandatoryFields = fields.length === 0
      ? null
      : (fields as import('../db').TicketFieldCode[]);

    db.projectTaskTypes = db.projectTaskTypes.filter((r) => r.projectId !== projectId);
    for (const id of allowed) {
      db.projectTaskTypes.push({ projectId, taskTypeId: id });
    }

    const settings = projectSettings(project, db);
    return ok(settings, undefined, { headers: { ETag: projectSettingsEtag(settings) } });
  }),

  // ── clients ───────────────────────────────────────────────────────────────
  http.get(url('/clients'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    let rows = db.clients;
    const text = q.get('q')?.toLowerCase();
    if (text) {
      rows = rows.filter((c) =>
        [c.name, c.clientCode, c.domain].some((f) => f.toLowerCase().includes(text)),
      );
    }
    if (q.get('isActive')) rows = rows.filter((c) => String(c.isActive) === q.get('isActive'));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(clientDto), meta);
  }),
  http.post(url('/clients'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, string>;
    if (db.clients.some((c) => c.clientCode === body.clientCode)) {
      return problem(409, 'duplicate', 'That client code is already in use');
    }
    const c = {
      id: db.clients.length + 1, clientCode: body.clientCode, name: body.name,
      domain: body.domain ?? '', accountManagerId: Number(body.accountManagerId ?? 2),
      supportPlan: body.supportPlan ?? 'Standard',
      timezone: body.timezone ?? 'Asia/Kolkata', isActive: true,
    };
    db.clients.push(c);
    return ok(clientDto(c), undefined, { status: 201 });
  }),
  http.patch(url('/clients/:clientId'), async ({ params, request }) => {
    const db = getDb();
    const c = db.clients.find((x) => x.id === Number(params.clientId));
    if (!c) return notFound('Client');
    Object.assign(c, await request.json());
    return ok(clientDto(c));
  }),
  http.patch(url('/clients/:clientId/status'), async ({ params, request }) => {
    const db = getDb();
    const c = db.clients.find((x) => x.id === Number(params.clientId));
    if (!c) return notFound('Client');
    const { isActive } = (await request.json()) as { isActive: boolean };
    c.isActive = isActive;
    // Deactivating warns and blocks NEW tickets — it never hides historical ones.
    return ok(clientDto(c));
  }),
  http.get(url('/clients/:clientId/contacts'), ({ params }) =>
    ok(getDb().contacts.filter((x) => x.clientId === Number(params.clientId))),
  ),
  http.post(url('/clients/:clientId/contacts'), async ({ params, request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, unknown>;
    if (!body.email || !String(body.email).includes('@')) {
      return validationFailed({ email: ['must be a well-formed email address'] });
    }
    const clientId = Number(params.clientId);
    if (body.isPrimary) {
      // Setting a new primary demotes the previous one, in the same transaction.
      db.contacts.filter((c) => c.clientId === clientId).forEach((c) => { c.isPrimary = false; });
    }
    const c = {
      id: nextId(db, 'contact') + 100, clientId, name: String(body.name),
      email: String(body.email), phone: String(body.phone ?? ''),
      isPrimary: Boolean(body.isPrimary),
      notificationOptIn: body.notificationOptIn !== false,
      portalAccess: Boolean(body.portalAccess),
    };
    db.contacts.push(c);
    return ok(c, undefined, { status: 201 });
  }),
  http.get(url('/clients/:clientId/tickets'), ({ params, request }) => {
    const db = getDb();
    const c = db.clients.find((x) => x.id === Number(params.clientId));
    if (!c) return notFound('Client');
    const rows = scopedTickets(db).filter((t) => t.clientId === c.id);
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok({
      client: clientDto(c),
      tickets: page.map((t) => ticketDto(t, db)),
      openCount: rows.filter((t) => t.status !== 'CLOSED').length,
      closedCount: rows.filter((t) => t.status === 'CLOSED').length,
      slaCompliancePct: 91.2,
      avgResolutionHrs: 18.4,
    }, meta);
  }),

  // ── imports ───────────────────────────────────────────────────────────────
  http.get(url('/imports/:schema/template'), () =>
    new HttpResponse(new Blob(['mock xlsx template']), {
      headers: {
        'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'Content-Disposition': 'attachment; filename="import-template.xlsx"',
      },
    }),
  ),
  http.post(url('/imports/:schema/upload'), () =>
    ok({
      uploadId: '11111111-2222-3333-4444-555555555555',
      sheets: ['Clients'], rowCount: 128,
      headers: ['Client Code', 'Name', 'Domain', 'Support Plan', 'Status'],
      suggestedMapping: {
        clientCode: 'Client Code', name: 'Name', domain: 'Domain',
        supportPlan: 'Support Plan', isActive: 'Status',
      },
    }),
  ),
  http.post(url('/imports/:schema/validate'), () =>
    // A dry run writes nothing and shows a per-row verdict. This is the step
    // that makes a bulk import safe to run at all.
    ok({
      willCreate: 120, willUpdate: 5, duplicates: 2, rejected: 1,
      rows: [
        { rowNumber: 2, verdict: 'WILL_CREATE', reason: null, values: { clientCode: 'NEWCO', name: 'Newco Ltd' } },
        { rowNumber: 3, verdict: 'WILL_UPDATE', reason: 'Client code already exists — will be updated', values: { clientCode: 'ACME', name: 'Acme Retail Limited' } },
        { rowNumber: 7, verdict: 'DUPLICATE_IN_FILE', reason: 'Client code NEWCO appears on row 2', values: { clientCode: 'NEWCO' } },
        { rowNumber: 9, verdict: 'REJECTED', reason: 'Email is not well-formed', values: { clientCode: 'BADCO', email: 'not-an-email' } },
      ],
    }),
  ),
  http.post(url('/imports/:schema/commit'), () =>
    ok({
      batchId: '99999999-8888-7777-6666-555555555555', status: 'RUNNING',
      processed: 0, total: 128, created: 0, updated: 0, rejected: 0, errorReportUrl: null,
    }, undefined, { status: 202 }),
  ),
  http.get(url('/import-batches/:batchId'), () =>
    ok({
      batchId: '99999999-8888-7777-6666-555555555555', status: 'COMPLETED',
      processed: 128, total: 128, created: 120, updated: 5, rejected: 3,
      errorReportUrl: '/mock-files/import-errors.xlsx',
    }, undefined, { headers: { ETag: 'W/"batch-complete"' } }),
  ),

  // ── browser push · D-045 ──────────────────────────────────────────────────
  // A real, well-formed VAPID public key shape — 65 base64url bytes — so a
  // client that passes it to `pushManager.subscribe()` fails on the browser's
  // permission prompt rather than on a key the API would never have accepted.
  http.get(url('/push/public-key'), () =>
    ok({ publicKey: 'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U' }),
  ),
  http.post(url('/me/push-subscriptions'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as {
      endpoint?: string
      keys?: { p256dh?: string; auth?: string }
      userAgent?: string | null
    };
    if (!body.endpoint || !body.keys?.p256dh || !body.keys?.auth) {
      return validationFailed({ endpoint: ['is missing or malformed'] });
    }
    // Keyed on the endpoint, and the user is reassigned on conflict — the same
    // rule the real table's unique key enforces, so a client cannot be built
    // against a mock that quietly allows two owners for one browser.
    const existing = db.pushSubscriptions.find((s) => s.endpoint === body.endpoint);
    const row = {
      endpoint: body.endpoint,
      userId: db.currentUserId,
      p256dh: body.keys.p256dh,
      auth: body.keys.auth,
      userAgent: body.userAgent ?? null,
    };
    if (existing) Object.assign(existing, row);
    else db.pushSubscriptions.push(row);
    return noContent();
  }),
  http.delete(url('/me/push-subscriptions'), ({ request }) => {
    const db = getDb();
    const endpoint = new URL(request.url).searchParams.get('endpoint');
    // Scoped to the caller, and 204 either way: unsubscribing states a desired
    // state, and a browser the push service already expired has nothing to
    // report as an error.
    db.pushSubscriptions = db.pushSubscriptions.filter(
      (s) => !(s.endpoint === endpoint && s.userId === db.currentUserId),
    );
    return noContent();
  }),

  // ── masters ───────────────────────────────────────────────────────────────
  http.get(url('/masters/task-types'), () => ok(getDb().taskTypes)),
  // Inactive rows included, in `seq` order — a picker filters them out, a grid
  // still has to name the module an old ticket was raised against.
  http.get(url('/masters/modules'), () =>
    ok([...getDb().modules].sort((a, b) => a.seq - b.seq)),
  ),
  http.get(url('/masters/priorities'), () =>
    ok([
      { id: 1, level: 'LOW', colour: '#84CC16', defaultSlaHrs: 120, autoEscalates: false },
      { id: 2, level: 'MEDIUM', colour: '#F59E0B', defaultSlaHrs: 48, autoEscalates: false },
      { id: 3, level: 'HIGH', colour: '#9A3412', defaultSlaHrs: 16, autoEscalates: true },
      { id: 4, level: 'CRITICAL', colour: '#BE185D', defaultSlaHrs: 4, autoEscalates: true },
    ]),
  ),
  // ── roles & permissions · S-09 (B-015) ────────────────────────────────────
  // Reference data. No create, edit or delete: a capability exists because code
  // checks for it, so a row an admin could add would grant nothing.
  http.get(url('/masters/permissions'), () => ok(getDb().permissions)),

  http.get(url('/masters/roles'), ({ request }) => {
    const isActive = new URL(request.url).searchParams.get('isActive');
    const rows = getDb().roles
      .filter((r) => isActive == null || r.isActive === (isActive === 'true'))
      .sort((a, b) => a.name.localeCompare(b.name));
    return ok(rows.map(roleDto));
  }),

  http.post(url('/masters/roles'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, unknown>;
    const code = String(body.code ?? '').trim().toUpperCase();

    if (db.roles.some((r) => r.code === code)) {
      return problem(409, 'duplicate', 'Duplicate role code',
        { detail: `A role with code '${code}' already exists`, errors: { code: ['already taken'] } });
    }
    // isSystem is never taken from the body. Nothing created here is one of the six.
    const created = {
      // Max-plus-one rather than a counter: a test that pushes a fixture role
      // with an explicit id would otherwise get a collision on the next create.
      id: Math.max(0, ...db.roles.map((r) => r.id)) + 1,
      code,
      name: String(body.name ?? '').trim(),
      description: (body.description as string | null) || null,
      isSystem: false,
      isActive: body.isActive == null ? true : Boolean(body.isActive),
    };
    db.roles.push(created);
    db.roleGrants[created.id] = [];
    return ok(roleDetailDto(created), undefined,
      { status: 201, headers: { ETag: roleEtag(created) } });
  }),

  http.get(url('/masters/roles/:roleId'), ({ params }) => {
    const role = getDb().roles.find((r) => r.id === Number(params.roleId));
    if (!role) return notFound('Role');
    return HttpResponse.json({ data: roleDetailDto(role) },
      { headers: { ETag: roleEtag(role) } });
  }),

  http.patch(url('/masters/roles/:roleId'), async ({ params, request }) => {
    const role = getDb().roles.find((r) => r.id === Number(params.roleId));
    if (!role) return notFound('Role');

    const stale = rolePrecondition(role, request.headers.get('If-Match'));
    if (stale) return stale;

    const body = (await request.json()) as Record<string, unknown>;
    // A code change is refused even on a non-system role: the code is carried
    // in issued tokens, in @PreAuthorize and in workflow_transitions.
    if (body.code != null && String(body.code).trim().toUpperCase() !== role.code) {
      return problem(409, 'immutable-field', 'Role code cannot be changed', {
        detail: 'A role code cannot be changed once created. Deactivate this role and create a replacement.',
        errors: { code: ['immutable'] },
      });
    }
    if (body.name != null) role.name = String(body.name).trim();
    if (body.description !== undefined) role.description = (body.description as string | null) || null;
    if (body.isActive != null) role.isActive = Boolean(body.isActive);

    return HttpResponse.json({ data: roleDetailDto(role) },
      { headers: { ETag: roleEtag(role) } });
  }),

  http.delete(url('/masters/roles/:roleId'), ({ params }) => {
    const db = getDb();
    const role = db.roles.find((r) => r.id === Number(params.roleId));
    if (!role) return notFound('Role');

    // The system check comes first. A system role always has holders, so
    // reporting in-use first would say "reassign 6 people" for a role that
    // could never be deleted however many people were reassigned.
    if (role.isSystem) {
      return problem(409, 'system-role-undeletable', 'System role', {
        detail: `'${role.code}' is a system role and cannot be deleted. Deactivate it instead.`,
        userCount: 0,
      });
    }
    const holders = db.users.filter((u) => u.role === role.code).length;
    if (holders > 0) {
      return problem(409, 'role-in-use', 'Role still in use', {
        detail: `${holders} resource${holders === 1 ? '' : 's'} still hold '${role.code}'. Reassign them before deleting the role.`,
        userCount: holders,
      });
    }
    db.roles.splice(db.roles.indexOf(role), 1);
    delete db.roleGrants[role.id];
    return noContent();
  }),

  http.put(url('/masters/roles/:roleId/permissions'), async ({ params, request }) => {
    const db = getDb();
    const role = db.roles.find((r) => r.id === Number(params.roleId));
    if (!role) return notFound('Role');

    const stale = rolePrecondition(role, request.headers.get('If-Match'));
    if (stale) return stale;

    const body = (await request.json()) as { permissionCodes?: string[] };
    const wanted = [...new Set((body.permissionCodes ?? []).map((c) => c.trim()).filter(Boolean))];

    // 422, not a disabled checkbox. This is the one reachable UI edge on the
    // append-only guarantee, so the refusal is server-side.
    const ungrantable = wanted.filter(
      (c) => db.permissions.find((p) => p.code === c)?.isGrantable === false,
    );
    if (ungrantable.length > 0) {
      return problem(422, 'ungrantable-permission', 'Permission cannot be granted', {
        detail: `${ungrantable.join(', ')} cannot be granted to any role. Blueprint §2: ticket history and the ribbon are append-only, and nobody may edit them.`,
      });
    }
    // Unknown codes are named, not skipped: a typo that quietly grants nothing
    // is a permission bug found in production.
    const unknown = wanted.filter((c) => !db.permissions.some((p) => p.code === c));
    if (unknown.length > 0) {
      return validationFailed({ permissionCodes: [`No such permission: ${unknown.join(', ')}`] });
    }

    db.roleGrants[role.id] = wanted;
    return HttpResponse.json({ data: roleDetailDto(role) },
      { headers: { ETag: roleEtag(role) } });
  }),

  // ── working calendar · S-14 (B-023) ───────────────────────────────────────
  http.get(url('/masters/holidays'), () =>
    ok({
      holidays: calendarState().holidays.filter((h) => h.isActive),
      ...workingWeek(),
    }),
  ),
  http.post(url('/masters/holidays'), async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    const date = String(body.date);
    const projectId = (body.projectId ?? null) as number | null;
    // uq_holidays (holiday_date, project_id). A project holiday does not clash
    // with the org one for the same date — either makes the day non-working.
    if (calendarState().holidays.some((h) => h.date === date && h.projectId === projectId)) {
      return problem(409, 'duplicate', projectId === null
        ? `An org-wide holiday already exists on ${date}`
        : `Project ${projectId} already has a holiday on ${date}`);
    }
    const created = {
      id: nextCalendarId('holiday'), date, name: String(body.name), projectId,
      isRecurring: Boolean(body.isRecurring ?? false),
      isActive: Boolean(body.isActive ?? true),
    };
    calendarState().holidays.push(created);
    return ok(created, undefined, { status: 201 });
  }),
  http.patch(url('/masters/holidays/:holidayId'), async ({ params, request }) => {
    const holiday = calendarState().holidays.find((h) => h.id === Number(params.holidayId));
    if (!holiday) return notFound('Holiday');
    const body = (await request.json()) as Partial<Holiday>;
    // Only what was sent — omitting a field must not blank it. Mirrors the real
    // service's nullValuePropertyMappingStrategy = IGNORE.
    const sent = Object.fromEntries(
      Object.entries(body).filter(([, v]) => v !== undefined && v !== null),
    ) as Partial<Holiday>;
    Object.assign(holiday, sent);
    return ok(holiday);
  }),
  http.delete(url('/masters/holidays/:holidayId'), ({ params }) => {
    const i = calendarState().holidays.findIndex((h) => h.id === Number(params.holidayId));
    if (i < 0) return notFound('Holiday');
    calendarState().holidays.splice(i, 1);
    return noContent();
  }),

  http.get(url('/masters/working-calendar'), () =>
    HttpResponse.json({ data: workingWeekFull() }, { headers: { ETag: calendarEtag() } }),
  ),
  http.put(url('/masters/working-calendar'), async ({ request }) => {
    // The mock enforces If-Match too. A guard the real backend has and the mock
    // waves through is a guard the frontend never gets to exercise.
    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the working week first and send back its ETag.');
    }
    if (ifMatch !== '*' && ifMatch.replace(/W\//, '') !== calendarEtag()) {
      return problem(412, 'precondition-failed',
        'The working week changed since you read it. Reload and reapply your edit.');
    }
    const body = (await request.json()) as Record<string, unknown>;
    calendarState().week = {
      weeklyOff: [...(body.weeklyOff as number[])].sort((a, b) => a - b),
      // Echoed back with seconds, as a LocalTime serialises. A client that
      // sends `09:30` must be able to read `09:30:00` back without surprise.
      workDayStart: withSeconds(String(body.workDayStart)),
      workDayEnd: withSeconds(String(body.workDayEnd)),
      timezone: String(body.timezone),
    };
    return HttpResponse.json({ data: workingWeekFull() }, { headers: { ETag: calendarEtag() } });
  }),

  http.get(url('/masters/leaves'), ({ request }) => {
    const q = new URL(request.url).searchParams;
    let rows = calendarState().leaves;
    if (q.get('userId')) rows = rows.filter((l) => l.userId === Number(q.get('userId')));
    if (q.get('status')) rows = rows.filter((l) => l.status === q.get('status'));
    // Overlap, not containment: leave that brackets the window entirely has
    // neither endpoint inside it.
    const from = q.get('from');
    const to = q.get('to');
    if (from) rows = rows.filter((l) => l.endDate >= from);
    if (to) rows = rows.filter((l) => l.startDate <= to);
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),
  http.post(url('/masters/leaves'), async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    if (String(body.endDate) < String(body.startDate)) {
      return validationFailed({ endDate: ['Leave cannot end before it starts'] });
    }
    const created = {
      id: nextCalendarId('leave'), userId: Number(body.userId),
      startDate: String(body.startDate), endDate: String(body.endDate),
      leaveType: (body.leaveType ?? 'PLANNED') as string,
      isHalfDay: Boolean(body.isHalfDay ?? false),
      status: (body.status ?? 'APPROVED') as string,
      reason: (body.reason ?? null) as string | null,
    };
    calendarState().leaves.push(created);
    return ok(created, undefined, { status: 201 });
  }),
  http.patch(url('/masters/leaves/:leaveId'), async ({ params, request }) => {
    const leave = calendarState().leaves.find((l) => l.id === Number(params.leaveId));
    if (!leave) return notFound('Leave');
    const body = (await request.json()) as Record<string, unknown>;
    const start = String(body.startDate ?? leave.startDate);
    const end = String(body.endDate ?? leave.endDate);
    if (end < start) return validationFailed({ endDate: ['Leave cannot end before it starts'] });
    Object.assign(leave, body);
    return ok(leave);
  }),
  http.delete(url('/masters/leaves/:leaveId'), ({ params }) => {
    const i = calendarState().leaves.findIndex((l) => l.id === Number(params.leaveId));
    if (i < 0) return notFound('Leave');
    calendarState().leaves.splice(i, 1);
    return noContent();
  }),
  http.get(url('/masters/workflow-templates'), () =>
    ok([{
      id: 1, name: 'Standard Dev Flow', version: 1, projectId: null, taskTypeId: null,
      isActive: true,
      stages: getDb().stages.map((s) => ({ ...s, isDeprecated: false })),
    }]),
  ),
  http.post(url('/masters/workflow-templates'), async ({ request }) =>
    ok({ id: 2, version: 1, isActive: true, ...(await request.json() as object) },
       undefined, { status: 201 }),
  ),

  // ── dashboard & reports ───────────────────────────────────────────────────
  http.get(url('/dashboard/summary'), () => {
    const db = getDb();
    const rows = scopedTickets(db);
    const open = rows.filter((t) => t.status !== 'CLOSED');
    const card = (key: string, label: string, value: number, drillDown: string) => ({
      key, label, value,
      deltaPct: Math.round(((value % 7) - 3) * 10) / 10,
      sparkline: Array.from({ length: 12 }, (_, i) => Math.max(0, value - 6 + ((i * 3) % 7))),
      drillDown,
    });
    return ok({
      asOf: new Date().toISOString(),
      cards: [
        card('total', 'Total tickets', rows.length, '/tickets'),
        card('open', 'Open', open.length, '/tickets?status=NEW'),
        card('closed', 'Closed', rows.length - open.length, '/tickets?status=CLOSED'),
        card('critical', 'Critical', rows.filter((t) => t.level === 'CRITICAL').length, '/tickets?level=CRITICAL'),
        card('delayed', 'Delayed', rows.filter((t) => t.isDelayed).length, '/tickets?isDelayed=true'),
        card('reopened', 'Reopened', rows.filter((t) => t.reopenCount > 0).length, '/tickets?reopenedOnly=true'),
      ],
    });
  }),
  http.get(url('/dashboard/widget/:widgetKey'), ({ params, request }) => {
    const db = getDb();
    const key = String(params.widgetKey);
    const etag = `W/"widget-${key}"`;
    if (request.headers.get('If-None-Match') === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }
    const rows = scopedTickets(db);
    let series;
    if (key === 'type-donut') {
      series = [{
        name: 'By task type',
        points: db.taskTypes.map((tt) => ({
          x: tt.name,
          y: rows.filter((t) => t.taskTypeId === tt.id).length,
          drillDown: `/tickets?taskTypeId=${tt.id}`,
        })),
      }];
    } else if (key === 'stage-funnel') {
      series = [{
        name: 'By stage',
        points: db.stages.map((s) => ({
          x: s.displayName,
          y: rows.filter((t) => t.currentStageCode === s.stageCode).length,
          drillDown: `/tickets?stage=${s.stageCode}`,
        })),
      }];
    } else {
      series = [{
        name: key,
        points: Array.from({ length: 14 }, (_, i) => ({
          x: new Date(Date.UTC(2026, 6, 24 + i)).toISOString().slice(0, 10),
          y: 3 + ((i * 5) % 11),
          drillDown: null,
        })),
      }];
    }
    return ok({ key, asOf: new Date().toISOString(), series }, undefined, { headers: { ETag: etag } });
  }),
  http.get(url('/reports/:reportKey'), ({ params, request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    if (q.get('export')) {
      return new HttpResponse(new Blob(['mock export']), {
        headers: { 'Content-Type': 'application/octet-stream' },
      });
    }
    return ok({
      reportKey: String(params.reportKey),
      columns: [
        { key: 'resource', label: 'Resource', type: 'string' },
        { key: 'closed', label: 'Closed', type: 'number' },
        { key: 'effortHrs', label: 'Effort', type: 'duration' },
        { key: 'slaPct', label: 'SLA', type: 'percent' },
      ],
      rows: db.users.map((u) => ({
        resource: u.displayName,
        closed: db.tickets.filter((t) => t.assigneeId === u.id && t.status === 'CLOSED').length,
        effortHrs: round(db.effortLogs.filter((e) => e.userId === u.id).reduce((s, e) => s + e.hours, 0)),
        slaPct: 80 + (u.id * 3) % 20,
      })),
    }, undefined, { headers: { ETag: `W/"report-${params.reportKey}"` } });
  }),
  http.post(url('/reports/schedule'), () => new HttpResponse(null, { status: 201 })),

  // ── notifications ─────────────────────────────────────────────────────────
  http.get(url('/notifications'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    let rows = db.notifications.filter((n) => n.userId === db.currentUserId);
    const tab = q.get('tab');
    if (tab === 'mentions') rows = rows.filter((n) => n.eventKey === 'MENTIONED');
    if (tab === 'assignments') rows = rows.filter((n) => n.eventKey === 'TICKET_HANDED_OFF');
    if (tab === 'escalations') rows = rows.filter((n) => n.eventKey === 'SLA_BREACH');
    if (q.get('unreadOnly') === 'true') rows = rows.filter((n) => !n.isRead);
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, {
      ...meta,
      unreadCount: db.notifications.filter((n) => n.userId === db.currentUserId && !n.isRead).length,
    });
  }),
  http.patch(url('/notifications/:notificationId/read'), ({ params }) => {
    const n = getDb().notifications.find((x) => x.id === Number(params.notificationId));
    if (!n) return notFound('Notification');
    n.isRead = true;
    return noContent();
  }),
  http.patch(url('/notifications/read-all'), () => {
    const db = getDb();
    db.notifications.filter((n) => n.userId === db.currentUserId).forEach((n) => { n.isRead = true; });
    return noContent();
  }),
  // D-042/D-036. The catalogue with overrides applied, mirroring the server:
  // built from the event list so a new event appears without a stored row, and
  // a locked mail always reads as on whatever the override says.
  http.get(url('/me/notification-preferences'), () => {
    const db = getDb();
    return HttpResponse.json({ data: preferenceMatrix(db) });
  }),
  http.put(url('/me/notification-preferences'), async ({ request }) => {
    const db = getDb();
    const { preferences } = (await request.json()) as {
      preferences: { eventKey: string; inApp?: boolean; email?: boolean; push?: boolean }[];
    };
    for (const update of preferences) {
      const event = NOTIFICATION_EVENTS.find((e) => e.eventKey === update.eventKey);
      if (!event) {
        return HttpResponse.json(
          {
            type: 'https://edutrack/errors/invalid-body',
            title: 'Unknown notification event',
            status: 400,
            detail: 'One or more eventKey values are not notification events',
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        );
      }
      const stored = (db.notificationPreferences ??= []);
      const row = stored.find(
        (p) => p.userId === db.currentUserId && p.eventKey === update.eventKey,
      ) ?? {
        userId: db.currentUserId, eventKey: update.eventKey,
        inApp: true, email: true, push: true,
      };
      if (update.inApp !== undefined) row.inApp = update.inApp;
      // Discarded rather than rejected when locked — as on the server.
      if (update.email !== undefined && !isMandatoryMail(event.category)) row.email = update.email;
      // No lock to check: there is no mandatory push.
      if (update.push !== undefined) row.push = update.push;
      if (!stored.includes(row)) stored.push(row);
    }
    return HttpResponse.json({ data: preferenceMatrix(db) });
  }),
  // D-046. Oldest first and independent of isRead — mirroring the server, where
  // "shown" and "read" are separate facts.
  http.get(url('/notifications/pending'), ({ request }) => {
    const db = getDb();
    const limit = Number(new URL(request.url).searchParams.get('limit') ?? 5);
    const queued = db.notifications
      .filter((n) => n.userId === db.currentUserId && !n.deliveredAt)
      .sort((a, b) => a.id - b.id);
    return HttpResponse.json({ data: queued.slice(0, limit), hasMore: queued.length > limit });
  }),
  http.post(url('/notifications/delivered'), async ({ request }) => {
    const db = getDb();
    const { ids } = (await request.json()) as { ids: number[] };
    db.notifications
      // Somebody else's ids are ignored rather than rejected, as on the server.
      .filter((n) => n.userId === db.currentUserId && ids.includes(n.id) && !n.deliveredAt)
      .forEach((n) => { n.deliveredAt = new Date().toISOString(); });
    return noContent();
  }),

  // ── chat ──────────────────────────────────────────────────────────────────
  http.get(url('/chat/threads'), ({ request }) => {
    const db = getDb();
    const kind = new URL(request.url).searchParams.get('kind');
    const rows = db.chatThreads
      .filter((t) => !kind || t.kind === kind)
      .map((t) => ({
        id: t.id, kind: t.kind, title: t.title, ticketId: t.ticketId,
        unreadCount: db.chatMessages.filter(
          (m) => m.threadId === t.id && !m.readBy.includes(db.currentUserId),
        ).length,
        lastMessageAt: t.lastMessageAt,
        participants: t.participantIds.map((id) => userRef(id, db)),
      }));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),
  http.get(url('/chat/threads/:threadId/messages'), ({ params, request }) => {
    const db = getDb();
    const rows = db.chatMessages
      .filter((m) => m.threadId === Number(params.threadId))
      .map(messageDto);
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),
  http.post(url('/chat/threads/:threadId/messages'), async ({ params, request }) => {
    const db = getDb();
    const thread = db.chatThreads.find((t) => t.id === Number(params.threadId));
    if (!thread) return notFound('Thread');
    const { body } = (await request.json()) as { body: string };
    if (!body?.trim()) return validationFailed({ body: ['must not be blank'] });
    const m = {
      id: nextId(db, 'message'), threadId: thread.id, body,
      authorId: db.currentUserId, kind: 'TEXT' as const,
      isEdited: false, isDeleted: false, readBy: [db.currentUserId],
      createdAt: new Date().toISOString(),
    };
    db.chatMessages.push(m);
    thread.lastMessageAt = m.createdAt;

    // D-056. A reply closes whatever it answers, here as on the server —
    // otherwise the badge and the awaiting-response list would only ever grow
    // in the mock, and the client would be built against a list that never
    // clears. Not the poster's own request: a manager chasing their own
    // question must not close it.
    for (const r of db.statusRequests) {
      if (r.threadId !== thread.id || r.answeredAt !== null) continue;
      if (r.requestedById === db.currentUserId) continue;
      const ticket = db.tickets.find((t) => t.ticketId === r.ticketId);
      if (r.askedOfId !== db.currentUserId && ticket?.assigneeId !== db.currentUserId) continue;
      r.answerMessageId = m.id;
      r.answeredAt = m.createdAt;
      // Working minutes, so the mock cannot teach a wall-clock reading. Same
      // calendar the planned-close-date preview walks.
      r.responseWorkingMinutes = workingMinutesBetween(
        r.requestedAt, m.createdAt, ticket?.projectId ?? null, r.askedOfId, db,
      );
    }
    return ok(messageDto(m), undefined, { status: 201 });
  }),

  /**
   * D-054 · cards for codes a client already has, for the live path.
   *
   * A message arriving over the socket is one frame to a whole room, so it
   * carries no cards — each client resolves its own. Scoped, and silent about
   * what it withheld.
   */
  http.get(url('/chat/ticket-cards'), ({ request }) => {
    const raw = new URL(request.url).searchParams.get('codes') ?? '';
    return ok(ticketCardsFor(ticketCodesIn(raw.replace(/,/g, ' '))));
  }),

  /** D-056 · the manager's "Awaiting response" list, longest wait first. */
  http.get(url('/me/awaiting-response'), () => {
    const db = getDb();
    return ok(
      db.statusRequests
        .filter((r) => r.requestedById === db.currentUserId && r.answeredAt === null)
        .sort((a, b) => a.requestedAt.localeCompare(b.requestedAt))
        .map((r) => statusRequestDto(r, db)),
    );
  }),

  /**
   * D-053 search. Mirrors the two rules the backend enforces, because a mock
   * that is more permissive than the server teaches the UI a behaviour it will
   * later lose: **deleted messages never match**, and words shorter than
   * `innodb_ft_min_token_size` are ignored.
   */
  http.get(url('/chat/messages/search'), ({ request }) => {
    const db = getDb();
    const query = new URL(request.url).searchParams;
    const threadId = query.get('threadId');
    const terms = (query.get('q') ?? '')
      .toLowerCase()
      .split(/[^\p{L}\p{N}\p{M}_]+/u)
      .filter((term) => term.length >= 3);

    const hits = terms.length === 0 ? [] : db.chatMessages
      .filter((m) => !m.isDeleted)
      .filter((m) => !threadId || m.threadId === Number(threadId))
      .filter((m) => terms.every((term) => m.body.toLowerCase().includes(term)))
      .sort((a, b) => b.id - a.id)
      .map((m) => {
        const thread = db.chatThreads.find((t) => t.id === m.threadId);
        return {
          messageId: m.id,
          threadId: m.threadId,
          threadKind: thread?.kind ?? 'TICKET',
          threadTitle: thread?.title ?? 'Direct message',
          ticketId: thread?.ticketId ?? null,
          body: m.body,
          author: userRef(m.authorId, db),
          createdAt: m.createdAt,
        };
      });

    const { page, meta } = paginate(hits, new URL(request.url));
    return ok(page, meta);
  }),

  /**
   * D-057. Author-only inside five minutes — 404 when it is not yours (a 409
   * would confirm the message exists and who wrote it), 409 only once your own
   * message has aged out.
   */
  http.patch(url('/chat/threads/:threadId/messages/:messageId'), async ({ params, request }) => {
    const db = getDb();
    const m = db.chatMessages.find(
      (x) => x.id === Number(params.messageId) && x.threadId === Number(params.threadId),
    );
    if (!m || m.authorId !== db.currentUserId) return notFound('Message');

    const { body } = (await request.json()) as { body: string };
    if (!body?.trim()) return validationFailed({ body: ['must not be blank'] });
    if (m.isDeleted) {
      return problem(409, 'chat-message-immutable', 'the message was deleted');
    }
    if (Date.now() - Date.parse(m.createdAt) > 5 * 60_000) {
      return problem(409, 'chat-message-immutable', 'the five-minute edit window has closed');
    }

    m.body = body;
    m.isEdited = true;
    return ok(messageDto(m));
  }),

  /** D-057. A tombstone: the row survives, the words are withheld on read. */
  http.delete(url('/chat/threads/:threadId/messages/:messageId'), ({ params }) => {
    const db = getDb();
    const m = db.chatMessages.find(
      (x) => x.id === Number(params.messageId) && x.threadId === Number(params.threadId),
    );
    if (!m || m.authorId !== db.currentUserId) return notFound('Message');

    // Idempotent, and deliberately not time-limited: the window stops anyone
    // rewriting what was said, while a tombstone adds to the record.
    m.isDeleted = true;
    return ok(messageDto(m));
  }),

  // ── webhooks & audit ──────────────────────────────────────────────────────
  http.post(url('/webhooks/email/inbound'), ({ request }) =>
    request.headers.get('X-Webhook-Signature')
      ? new HttpResponse(null, { status: 202 })
      : problem(401, 'bad-signature', 'Webhook signature missing or invalid'),
  ),
  http.post(url('/webhooks/email/bounce'), ({ request }) =>
    request.headers.get('X-Webhook-Signature')
      ? new HttpResponse(null, { status: 202 })
      : problem(401, 'bad-signature', 'Webhook signature missing or invalid'),
  ),
  http.get(url('/audit-logs'), ({ request }) => {
    const db = getDb();
    // 403 is legitimate here: the failure does not depend on any row existing.
    if (currentUser(db).role !== 'ADMIN') {
      return problem(403, 'forbidden', 'The audit log is Admin only');
    }
    const rows = db.history.map((h) => ({
      id: h.id, actor: userRef(h.actorId, db), action: h.action,
      entityType: 'TICKET', entityId: h.ticketId,
      ipAddress: '10.0.0.1', userAgent: 'mock', detail: {}, createdAt: h.createdAt,
    }));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),
];

// ── users: filtering and export ─────────────────────────────────────────────

/**
 * B-010 · the S-07 filters, in one place.
 *
 * The grid and the export share it deliberately. An export that built its
 * filter separately is an export that eventually disagrees with the screen
 * above it, and nobody notices until the numbers are in a board pack.
 */
function filteredUsers(q: URLSearchParams) {
  let rows = getDb().users;

  const text = q.get('q')?.toLowerCase();
  if (text) {
    rows = rows.filter((u) =>
      [u.displayName, u.username, u.email, u.employeeCode].some((f) => f.toLowerCase().includes(text)),
    );
  }
  if (q.get('role')) rows = rows.filter((u) => u.role === q.get('role'));
  if (q.get('isActive')) rows = rows.filter((u) => String(u.isActive) === q.get('isActive'));
  if (q.get('projectId')) rows = rows.filter((u) => u.projectIds.includes(Number(q.get('projectId'))));
  if (q.get('managerId')) {
    rows = rows.filter((u) => u.reportingManagerId === Number(q.get('managerId')));
  }
  return [...rows].sort((a, b) => a.displayName.localeCompare(b.displayName));
}

/**
 * A downloadable file, close enough to prove the button works.
 *
 * CSV in both cases — the mock cannot produce a real xlsx, and pretending to
 * would give the browser a corrupt file, which is a worse lie than a `.csv`
 * whose `Content-Disposition` says what it is. The real engine is SXSSF
 * (`ResourceExportWriter`).
 */
function usersExport(rows: import('../db').User[], format: 'xlsx' | 'csv') {
  const db = getDb();
  const header = [
    'Emp Code', 'Name', 'Username', 'Email', 'Role', 'Department', 'Designation',
    'Reporting Manager', 'Projects', 'Status', 'Open Tickets', 'Last Login (UTC)',
  ];
  const cells = (u: import('../db').User) => [
    u.employeeCode, u.displayName, u.username, u.email, u.role,
    u.department ?? '', u.designation ?? '',
    userRef(u.reportingManagerId, db)?.displayName ?? '',
    u.projectIds.map((id) => projectRef(id, db)?.name).filter(Boolean).join(', '),
    u.isActive ? 'Active' : 'Inactive',
    String(db.tickets.filter((t) => t.assigneeId === u.id && t.status !== 'CLOSED').length),
    u.lastLoginAt ?? '',
  ];

  const body = [header, ...rows.map(cells)]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\r\n');

  // Leading BOM, built from its code point rather than pasted in as an
  // invisible literal: Excel reads a BOM-less UTF-8 CSV in the system codepage
  // and mangles every non-ASCII name.
  return new HttpResponse(String.fromCharCode(0xfeff) + body, {
    headers: {
      'Content-Type': 'application/octet-stream',
      'Content-Disposition': `attachment; filename="resources-2026-08-11.${format}"`,
    },
  });
}

// ── mappers ─────────────────────────────────────────────────────────────────
function userDto(u: User) {
  const db = getDb();
  return {
    id: u.id, displayName: u.displayName, avatarUrl: u.avatarUrl, role: u.role,
    username: u.username, email: u.email, employeeCode: u.employeeCode,
    department: u.department, designation: u.designation,
    reportingManager: userRef(u.reportingManagerId, db),
    projectIds: u.projectIds,
    // B-010: names as well as ids, so the S-07 grid renders a Projects column
    // without a lookup per row.
    projects: u.projectIds.map((id) => projectRef(id, db)).filter(Boolean),
    isActive: u.isActive,
    openTicketCount: db.tickets.filter((t) => t.assigneeId === u.id && t.status !== 'CLOSED').length,
    lastLoginAt: u.lastLoginAt,
    createdAt: '2026-08-03T09:00:00.000Z',
  };
}

/**
 * B-011 · `UserDetail` — everything `userDto` returns plus the S-08-only half.
 *
 * Deliberately **not** merged into `userDto`. `listUsers` returns up to 200 of
 * those, and a skills array plus a membership list on every grid row is weight
 * paid on every page of a screen that shows neither.
 */
function userDetailDto(u: User) {
  return {
    ...userDto(u),
    mobile: u.mobile,
    dateOfJoining: u.dateOfJoining,
    location: u.location,
    timezone: u.timezone,
    dailyCapacityHrs: u.dailyCapacityHrs,
    weeklyOff: u.weeklyOff,
    skills: u.skills,
    projectAssignments: u.projectIds.map((projectId: number) => ({
      projectId,
      // `undefined` rather than null: the generated Zod types this
      // `.optional()`, so an explicit null is a value the frontend's own schema
      // rejects. Same reason `ResourceDtos` carries `@JsonInclude(NON_NULL)`.
      ...(u.projectRoles[projectId] ? { roleInProject: u.projectRoles[projectId] } : {}),
    })),
    mustChangePassword: u.mustChangePassword,
  };
}

/**
 * The `ETag`, derived from content rather than a version counter.
 *
 * The same three exclusions `ResourceDetail.etag()` makes, for the same reason:
 * `openTicketCount`, `lastLoginAt` and `mustChangePassword` all move without
 * anybody editing this resource, and any of them inside the tag would produce a
 * 412 naming a conflict that does not exist. The tag covers what the `PATCH` can
 * change, and no more.
 */
function userEtag(u: User) {
  const stable = JSON.stringify({
    ...userDetailDto(u),
    openTicketCount: undefined,
    lastLoginAt: undefined,
    mustChangePassword: undefined,
  });
  let hash = 0;
  for (let i = 0; i < stable.length; i++) {
    hash = (hash * 31 + stable.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

/**
 * The three unique fields, all reported at once.
 *
 * One round of correction rather than three: an admin who fixes a duplicate
 * username should not then discover the duplicate email.
 */
function userConflicts(users: User[], body: Record<string, unknown>, excludeId: number | null) {
  const errors: Record<string, string[]> = {};
  const clash = (field: 'username' | 'email' | 'employeeCode', message: string) => {
    const candidate = String(body[field] ?? '').toLowerCase();
    if (candidate === '') return;
    const taken = users.some((u) => u.id !== excludeId && String(u[field]).toLowerCase() === candidate);
    if (taken) errors[field] = [message];
  };

  clash('username', 'That username is already taken');
  clash('email', 'That email address is already registered');
  clash('employeeCode', 'That employee code is already in use');

  if (Object.keys(errors).length === 0) return null;
  return problem(409, 'duplicate', Object.values(errors).flat().join('; '), { errors });
}

/**
 * Replaces the membership set when the key is present, leaves it alone when it
 * is absent — the contract's "sent whole, not as a delta".
 */
function applyProjects(u: User, projects: unknown) {
  if (projects == null) return;
  const rows = projects as Array<{ projectId: number; roleInProject?: string }>;
  u.projectIds = rows.map((row) => row.projectId);
  u.projectRoles = Object.fromEntries(
    rows.map((row) => [row.projectId, (row.roleInProject as never) ?? null]),
  );
}

/**
 * B-016 · one row of the S-10 grid.
 *
 * **`isActive` is derived, not stored, and it is `status !== 'CLOSED'`.** Five
 * screens send `?isActive=true` to fill a picker; deriving it from
 * `status === 'ACTIVE'` would make putting a project on hold silently remove it
 * from the create-ticket form. The server does the same thing for the same
 * reason — `ProjectService.isActive`.
 *
 * `startDate` and `endDate` used to be hardcoded `null` here, which is why
 * nothing noticed the columns had no reader until this task.
 */
function projectDto(p: import('../db').Project) {
  return {
    id: p.id, projectCode: p.projectCode, name: p.name, clientName: p.clientName,
    projectManager: userRef(p.projectManagerId), colourTag: p.colourTag,
    startDate: p.startDate, endDate: p.endDate,
    status: p.status, isActive: p.status !== 'CLOSED',
    autoAssignRule: p.autoAssignRule,
  };
}

/**
 * The edit form's read. `ticketsIssued` is what makes `projectCode` immutable,
 * so the form disables the input and says why rather than letting the refusal
 * arrive on save.
 */
function projectDetailDto(p: import('../db').Project) {
  return { ...projectDto(p), description: p.description, ticketsIssued: p.ticketSeq };
}

/**
 * B-017 · one roster row.
 *
 * Carries both roles, because the tab's job is showing where they differ: a
 * Developer mapped as QA on this project is the case `role_in_project` exists
 * for, and a row showing only one of the two cannot express it.
 *
 * `isActive` is the **resource's** status, not the membership's. A leaver still
 * on a team is exactly what the tab has to surface, since that is what B-014's
 * reassignment flow exists to clear up.
 */
function projectMemberDto(u: User, projectId: number) {
  return {
    userId: u.id,
    displayName: u.displayName,
    email: u.email,
    role: u.role,
    projectRole: u.projectRoles[projectId] ?? null,
    // `?? null`, never `?? 100`. A membership with no stated allocation is not
    // a fully-committed one, and nothing downstream can tell an invented
    // default from a real figure.
    allocationPct: u.projectAllocations[projectId] ?? null,
    isActive: u.isActive,
    openTicketCount: openTicketsOnProject(u.id, projectId),
    addedAt: u.projectMemberSince[projectId] ?? '2026-01-01T09:00:00.000Z',
  };
}

/**
 * Open tickets this person holds **on this project**.
 *
 * Scoped to the project because the removal refusal is: work held elsewhere is
 * not a fact this screen can act on, and refusing a removal because of it would
 * send an admin to reassign tickets that are none of this project's business.
 */
function openTicketsOnProject(userId: number, projectId: number) {
  return getDb().tickets.filter(
    (t) => t.assigneeId === userId && t.projectId === projectId && t.status !== 'CLOSED',
  ).length;
}

/**
 * Content-derived, like the server's — a timestamp tag moves when a save
 * rewrites identical values, failing an edit that conflicts with nothing.
 * `ticketsIssued` is in the hash on purpose: a ticket allocated while the form
 * is open is precisely the event that fixes the code.
 */
function projectEtag(p: import('../db').Project) {
  return `"${JSON.stringify(projectDetailDto(p)).length.toString(16)}-${p.ticketSeq}-${p.projectCode}"`;
}

function clientDto(c: import('../db').Client) {
  const db = getDb();
  return {
    ...clientRef(c.id, db),
    domain: c.domain, accountManager: userRef(c.accountManagerId, db),
    supportPlan: c.supportPlan, slaPolicyId: null, timezone: c.timezone,
    isActive: c.isActive,
    openTicketCount: db.tickets.filter((t) => t.clientId === c.id && t.status !== 'CLOSED').length,
    primaryContact: db.contacts.find((x) => x.clientId === c.id && x.isPrimary) ?? null,
  };
}

/**
 * D-054 · the ticket codes a body names.
 *
 * The same pattern the server uses — `{PROJECT}-{YY}-{NNNNN}` from C-011, not
 * the blueprint's illustrative `TKT-xxxx`, which matches nothing real — with the
 * same lookarounds, so a code inside `builds/CRM-26-00347-final.zip` is a
 * filename here too. A mock that were more generous would have the UI rendering
 * cards that vanish against the real server.
 */
const TICKET_CODE = /(?<![A-Za-z0-9-])([A-Z][A-Z0-9]{1,9}-\d{2}-\d{5})(?![A-Za-z0-9-])/g;
const MAX_REFS = 10;

export function ticketCodesIn(body: string | null | undefined): string[] {
  if (!body) return [];
  return [...new Set(body.match(TICKET_CODE) ?? [])].slice(0, MAX_REFS);
}

/**
 * D-054 · cards for the codes a reader may actually see.
 *
 * Goes through `scopedTickets`, which is the mock's stand-in for A-034 — so a
 * code naming a ticket outside the current user's scope produces no card and
 * stays plain text, exactly as on the server. A mock that resolved every code
 * would teach the UI that a reference always unfurls.
 */
export function ticketCardsFor(codes: string[], db = getDb()) {
  const visible = scopedTickets(db);
  return codes
    .map((code) => visible.find((t) => t.ticketId === code))
    .filter((t): t is NonNullable<typeof t> => t != null)
    .map((t) => ({
      ticketId: t.ticketId, title: t.title, level: t.level, status: t.status,
      currentStageCode: t.currentStageCode,
      assignee: t.assigneeId == null ? null : userRef(t.assigneeId, db),
      plannedCloseDate: t.plannedCloseDate, isDelayed: t.isDelayed,
    }));
}

function messageDto(m: import('../db').ChatMessage) {
  return {
    // The tombstone: the row is still here, the words are not (D-057). A mock
    // that kept returning the body would let the UI render deleted messages
    // and only break against the real server.
    id: m.id, body: m.isDeleted ? null : m.body, author: userRef(m.authorId), kind: m.kind,
    isEdited: m.isEdited, isDeleted: m.isDeleted,
    editableUntil: new Date(Date.parse(m.createdAt) + 5 * 60_000).toISOString(),
    attachments: [], readBy: m.readBy,
    // Resolved per read against the caller's scope, never stored on the row —
    // and always empty on a tombstone, whose body named nothing the reader can
    // still see.
    ticketRefs: m.isDeleted ? [] : ticketCardsFor(ticketCodesIn(m.body)),
    createdAt: m.createdAt,
  };
}

/** Detect a reporting cycle at any depth, not just self-reference. */
function createsCycle(userId: number, newManagerId: number): boolean {
  const db = getDb();
  let cursor: number | null = newManagerId;
  const seen = new Set<number>();
  while (cursor != null && !seen.has(cursor)) {
    if (cursor === userId) return true;
    seen.add(cursor);
    cursor = db.users.find((u) => u.id === cursor)?.reportingManagerId ?? null;
  }
  return false;
}
