import { http, HttpResponse } from 'msw';
import { isWellFormedEmail } from '@/lib/email';
import { getDb, nextId } from '../db';
import type {
  Db, Holiday, Level, NotificationChannelCode, NotificationTemplateRow, Priority,
  ProjectRoleCode, Role, TaskType, User,
} from '../db';
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

/**
 * D-036 — the same rule as `NotificationEvent.isMandatoryMail()`.
 *
 * **`STATUS_REQUEST` was missing here and the server has always included it.**
 * D-055 found the gap on the Java side: §4B.6's prose sentence lists four kinds
 * of mandatory mail, and its table — the precise version — also marks "Status
 * requested by manager" as ❌ never optional. `NotificationEvent.isMandatoryMail`
 * covers all three categories; this helper covered two, so the mock rendered the
 * status-request email preference as unlocked while the server locks it.
 * Nothing failed, because no test asserted that row. **Flagged for Stream D** —
 * one line in their file, corrected here because B-022's template handlers need
 * the same rule and two versions of it in one file is how the next one drifts.
 */
const isMandatoryMail = (category: string) =>
  category === 'ASSIGNMENT' || category === 'ESCALATION' || category === 'STATUS_REQUEST';

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

// ── task types · S-11 (B-020) ───────────────────────────────────────────────

/**
 * `defaultLevel` must be one of the four `CONTRACT_LEVELS` — declared with the
 * priority handlers below, and shared rather than restated here.
 *
 * The real `TaskTypeService` checks the priority master first and the contract
 * enum second, and says which of the two refused. The mock keeps the second
 * half: it is a property of the contract rather than of the data, and it is the
 * half a client can be surprised by.
 */
function levelRefusal(level: string) {
  if (!(CONTRACT_LEVELS as readonly string[]).includes(level)) {
    return {
      value: level,
      refusal: validationFailed({
        defaultLevel: [`No such level: '${level}'. Levels come from the priority master (S-12).`],
      }),
    };
  }
  return { value: level, refusal: null };
}

/**
 * One pass over the tickets for the whole grid, not one per row.
 *
 * The real service does the same thing as a single grouped statement, for the
 * same reason: twelve task types is exactly the size at which an N+1 is
 * invisible, which is how one survives to a table that is not twelve rows.
 */
const ticketCountsByTaskType = () => {
  const counts = new Map<number, number>();
  for (const ticket of getDb().tickets) {
    if (ticket.taskTypeId != null) {
      counts.set(ticket.taskTypeId, (counts.get(ticket.taskTypeId) ?? 0) + 1);
    }
  }
  return counts;
};

/** `ticketCount` is derived, so the mock cannot drift from its own ticket list. */
const taskTypeDto = (type: TaskType, counts = ticketCountsByTaskType()) => ({
  ...type,
  ticketCount: counts.get(type.id) ?? 0,
});

/**
 * Content-derived, and it covers `ticketCount` — so a ticket raised while the
 * edit dialog is open costs a reload, which is correct: that count is what the
 * deactivate decision was made against.
 */
const taskTypeEtag = (type: TaskType) =>
  `"${Math.abs([...JSON.stringify(taskTypeDto(type))]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * The mock enforces `If-Match` too. A guard the real backend has and the mock
 * waves through is a guard the frontend never gets to exercise.
 */
function taskTypePrecondition(type: TaskType, ifMatch: string | null) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the task type first and send back its ETag.');
  }
  if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== taskTypeEtag(type).replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'This task type changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

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

// ── notification templates · S-15 (B-022) ───────────────────────────────────

/** D-042's three, mirroring `NotificationChannel`. */
const NOTIFICATION_CHANNELS: NotificationChannelCode[] = ['IN_APP', 'EMAIL', 'PUSH'];

/**
 * §11's "To" column, mirroring `NotificationRecipient`.
 *
 * Copied here in full rather than sliced the way `NOTIFICATION_EVENTS` is,
 * because this list is what the S-15 form's multi-select renders — a slice
 * would make the mock refuse a recipient the server accepts, and the screen
 * would be built around the refusal.
 */
const NOTIFICATION_RECIPIENTS = [
  'ASSIGNEE', 'STAGE_OWNER', 'PREVIOUS_ASSIGNEE', 'REPORTER', 'PROJECT_MANAGER',
  'REPORTING_MANAGER', 'SUPPORT_DESK', 'WATCHERS', 'MENTIONED_USER', 'CLIENT_CONTACT',
  'REQUESTER', 'ALL_USERS', 'ADMIN',
];

/** Mirrors `MergeTag`. Blueprint §4B.6's five are the first five. */
const MERGE_TAGS = [
  'ticket_id', 'assignee', 'stage', 'client', 'planned_close',
  'ticket_title', 'ticket_url', 'project', 'level', 'status',
  'actor', 'recipient', 'comment', 'iteration', 'cycle',
  'overdue_by', 'sla_due', 'org',
];

/** `{{ name }}` — braces doubled, inner whitespace tolerated, name captured.
 *  The same pattern `MergeTag.PLACEHOLDER` compiles, for the same reason: a
 *  paste from a document produces `{{ ticket_id }}` and refusing it would be a
 *  refusal about spacing rather than about spelling. */
const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_]+)\s*}}/g;

/**
 * `isMandatory` is computed here, exactly as the server derives it, rather
 * than stored on the fixture row — otherwise the mock and the server could
 * disagree about which templates are locked, and the mock is the one the
 * screen's tests would believe.
 */
const templateDto = (template: NotificationTemplateRow) => ({
  ...template,
  isMandatory: template.channel === 'EMAIL' && isMandatoryMail(template.category),
});

const templateEtag = (template: NotificationTemplateRow) =>
  `"${Math.abs([...JSON.stringify(templateDto(template))]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/** The mock enforces `If-Match` too — a guard the real backend has and the mock
 *  waves through is a guard the frontend never gets to exercise. */
function templatePrecondition(template: NotificationTemplateRow, ifMatch: string | null) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the template first and send back its ETag.');
  }
  if (ifMatch !== '*'
      && ifMatch.replace(/W\/|"/g, '') !== templateEtag(template).replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'This template changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

/**
 * §4B.6's never-optional mail, being switched off.
 *
 * Mirrored from `NotificationTemplateService.guardMandatory` so the S-15 form
 * can be built against it. A mock that let the toggle through would let the
 * screen ship with no handling for the one refusal it will actually meet — and
 * the refusal is the whole point of the rule.
 */
function mandatoryProblem(category: string, channel: NotificationChannelCode) {
  if (channel !== 'EMAIL' || !isMandatoryMail(category)) return null;
  const detail = 'This mail cannot be switched off. Blueprint §4B.6 marks assignment, handoff, '
    + 'escalation and status-request mail as never optional, and D-036 already stops an '
    + 'individual user muting it — switching the template off here would silence it for '
    + 'everybody at once. The in-app template for this event can be switched off.';
  return problem(409, 'mandatory-notification', 'This mail cannot be switched off',
    { detail, errors: { isActive: [detail] } });
}

/** A placeholder that resolves to nothing would print literal braces in a
 *  client-facing mail, and the admin who typed it would never find out. */
function mergeTagProblem(subject: string | null, body: string) {
  const unknown = [...new Set(
    [...`${subject ?? ''}\n${body}`.matchAll(PLACEHOLDER)]
      .map((match) => match[1])
      .filter((tag) => !MERGE_TAGS.includes(tag)),
  )];
  if (unknown.length === 0) return null;
  const detail = unknown.length === 1
    ? `{{${unknown[0]}}} is not a merge tag, so it would be printed literally — braces `
      + 'included — in every notification this template renders.'
    : 'These are not merge tags and would be printed literally, braces included, in every '
      + `notification this template renders: ${unknown.map((t) => `{{${t}}}`).join(', ')}`;
  return problem(400, 'unknown-merge-tag', 'Unknown merge tag',
    { detail, unknownTags: unknown, knownTags: MERGE_TAGS, errors: { bodyTemplate: [detail] } });
}

/** A mail with no subject line is unsendable. The other two channels do not
 *  require one and are not refused one — a push has a title as well as a body. */
function subjectProblem(channel: NotificationChannelCode, subject: string | null) {
  if (channel !== 'EMAIL' || (subject && subject.trim())) return null;
  return validationFailed({
    subjectTemplate: ['An email template needs a subject line. The ticket code is prefixed by '
      + 'the sender, so write what happened rather than the code itself.'],
  });
}

function recipientProblem(recipients: string[]) {
  if (recipients.length === 0) {
    return validationFailed({
      recipients: ['Name at least one recipient. A template with none is a row that looks '
        + 'configured and sends nothing — switch it off instead, which says so.'],
    });
  }
  const unknown = recipients.filter((r) => !NOTIFICATION_RECIPIENTS.includes(r));
  if (unknown.length === 0) return null;
  return validationFailed({
    recipients: [`Not a recipient this system can resolve: ${unknown.join(', ')}. These are `
      + "positions relative to a ticket rather than roles — 'PROJECT_MANAGER' means the PM of "
      + "this ticket's project, not everybody holding the PM role."],
  });
}

// ── priorities · S-12 (B-021) ───────────────────────────────────────────────

/**
 * The four the contract's `Level` can carry — mirrors `PriorityService`.
 *
 * Shared with the task type handlers above, which refuse a `defaultLevel`
 * outside this set for the same reason. Two copies would be two things to keep
 * in step on the day `Level` finally opens, and that day needs exactly one edit
 * here.
 */
const CONTRACT_LEVELS: Level[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/**
 * The three usage counts, derived rather than stored.
 *
 * Each one keys on the level **code** against another collection, exactly as
 * the server's SQL keys on a `VARCHAR` rather than joining `priorities.id` —
 * a fixture that joined on the id would make the screen look right against
 * data that cannot exist.
 *
 * `taskTypeCount` counts only **active** types, because a retired one cannot
 * fail its own validation and so cannot block a retire.
 */
const priorityDto = (priority: Priority) => {
  const db = getDb();
  return {
    ...priority,
    ticketCount: db.tickets.filter((t) => t.level === priority.level).length,
    taskTypeCount: db.taskTypes.filter(
      (t) => t.isActive && t.defaultLevel === priority.level).length,
    slaPolicyCount: db.slaPolicies.filter((p) => p.level === priority.level).length,
  };
};

/**
 * Content-derived, and it covers the usage counts — so a ticket raised at this
 * level while the dialog is open costs a reload, which is correct: those counts
 * are what the retire decision was made against.
 */
const priorityEtag = (priority: Priority) =>
  `"${Math.abs([...JSON.stringify(priorityDto(priority))]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * The mock enforces `If-Match` too. A guard the real backend has and the mock
 * waves through is a guard the frontend never gets to exercise.
 */
function priorityPrecondition(priority: Priority, ifMatch: string | null) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the level first and send back its ETag.');
  }
  if (ifMatch !== '*'
      && ifMatch.replace(/W\/|"/g, '') !== priorityEtag(priority).replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'This level changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

/**
 * §6's pointer is missing, ambiguous or retired.
 *
 * Its own problem `type`, not folded into `in-use`: the remedy is a single
 * sentence the screen can act on — move the flag, then retry — where `in-use`
 * would send the admin looking for references that do not exist.
 */
const escalationTargetProblem = (detail: string, field: 'isActive' | 'autoEscalates') =>
  problem(409, 'escalation-target-required',
    'The SLA engine needs exactly one escalation target',
    { detail, errors: { [field]: [detail] } });

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

  // Active types only, which is what `SlaMatrixService.activeTaskTypes` does —
  // and is the consequence B-020's Task Type Master has to state out loud,
  // because retiring a type silently removes a row from every project's SLA
  // tab. The mock had no retired type to disagree over until B-020 added one.
  return db.taskTypes.filter((tt) => tt.isActive).flatMap((tt) =>
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
    // B-026 · derived from `status`, and it is the derivation the server uses:
    // `status !== 'INACTIVE'`, so `?isActive=true` returns prospects too.
    if (q.get('isActive')) {
      rows = rows.filter((c) => String(c.status !== 'INACTIVE') === q.get('isActive'));
    }
    // B-025 · S-32's remaining three filters. `projectId` reads the mapping
    // table rather than the project's `clientName`, because the name is a label
    // and the mapping is the relationship.
    const projectId = q.get('projectId');
    if (projectId) {
      const mapped = new Set(
        db.clientProjects.filter((cp) => cp.projectId === Number(projectId)).map((cp) => cp.clientId),
      );
      rows = rows.filter((c) => mapped.has(c.id));
    }
    const supportPlan = q.get('supportPlan');
    // Case-insensitive, matching what `utf8mb4_0900_ai_ci` does server-side —
    // a mock that is stricter than the database teaches the wrong lesson.
    if (supportPlan) {
      rows = rows.filter(
        (c) => (c.supportPlan ?? '').toLowerCase() === supportPlan.toLowerCase(),
      );
    }
    const accountManagerId = q.get('accountManagerId');
    if (accountManagerId) {
      rows = rows.filter((c) => c.accountManagerId === Number(accountManagerId));
    }
    // Ordered by name then id, the keyset the server pages by. Without it the
    // mock pages in insertion order and a screen that depends on the ordering
    // passes here and fails against the real backend.
    rows = [...rows].sort((a, b) => a.name.localeCompare(b.name) || a.id - b.id);
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(clientDto), meta);
  }),
  // Declared before `/clients/:clientId/status` so MSW matches the literal
  // first — msw resolves handlers in array order, unlike Spring and React
  // Router, which rank a literal above a variable however they are written.
  http.patch(url('/clients/bulk-status'), async ({ request }) => {
    const db = getDb();
    const { clientIds, isActive } = (await request.json()) as {
      clientIds: number[]
      isActive: boolean
    };
    if (!Array.isArray(clientIds) || clientIds.length === 0) {
      return validationFailed({ clientIds: ['must name at least one client'] });
    }
    // One unknown id fails the whole batch and writes nothing — the server's
    // rule, mirrored, because a mock that silently skips would let a screen
    // ship believing partial success is reported.
    const ids = [...new Set(clientIds)];
    const missing = ids.filter((id) => !db.clients.some((c) => c.id === id));
    if (missing.length > 0) {
      return problem(404, 'not-found', `These clients do not exist: ${missing.join(', ')}.`);
    }
    const changed = db.clients.filter((c) => ids.includes(c.id));
    // Per row, not one status for the batch: a selection containing prospects
    // must come back with those prospects intact. ClientStatus.activatedFrom.
    changed.forEach((c) => {
      c.status = !isActive ? 'INACTIVE' : (c.status !== 'INACTIVE' ? c.status : 'ACTIVE');
    });
    return ok(changed.map(clientDto));
  }),
  // B-026 · S-33's read. Emits the ETag, which is the only place the PATCH
  // below can obtain the `If-Match` it requires.
  http.get(url('/clients/:clientId'), ({ params }) => {
    const c = getDb().clients.find((x) => x.id === Number(params.clientId));
    if (!c) return notFound('Client');
    return ok(clientDetailDto(c), undefined, { headers: { ETag: clientEtag(c) } });
  }),
  http.post(url('/clients'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, unknown>;

    const errors = validateClientWrite(body, null);
    if (Object.keys(errors).length > 0) {
      // 409 when a duplicate code is the ONLY failure, 400 otherwise — the
      // server's rule, mirrored, because a client branching on the status
      // would otherwise handle a mixed failure as a uniqueness conflict and
      // never show the other message.
      return clientWriteProblem(errors);
    }

    const c: import('../db').Client = {
      id: Math.max(0, ...db.clients.map((x) => x.id)) + 1,
      clientCode: '', name: '', domain: '', accountManagerId: null,
      supportPlan: null, timezone: 'Asia/Kolkata', status: 'ACTIVE',
    };
    applyClientWrite(c, body);
    db.clients.push(c);
    applyClientProjects(c.id, body);

    return ok(clientDetailDto(c), undefined, {
      status: 201,
      headers: { ETag: clientEtag(c) },
    });
  }),
  http.patch(url('/clients/:clientId'), async ({ params, request }) => {
    const db = getDb();
    const c = db.clients.find((x) => x.id === Number(params.clientId));
    // 404 before the precondition: answering 428 for a client that does not
    // exist sends the caller to fetch a tag from a URL that will 404 too.
    if (!c) return notFound('Client');

    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the client first and send back its ETag.');
    }
    if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== clientEtag(c).replace(/"/g, '')) {
      return problem(412, 'precondition-failed',
        'This client changed since you read it. Reload and reapply your edit.');
    }

    const body = (await request.json()) as Record<string, unknown>;
    const errors = validateClientWrite(body, c.id);
    if (Object.keys(errors).length > 0) {
      return clientWriteProblem(errors);
    }

    applyClientWrite(c, body);
    applyClientProjects(c.id, body);
    return ok(clientDetailDto(c), undefined, { headers: { ETag: clientEtag(c) } });
  }),
  http.patch(url('/clients/:clientId/status'), async ({ params, request }) => {
    const db = getDb();
    const c = db.clients.find((x) => x.id === Number(params.clientId));
    if (!c) return notFound('Client');
    const { isActive } = (await request.json()) as { isActive: boolean };
    // B-026 · a Prospect is already active by the `status !== 'INACTIVE'`
    // projection, so activating one leaves it a Prospect. Writing 'ACTIVE'
    // anyway would let the grid's bulk Activate promote a shortlist of
    // prospects into contracted clients. Mirrors ClientStatus.activatedFrom.
    c.status = !isActive ? 'INACTIVE' : (c.status !== 'INACTIVE' ? c.status : 'ACTIVE');
    // Deactivating warns and blocks NEW tickets — it never hides historical ones.
    return ok(clientDto(c));
  }),
  // B-027 · the child grid. `includeInactive` defaults to false, and honouring
  // that default in the mock is the point: a removed contact must stop being
  // offered on the ticket create form, and a mock that returned everything
  // would let that regression ship.
  http.get(url('/clients/:clientId/contacts'), ({ params, request }) => {
    const db = getDb();
    const clientId = Number(params.clientId);
    if (!db.clients.some((c) => c.id === clientId)) return notFound('Client');
    const includeInactive =
      new URL(request.url).searchParams.get('includeInactive') === 'true';
    const rows = db.contacts
      .filter((x) => x.clientId === clientId && (includeInactive || x.isActive))
      // The server's ORDER BY: live before removed, primary first, then name.
      .sort(
        (a, b) =>
          Number(b.isActive) - Number(a.isActive) ||
          Number(b.isPrimary) - Number(a.isPrimary) ||
          a.name.localeCompare(b.name) ||
          a.id - b.id,
      );
    return ok(rows);
  }),
  http.post(url('/clients/:clientId/contacts'), async ({ params, request }) => {
    const db = getDb();
    const clientId = Number(params.clientId);
    if (!db.clients.some((c) => c.id === clientId)) return notFound('Client');

    const body = (await request.json()) as Record<string, unknown>;
    const failure = validateContactWrite(db, clientId, body, null);
    if (failure) return failure;

    const c: import('../db').Contact = {
      id: Math.max(0, ...db.contacts.map((x) => x.id)) + 1,
      clientId,
      name: String(body.name).trim(),
      designation: trimOrNull(body.designation),
      email: trimOrNull(body.email),
      phone: String(body.phone ?? '').trim(),
      isPrimary: body.isPrimary === true,
      // The one default that is not false — §11 names the client contact on
      // the mails a client is meant to receive.
      notificationOptIn: body.notificationOptIn !== false,
      portalAccess: body.portalAccess === true,
      isActive: true,
    };
    db.contacts.push(c);
    // Insert first, demote second — the server's ordering, so `exceptId` is a
    // real id rather than a sentinel meaning "nothing".
    if (c.isPrimary) demoteOtherPrimaries(db, clientId, c.id);
    return ok(c, undefined, { status: 201 });
  }),
  http.patch(url('/clients/:clientId/contacts/:contactId'), async ({ params, request }) => {
    const db = getDb();
    const clientId = Number(params.clientId);
    const contactId = Number(params.contactId);
    // Scoped to the client: a contact id under a *different* client is 404,
    // not an edit that silently lands on somebody else's row.
    const c = db.contacts.find((x) => x.id === contactId && x.clientId === clientId);
    if (!c) return notFound('Contact');

    const body = (await request.json()) as Record<string, unknown>;
    const failure = validateContactWrite(db, clientId, body, contactId);
    if (failure) return failure;

    // The whole representation — an absent field is a cleared field. `isActive`
    // is deliberately not written: an edit must not resurrect a removed
    // contact, which is the two-writers rule B-017 had to pin on
    // `project_members` and which has its own test here.
    c.name = String(body.name).trim();
    c.designation = trimOrNull(body.designation);
    c.email = trimOrNull(body.email);
    c.phone = String(body.phone ?? '').trim();
    c.isPrimary = body.isPrimary === true;
    c.notificationOptIn = body.notificationOptIn !== false;
    c.portalAccess = body.portalAccess === true;

    if (c.isPrimary) demoteOtherPrimaries(db, clientId, c.id);
    return ok(c);
  }),
  http.delete(url('/clients/:clientId/contacts/:contactId'), ({ params }) => {
    const db = getDb();
    const clientId = Number(params.clientId);
    const c = db.contacts.find(
      (x) => x.id === Number(params.contactId) && x.clientId === clientId,
    );
    if (!c) return notFound('Contact');
    // Deactivates, never deletes — `tickets.client_contact_id` points here.
    // `is_primary` is cleared with it: a removed contact must not stay starred,
    // or the grid shows a primary who has left.
    c.isActive = false;
    c.isPrimary = false;
    // 204 whether or not it was already removed. B-014's UNCHANGED argument:
    // the second half of a double-click is not an error.
    return new HttpResponse(null, { status: 204 });
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
  // ── task types · S-11 (B-020) ─────────────────────────────────────────────
  // Inactive rows included, in `seq` order — a picker filters them out, a grid
  // still has to name the type an old ticket was raised against.
  http.get(url('/masters/task-types'), () => {
    const counts = ticketCountsByTaskType();
    return ok(
      [...getDb().taskTypes]
        .sort((a, b) => a.seq - b.seq || a.id - b.id)
        .map((type) => taskTypeDto(type, counts)),
    );
  }),

  http.post(url('/masters/task-types'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, unknown>;
    const code = String(body.code ?? '').trim().toUpperCase();
    const name = String(body.name ?? '').trim();

    if (db.taskTypes.some((t) => t.code === code)) {
      // The message is repeated into `errors` rather than abbreviated there,
      // because that is what the real handler does — it keys the whole sentence
      // to the field so the form can render it on the input. A mock that put a
      // shorter string here would let a screen ship with an error message
      // nobody had ever actually read.
      const detail = `A task type with code '${code}' already exists.`;
      return problem(409, 'duplicate', 'Duplicate task type', {
        detail,
        errors: { code: [detail] },
      });
    }
    // There is no uq_task_types_name, so this rule exists only in the service —
    // and it is the one protecting ticketForm.ts's §4B.2 rule, which matches on
    // the display name.
    const clash = db.taskTypes.find((t) => t.name.toLowerCase() === name.toLowerCase());
    if (clash) {
      const detail = `'${clash.name}' already exists. Two task types with the same name are indistinguishable in every picker that renders them.`;
      return problem(409, 'duplicate', 'Duplicate task type', {
        detail,
        errors: { name: [detail] },
      });
    }
    const level = levelRefusal(String(body.defaultLevel ?? '').trim().toUpperCase());
    if (level.refusal) return level.refusal;

    const created: TaskType = {
      id: Math.max(0, ...db.taskTypes.map((t) => t.id)) + 1,
      code,
      name,
      icon: (body.icon as string | null) || null,
      colour: String(body.colour ?? ''),
      defaultLevel: level.value as Level,
      defaultSlaHrs: body.defaultSlaHrs == null ? null : Number(body.defaultSlaHrs),
      seq: body.seq == null ? Math.max(0, ...db.taskTypes.map((t) => t.seq)) + 10 : Number(body.seq),
      isActive: body.isActive == null ? true : Boolean(body.isActive),
    };
    db.taskTypes.push(created);
    return ok(taskTypeDto(created), undefined,
      { status: 201, headers: { ETag: taskTypeEtag(created) } });
  }),

  http.get(url('/masters/task-types/:taskTypeId'), ({ params }) => {
    const type = getDb().taskTypes.find((t) => t.id === Number(params.taskTypeId));
    if (!type) return notFound('Task type');
    return HttpResponse.json({ data: taskTypeDto(type) },
      { headers: { ETag: taskTypeEtag(type) } });
  }),

  http.patch(url('/masters/task-types/:taskTypeId'), async ({ params, request }) => {
    const db = getDb();
    const type = db.taskTypes.find((t) => t.id === Number(params.taskTypeId));
    if (!type) return notFound('Task type');

    const stale = taskTypePrecondition(type, request.headers.get('If-Match'));
    if (stale) return stale;

    const body = (await request.json()) as Record<string, unknown>;
    // Resending the stored code is a no-op: S-11 submits the whole form on
    // every save, and any other reading makes every edit a 409.
    if (body.code != null && String(body.code).trim().toUpperCase() !== type.code) {
      const detail = 'A task type code cannot be changed once created. Deactivate this type and create a replacement.';
      return problem(409, 'immutable-field', 'Task type code cannot be changed', {
        detail,
        errors: { code: [detail] },
      });
    }
    if (body.name != null) {
      const name = String(body.name).trim();
      const clash = db.taskTypes.find(
        (t) => t.id !== type.id && t.name.toLowerCase() === name.toLowerCase(),
      );
      if (clash) {
        const detail = `'${clash.name}' already exists. Two task types with the same name are indistinguishable in every picker that renders them.`;
        return problem(409, 'duplicate', 'Duplicate task type', {
          detail,
          errors: { name: [detail] },
        });
      }
      type.name = name;
    }
    if (body.defaultLevel != null) {
      const level = levelRefusal(String(body.defaultLevel).trim().toUpperCase());
      if (level.refusal) return level.refusal;
      type.defaultLevel = level.value as Level;
    }
    // `undefined` is absent and `null` is "clear it" — the distinction the real
    // DTO is a POJO rather than a record in order to keep.
    if (body.icon !== undefined) type.icon = (body.icon as string | null) || null;
    if (body.defaultSlaHrs !== undefined) {
      type.defaultSlaHrs = body.defaultSlaHrs == null ? null : Number(body.defaultSlaHrs);
    }
    if (body.colour != null) type.colour = String(body.colour);
    if (body.seq != null) type.seq = Number(body.seq);
    // Never refused, whatever the ticket count. Refusing would leave an
    // organisation unable to retire a type it has stopped using.
    if (body.isActive != null) type.isActive = Boolean(body.isActive);

    return HttpResponse.json({ data: taskTypeDto(type) },
      { headers: { ETag: taskTypeEtag(type) } });
  }),

  // Inactive rows included, in `seq` order — a picker filters them out, a grid
  // still has to name the module an old ticket was raised against.
  http.get(url('/masters/modules'), () =>
    ok([...getDb().modules].sort((a, b) => a.seq - b.seq)),
  ),
  // ── priorities · S-12 (B-021) ─────────────────────────────────────────────
  // Rows from the store, not a literal. Until B-021 this was four frozen
  // objects with colours that matched neither §12.1 nor the migration, and two
  // of them flagged as the escalation target.
  //
  // **Active-only by default, unlike task types and modules above.**
  // `CreateTicketPage` and `TicketListPage` map this response straight into
  // their pickers without filtering, because until this task it could not
  // contain a retired row. The grid passes `includeInactive`.
  http.get(url('/masters/priorities'), ({ request }) => {
    const includeInactive =
      new URL(request.url).searchParams.get('includeInactive') === 'true';
    return ok(
      getDb().priorities
        .filter((p) => includeInactive || p.isActive)
        .sort((a, b) => a.seq - b.seq || a.id - b.id)
        .map(priorityDto),
    );
  }),

  http.get(url('/masters/priorities/:priorityId'), ({ params }) => {
    const priority = getDb().priorities.find((p) => p.id === Number(params.priorityId));
    if (!priority) return notFound('Priority');
    return ok(priorityDto(priority), undefined, { headers: { ETag: priorityEtag(priority) } });
  }),

  http.post(url('/masters/priorities'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Partial<Priority>;
    const level = String(body.level ?? '').trim().toUpperCase() as Level;

    // The headline refusal, mirrored from `PriorityService` so the screen can
    // be built against it. A mock that accepted a fifth level would let the
    // S-12 form ship with no handling for the one error it will actually see.
    if (!CONTRACT_LEVELS.includes(level)) {
      return validationFailed({
        level: [`'${level}' cannot be carried by the API's Level type, which is a closed `
          + 'four-value enum (LOW, MEDIUM, HIGH, CRITICAL). Opening it is a coordinated '
          + 'change across three streams.'],
      });
    }
    if (db.priorities.some((p) => p.level === level)) {
      return problem(409, 'duplicate', 'Duplicate priority level', {
        detail: `A level with code '${level}' already exists.`,
        errors: { level: [`A level with code '${level}' already exists.`] },
      });
    }
    const name = String(body.name ?? '').trim();
    const clash = db.priorities.find((p) => p.name.toLowerCase() === name.toLowerCase());
    if (clash) {
      return problem(409, 'duplicate', 'Duplicate priority level', {
        detail: `'${clash.name}' already exists.`,
        errors: { name: [`'${clash.name}' already exists.`] },
      });
    }

    const created: Priority = {
      id: nextId(db, 'priority'),
      level,
      name,
      colour: String(body.colour),
      defaultSlaHrs: body.defaultSlaHrs ?? null,
      autoEscalates: false,
      seq: body.seq ?? Math.max(0, ...db.priorities.map((p) => p.seq)) + 10,
      isActive: body.isActive ?? true,
    };
    db.priorities.push(created);
    if (body.autoEscalates) {
      db.priorities.forEach((p) => { p.autoEscalates = p.id === created.id; });
    }
    return ok(priorityDto(created), undefined,
      { status: 201, headers: { ETag: priorityEtag(created) } });
  }),

  http.patch(url('/masters/priorities/:priorityId'), async ({ params, request }) => {
    const db = getDb();
    const priority = db.priorities.find((p) => p.id === Number(params.priorityId));
    if (!priority) return notFound('Priority');

    const stale = priorityPrecondition(priority, request.headers.get('If-Match'));
    if (stale) return stale;

    const body = (await request.json()) as Partial<Priority>;

    if (body.level != null && String(body.level).toUpperCase() !== priority.level) {
      const detail = 'A level code cannot be changed once created — nothing would cascade '
        + 'the rename, so it would orphan every row holding the old value.';
      return problem(409, 'immutable-field', 'Priority code cannot be changed',
        { detail, errors: { level: [detail] } });
    }

    // The end state, computed before anything is written — the same ordering
    // bug `PriorityService.update` guards against. A body carrying both fields
    // would otherwise pass each check by being read before the other applied.
    const willBeActive = body.isActive ?? priority.isActive;

    if (priority.isActive && !willBeActive) {
      if (priority.autoEscalates) {
        return escalationTargetProblem(
          `'${priority.level}' is the level the SLA engine escalates to on breach (§6). `
          + 'Retiring it would leave auto-escalation with no target. Move the escalation '
          + 'flag to another level first.', 'isActive');
      }
      const blockers = db.taskTypes.filter(
        (t) => t.isActive && t.defaultLevel === priority.level);
      if (blockers.length > 0) {
        const detail = `${blockers.length} active task type${blockers.length === 1 ? '' : 's'} `
          + `default to '${priority.level}' (${blockers.slice(0, 5).map((t) => t.name).join(', ')}). `
          + 'Repoint them on the task type master first.';
        return problem(409, 'in-use', 'Priority level is in use', {
          detail,
          taskTypeCount: blockers.length,
          taskTypeNames: blockers.slice(0, 5).map((t) => t.name),
          errors: { isActive: [detail] },
        });
      }
    }

    if (body.autoEscalates != null) {
      if (!body.autoEscalates) {
        if (priority.autoEscalates) {
          return escalationTargetProblem(
            `'${priority.level}' is the level the SLA engine escalates to on breach (§6). `
            + 'Clearing it would leave auto-escalation with no target. Set the flag on '
            + 'another level instead — doing so clears this one.', 'autoEscalates');
        }
      } else if (!willBeActive) {
        return escalationTargetProblem(
          'A retired level cannot be the escalation target.', 'autoEscalates');
      } else {
        db.priorities.forEach((p) => { p.autoEscalates = p.id === priority.id; });
      }
    }

    if (body.name != null) {
      const name = body.name.trim();
      const clash = db.priorities.find(
        (p) => p.id !== priority.id && p.name.toLowerCase() === name.toLowerCase());
      if (clash) {
        return problem(409, 'duplicate', 'Duplicate priority level', {
          detail: `'${clash.name}' already exists.`,
          errors: { name: [`'${clash.name}' already exists.`] },
        });
      }
      priority.name = name;
    }
    if (body.colour != null) priority.colour = body.colour;
    if (body.defaultSlaHrs !== undefined) priority.defaultSlaHrs = body.defaultSlaHrs;
    if (body.seq != null) priority.seq = body.seq;
    if (body.isActive != null) priority.isActive = body.isActive;

    return ok(priorityDto(priority), undefined, { headers: { ETag: priorityEtag(priority) } });
  }),
  // ── notification templates · S-15 (B-022) ─────────────────────────────────
  // Every row, switched-off ones included, and no `includeInactive` parameter —
  // nothing outside S-15 reads this route, so the grid's need to show a
  // switched-off template is the only requirement there is.
  http.get(url('/masters/notification-templates'), () => ok(
    [...getDb().notificationTemplates]
      .sort((a, b) => a.eventCode.localeCompare(b.eventCode)
        || a.channel.localeCompare(b.channel))
      .map(templateDto),
  )),

  // Declared before `/:templateId` so MSW matches the literal first — unlike
  // Spring, which ranks a literal segment above a path variable regardless of
  // order, MSW takes the first registered match.
  http.get(url('/masters/notification-templates/vocabulary'), () => ok({
    // Derived from the seeded templates rather than restated, so the mock has
    // one vocabulary rather than two that drift. Every event has at least one
    // template, which is what makes the derivation total.
    events: [...new Map(getDb().notificationTemplates.map(
      (t) => [t.eventCode, {
        code: t.eventCode,
        category: t.category,
        mandatoryMail: isMandatoryMail(t.category),
      }])).values()].sort((a, b) => a.code.localeCompare(b.code)),
    channels: NOTIFICATION_CHANNELS,
    recipients: NOTIFICATION_RECIPIENTS,
    mergeTags: MERGE_TAGS,
  })),

  http.get(url('/masters/notification-templates/:templateId'), ({ params }) => {
    const template = getDb().notificationTemplates
      .find((t) => t.id === Number(params.templateId));
    if (!template) return notFound('Notification template');
    return ok(templateDto(template), undefined,
      { headers: { ETag: templateEtag(template) } });
  }),

  http.post(url('/masters/notification-templates'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Partial<NotificationTemplateRow>;
    const eventCode = String(body.eventCode ?? '').trim().toUpperCase();
    const channel = String(body.channel ?? '').trim().toUpperCase() as NotificationChannelCode;

    const known = db.notificationTemplates.find((t) => t.eventCode === eventCode);
    if (!known) {
      return validationFailed({
        eventCode: [`'${eventCode}' is not an event this system raises. A template for an `
          + 'event nothing fires is wording that can never be read.'],
      });
    }
    if (!NOTIFICATION_CHANNELS.includes(channel)) {
      return validationFailed({
        channel: [`'${channel}' is not a delivery channel. The three are IN_APP, EMAIL and `
          + 'PUSH — the bell is not one, it renders the IN_APP template.'],
      });
    }
    if (db.notificationTemplates.some(
      (t) => t.eventCode === eventCode && t.channel === channel)) {
      const detail = `A ${channel} template for ${eventCode} already exists. An event has one `
        + 'template per channel — edit that one, or bring it back if it is switched off.';
      return problem(409, 'duplicate', 'That event already has a template on this channel',
        { detail, errors: { eventCode: [detail] } });
    }

    const recipients = body.recipients ?? [];
    const badRecipients = recipientProblem(recipients);
    if (badRecipients) return badRecipients;

    const subject = (body.subjectTemplate ?? '').trim() || null;
    const bodyTemplate = String(body.bodyTemplate ?? '');
    const badSubject = subjectProblem(channel, subject);
    if (badSubject) return badSubject;
    const badTags = mergeTagProblem(subject, bodyTemplate);
    if (badTags) return badTags;

    const isActive = body.isActive ?? true;
    if (!isActive) {
      const locked = mandatoryProblem(known.category, channel);
      if (locked) return locked;
    }

    const created: NotificationTemplateRow = {
      id: Math.max(0, ...db.notificationTemplates.map((t) => t.id)) + 1,
      eventCode,
      category: known.category,
      channel,
      recipients: [...recipients],
      subjectTemplate: subject,
      bodyTemplate: bodyTemplate.trim(),
      isActive,
    };
    db.notificationTemplates.push(created);
    return ok(templateDto(created), undefined,
      { status: 201, headers: { ETag: templateEtag(created) } });
  }),

  http.patch(url('/masters/notification-templates/:templateId'), async ({ params, request }) => {
    const db = getDb();
    const template = db.notificationTemplates.find((t) => t.id === Number(params.templateId));
    if (!template) return notFound('Notification template');

    const stale = templatePrecondition(template, request.headers.get('If-Match'));
    if (stale) return stale;

    const body = (await request.json()) as Partial<NotificationTemplateRow>;

    // The pair is the row's identity. Refused rather than applied, and resending
    // the stored values is a no-op — S-15 submits the whole form on every save.
    for (const [field, stored] of [
      ['eventCode', template.eventCode], ['channel', template.channel],
    ] as const) {
      const sent = body[field];
      if (sent != null && String(sent).toUpperCase() !== stored) {
        const detail = "A template's event and channel are its identity and cannot be changed. "
          + `This one is the ${template.channel} template for ${template.eventCode}. Sent mail `
          + 'points at this row by id, so re-pointing it would change what those records claim '
          + 'to have been rendered from.';
        return problem(409, 'immutable-field',
          "A template's event and channel cannot be changed",
          { detail, errors: { [field]: [detail] } });
      }
    }

    // The end state, computed before anything is written — the same ordering
    // bug `PriorityService.update` guards against, and needed here because the
    // subject rule and the mandatory rule each read a field the other may move.
    const willBeSubject = body.subjectTemplate !== undefined
      ? (body.subjectTemplate ?? '').trim() || null
      : template.subjectTemplate;
    const willBeBody = body.bodyTemplate !== undefined
      ? String(body.bodyTemplate).trim()
      : template.bodyTemplate;
    const willBeActive = body.isActive ?? template.isActive;

    const badSubject = subjectProblem(template.channel, willBeSubject);
    if (badSubject) return badSubject;
    const badTags = mergeTagProblem(willBeSubject, willBeBody);
    if (badTags) return badTags;
    if (!willBeActive) {
      const locked = mandatoryProblem(template.category, template.channel);
      if (locked) return locked;
    }

    if (body.recipients !== undefined) {
      const badRecipients = recipientProblem(body.recipients ?? []);
      if (badRecipients) return badRecipients;
      template.recipients = [...(body.recipients ?? [])];
    }
    if (body.bodyTemplate !== undefined) {
      if (!willBeBody) {
        return validationFailed({
          bodyTemplate: ['bodyTemplate cannot be blank. A template with no body renders an '
            + 'empty notification, which is worse than none.'],
        });
      }
      template.bodyTemplate = willBeBody;
    }
    if (body.subjectTemplate !== undefined) template.subjectTemplate = willBeSubject;
    if (body.isActive != null) template.isActive = body.isActive;

    return ok(templateDto(template), undefined,
      { headers: { ETag: templateEtag(template) } });
  }),

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
    domain: c.domain,
    accountManager: c.accountManagerId == null ? null : userRef(c.accountManagerId, db),
    supportPlan: c.supportPlan, slaPolicyId: c.slaPolicyId ?? null, timezone: c.timezone,
    // B-026 · `status !== 'INACTIVE'`, never `status === 'ACTIVE'`. §4B.2's
    // ticket-form dropdown filters on this boolean, so the narrow reading would
    // remove every prospect from it — the server's ClientStatus carries the
    // argument and the mock must not disagree with it.
    isActive: c.status !== 'INACTIVE',
    status: c.status,
    openTicketCount: db.tickets.filter((t) => t.clientId === c.id && t.status !== 'CLOSED').length,
    // B-028 · `isActive` matters here and the mock was missing it. The server's
    // `primaryContacts` reads `is_primary = 1 AND is_active = 1`, because B-027
    // removes a contact by deactivating it — a client whose only primary has
    // left is a client nobody can be reached through, and counting them keeps
    // it selectable on a ticket it should not be.
    primaryContact:
      db.contacts.find((x) => x.clientId === c.id && x.isPrimary && x.isActive) ?? null,
    // B-028's gate. Stated rather than derived from `primaryContact` for the
    // reason the contract gives: the server omits that key rather than nulling
    // it, so a consumer reading the object gets `undefined`.
    hasPrimaryContact: db.contacts.some((x) => x.clientId === c.id && x.isPrimary && x.isActive),
    // B-025 · S-32's Projects and Last Ticket columns. Both derived from the
    // fixture rather than stored on the client, so a mapping added or a ticket
    // raised in a test is reflected here without a second place to update.
    projects: db.clientProjects
      .filter((cp) => cp.clientId === c.id)
      .map((cp) => db.projects.find((p) => p.id === cp.projectId))
      .filter((p): p is NonNullable<typeof p> => p != null)
      .map((p) => ({ id: p.id, projectCode: p.projectCode, name: p.name })),
    // Null rather than absent for a client nothing has been raised against —
    // "Never" is a state the column renders, not missing data.
    lastTicketDate:
      db.tickets
        .filter((t) => t.clientId === c.id)
        .map((t) => t.createdAt)
        .sort()
        .at(-1) ?? null,
  };
}

/**
 * B-026 · S-33's read — the grid row plus the §4B.2 groups the grid has no
 * column for, matching `ClientDtos.ClientDetail`.
 */
function clientDetailDto(c: import('../db').Client) {
  const db = getDb();
  const contacts = db.contacts.filter((x) => x.clientId === c.id);
  return {
    ...clientDto(c),
    shortName: c.shortName ?? null,
    logoUrl: c.logoUrl ?? null,
    industry: c.industry ?? null,
    primaryEmail: c.primaryEmail ?? null,
    supportEmail: c.supportEmail ?? null,
    phone: c.phone ?? null,
    addressLine1: c.addressLine1 ?? null,
    addressLine2: c.addressLine2 ?? null,
    city: c.city ?? null,
    state: c.state ?? null,
    country: c.country ?? null,
    postalCode: c.postalCode ?? null,
    contractStart: c.contractStart ?? null,
    contractEnd: c.contractEnd ?? null,
    billingReference: c.billingReference ?? null,
    billingEmail: c.billingEmail ?? null,
    notes: c.notes ?? null,
    tags: c.tags ?? [],
    defaultProjectId:
      db.clientProjects.find((cp) => cp.clientId === c.id && cp.isDefault)?.projectId ?? null,
    // Live contacts only, agreeing with the server's `contactSummary`. B-028 ·
    // `hasPrimaryContact` is not repeated here — `clientDto` above states it
    // once and this spreads it, so the list row and the detail cannot answer
    // the gate differently.
    contactCount: contacts.filter((x) => x.isActive).length,
  };
}

/**
 * Content-derived, like the server's — a timestamp tag moves when a save
 * rewrites identical values, failing an edit that conflicts with nothing.
 * `contactCount` is in it on purpose: a contact added elsewhere is precisely
 * the event that makes the client selectable on a ticket.
 */
function clientEtag(c: import('../db').Client) {
  return `"${JSON.stringify(clientDetailDto(c)).length.toString(16)}-${c.clientCode}"`;
}

/**
 * The mock's copy of `ClientWriteService`'s validation set.
 *
 * Deliberately the same rules and the same field keys, because the form's
 * "which tab does this error belong to" routing is driven off those keys — a
 * mock that refused nothing would let a whole class of UI behaviour ship
 * untested, and one that used different keys would test it against the wrong
 * contract. Returns a field-keyed map, empty when the body is good.
 */
function validateClientWrite(
  body: Record<string, unknown>,
  exceptId: number | null,
): Record<string, string[]> {
  const db = getDb();
  const errors: Record<string, string[]> = {};

  const code = String(body.clientCode ?? '').trim().toUpperCase();
  if (db.clients.some((c) => c.clientCode.toUpperCase() === code && c.id !== exceptId)) {
    errors.clientCode = [`Client code ${code} is already in use.`];
  }

  // B-028 · the three optional addresses, on the one rule. Absent and blank are
  // not failures — every column is nullable — so only a value that is there and
  // malformed is refused, which is `ClientWriteService.checkEmail` exactly.
  for (const field of ['primaryEmail', 'supportEmail', 'billingEmail'] as const) {
    const value = body[field] == null ? '' : String(body[field]).trim();
    if (value !== '' && !isWellFormedEmail(value)) {
      errors[field] = [`${field} is not a well-formed email address.`];
    }
  }

  const status = body.status == null ? 'ACTIVE' : String(body.status).toUpperCase();
  if (!['ACTIVE', 'INACTIVE', 'PROSPECT'].includes(status)) {
    errors.status = ['Status must be one of [ACTIVE, INACTIVE, PROSPECT].'];
  }

  if (body.supportPlan != null && String(body.supportPlan) !== '') {
    const plan = String(body.supportPlan).toUpperCase();
    if (!['BASIC', 'STANDARD', 'PREMIUM', 'ENTERPRISE'].includes(plan)) {
      errors.supportPlan = ['Support plan must be one of [BASIC, STANDARD, PREMIUM, ENTERPRISE].'];
    }
  }

  const timezone = body.timezone == null ? null : String(body.timezone).trim();
  if (timezone) {
    try {
      new Intl.DateTimeFormat('en', { timeZone: timezone });
    } catch {
      errors.timezone = [`'${timezone}' is not a known time zone.`];
    }
  }

  if (body.contractStart && body.contractEnd
      && String(body.contractEnd) < String(body.contractStart)) {
    errors.contractEnd = ['The contract cannot end before it starts.'];
  }

  if (body.accountManagerId != null) {
    const manager = db.users.find((u) => u.id === Number(body.accountManagerId));
    if (!manager) {
      errors.accountManagerId = ['That resource does not exist.'];
    } else if (!manager.isActive) {
      errors.accountManagerId = [
        `${manager.displayName} is deactivated and cannot be an account manager.`,
      ];
    }
  }

  const projectIds = Array.isArray(body.projectIds) ? (body.projectIds as number[]) : [];
  const missing = projectIds.filter((id) => !db.projects.some((p) => p.id === Number(id)));
  if (missing.length > 0) {
    errors.projectIds = [`These projects do not exist: ${missing.join(', ')}.`];
  }
  if (body.defaultProjectId != null && !projectIds.includes(Number(body.defaultProjectId))) {
    errors.defaultProjectId = [
      'The default project must be one of the projects this client is mapped to.',
    ];
  }

  return errors;
}

/**
 * 409 when a duplicate client code is the **only** failure, 400 otherwise —
 * `ClientExceptionHandler`'s rule, mirrored.
 *
 * Not cosmetic: CONVENTIONS.md §3 says clients branch on the status and the
 * `type`, so a 409 that also carried a bad timezone would be handled as a
 * uniqueness conflict and the other message would never be shown.
 */
function clientWriteProblem(errors: Record<string, string[]>) {
  const duplicateOnly = Object.keys(errors).length === 1 && 'clientCode' in errors;
  return duplicateOnly
    ? problem(409, 'duplicate', 'Client code already in use', { errors })
    : problem(400, 'validation-failed', 'The client was not saved', { errors });
}

/**
 * B-027 · a contact write's queried rules, mirroring `ClientContactService`.
 *
 * The two `@NotBlank`s are checked here rather than left to zod, because a mock
 * that accepted a nameless contact would let the row editor's own required-field
 * handling ship untested against anything.
 *
 * The email rule is the one that matters: **unique among this client's live
 * contacts, case-insensitively, and only within the client.** The same address
 * under two different clients is a consultant retained by both — the server's
 * index is deliberately not unique so D-039 can disambiguate on `website_domain`
 * — and a mock that checked globally would teach the opposite lesson.
 *
 * `exceptId` is what stops every ordinary edit reporting the contact's own
 * address as taken, since the body carries it on every save.
 */
function validateContactWrite(
  db: import('../db').Db,
  clientId: number,
  body: Record<string, unknown>,
  exceptId: number | null,
) {
  const name = String(body.name ?? '').trim();
  if (!name) return validationFailed({ name: ['name is required'] });

  const email = String(body.email ?? '').trim();
  if (!email) return validationFailed({ email: ['email is required'] });
  // B-028 · the server's rule, not `includes('@')`. This mock was the loosest
  // of the four things that answered "is this a valid email?", so a contact
  // added under `npm run dev` at `sara@acme` was accepted here and refused by
  // MySQL-backed `ClientContactService` — correct in dev, wrong in production,
  // which is the worst way round.
  if (!isWellFormedEmail(email)) {
    return validationFailed({ email: ['email is not a well-formed email address.'] });
  }

  const clash = db.contacts.find(
    (c) =>
      c.clientId === clientId &&
      c.isActive &&
      c.id !== exceptId &&
      (c.email ?? '').toLowerCase() === email.toLowerCase(),
  );
  if (clash) {
    // 409 whenever a duplicate email is involved, where the *client* form is
    // 409 only when a duplicate code is the sole failure — a contact has one
    // queried rule, so there is no mixture to describe with the wrong status.
    return problem(409, 'duplicate', 'That email is already used at this client', {
      errors: {
        email: [`${clash.name} already uses ${email.toLowerCase()} at this client.`],
      },
    });
  }
  return null;
}

/**
 * Clears `is_primary` on every *other* contact of the client — the single-writer
 * rule the schema cannot assert, since MySQL has no partial unique index.
 *
 * Removed contacts are demoted too, matching the server's predicate: a row that
 * kept its flag would render starred under `?includeInactive=true`, which is a
 * person who has left shown as the client's primary.
 */
function demoteOtherPrimaries(db: import('../db').Db, clientId: number, exceptId: number) {
  db.contacts
    .filter((c) => c.clientId === clientId && c.id !== exceptId)
    .forEach((c) => {
      c.isPrimary = false;
    });
}

/** An absent or blank optional string is `null`, which is what the column holds. */
function trimOrNull(raw: unknown): string | null {
  if (raw == null) return null;
  const trimmed = String(raw).trim();
  return trimmed === '' ? null : trimmed;
}

/** The `client_projects` replace, mirroring `ClientWriteRepository`. */
function applyClientProjects(clientId: number, body: Record<string, unknown>) {
  if (!Array.isArray(body.projectIds)) {
    // Absent leaves the mapping alone; only an explicit array replaces it.
    return;
  }
  const db = getDb();
  const ids = [...new Set((body.projectIds as number[]).map(Number))];
  db.clientProjects = db.clientProjects.filter((cp) => cp.clientId !== clientId);
  ids.forEach((projectId) => {
    db.clientProjects.push({
      clientId,
      projectId,
      isDefault: Number(body.defaultProjectId) === projectId,
    });
  });
}

/** Applies an S-33 body to a client row. Absent means cleared — see the server's `apply`. */
function applyClientWrite(c: import('../db').Client, body: Record<string, unknown>) {
  const text = (key: string) => {
    const raw = body[key];
    if (raw == null) return null;
    const trimmed = String(raw).trim();
    return trimmed === '' ? null : trimmed;
  };

  c.clientCode = String(body.clientCode ?? c.clientCode).trim().toUpperCase();
  c.name = String(body.name ?? c.name).trim();
  c.shortName = text('shortName');
  c.logoUrl = text('logoUrl');
  c.industry = text('industry');
  c.status = (body.status == null
    ? 'ACTIVE'
    : String(body.status).toUpperCase()) as import('../db').Client['status'];
  // The same normalisation the server does, so a domain saved here matches an
  // inbound sender address the way it will in production.
  c.domain = (text('domain') ?? '').toLowerCase()
    .replace(/^[a-z][a-z0-9+.-]*:\/\//, '').replace(/^www\./, '').split('/')[0];
  c.primaryEmail = text('primaryEmail');
  c.supportEmail = text('supportEmail');
  c.phone = text('phone');
  c.addressLine1 = text('addressLine1');
  c.addressLine2 = text('addressLine2');
  c.city = text('city');
  c.state = text('state');
  c.country = text('country');
  c.postalCode = text('postalCode');
  c.timezone = text('timezone') ?? 'Asia/Kolkata';
  c.accountManagerId = body.accountManagerId == null ? null : Number(body.accountManagerId);
  c.contractStart = text('contractStart');
  c.contractEnd = text('contractEnd');
  c.supportPlan = body.supportPlan == null || String(body.supportPlan) === ''
    ? null
    : String(body.supportPlan).toUpperCase();
  c.billingReference = text('billingReference');
  c.billingEmail = text('billingEmail');
  c.notes = text('notes');
  c.tags = Array.isArray(body.tags) ? (body.tags as string[]).map((t) => String(t).trim()) : [];
  c.slaPolicyId = body.slaPolicyId == null ? null : Number(body.slaPolicyId);
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
