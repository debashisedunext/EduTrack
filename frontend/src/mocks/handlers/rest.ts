import { http, HttpResponse } from 'msw';
import { isWellFormedEmail } from '@/lib/email';
import { getDb, nextId } from '../db';
import type {
  Db, Holiday, Level, NotificationChannelCode, NotificationTemplateRow, Priority, ReportScheduleRow,
  ProjectRoleCode, Role, Status, StatusCategory, StatusCode, TaskType, TemplateStage, User,
  WorkflowTransitionRow,
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

/**
 * A-073 · one widget's mock payload, shared by `/dashboard/widget/{key}` and
 * `/dashboard/widgets`.
 *
 * Extracted so the single and batch routes cannot disagree. Two mocks computing
 * the same widget two ways would drift, and the first assertion to break would
 * be one checking that the batched dashboard renders what the per-widget one
 * did — the exact property A-073's change has to preserve.
 */
function widgetPayload(db: ReturnType<typeof getDb>, key: string) {
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
  return { key, asOf: new Date().toISOString(), series };
}

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

/**
 * B-063 · the date and capacity arithmetic the timesheet needs.
 *
 * ISO strings throughout rather than a date library: the mock has no
 * dependencies, and `YYYY-MM-DD` compares correctly as a string, which is what
 * the week-bounds filter relies on.
 */
const addDays = (date: string, days: number) => {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

/** Mon=1 … Sun=7, matching `weeklyOff`'s ISO-8601 numbering rather than JS's. */
const isoWeekday = (date: string) => {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return day === 0 ? 7 : day;
};

/** The Monday of the week containing `date` — the server resolves it the same way. */
const mondayOf = (date: string) => addDays(date, 1 - isoWeekday(date));

const isoToday = () => new Date().toISOString().slice(0, 10);

const round2 = (n: number) => Math.round(n * 100) / 100;

const hoursOfWorkingDay = () => {
  const { workDayStart, workDayEnd } = calendarState().week;
  const minutes = (t: string) => {
    const [h, m] = t.split(':').map(Number);
    return h * 60 + m;
  };
  return (minutes(workDayEnd) - minutes(workDayStart)) / 60;
};

/**
 * What the calendar offers one resource on one date.
 *
 * <p>Weekly off, then org holidays — recurring ones matched on month and day,
 * as the server expands them — then that person's approved leave, with a half
 * day counted as half. Project holidays are not consulted: a week spans
 * projects, so the server does not pass a project id either.
 */
const capacityOn = (userId: number, date: string) => {
  const cal = calendarState();
  if (cal.week.weeklyOff.includes(isoWeekday(date))) return 0;

  const onHoliday = cal.holidays.some(
    (h) =>
      h.isActive &&
      h.projectId === null &&
      (h.date === date || (h.isRecurring && h.date.slice(5) === date.slice(5))),
  );
  if (onHoliday) return 0;

  const leave = cal.leaves.find(
    (l) =>
      l.userId === userId &&
      l.status === 'APPROVED' &&
      l.startDate <= date &&
      date <= l.endDate,
  );
  const full = hoursOfWorkingDay();
  if (!leave) return round2(full);
  return leave.isHalfDay ? round2(full / 2) : 0;
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

// ── S-13 tab 2 · the stage master (B-040) ───────────────────────────────────

/** One template's stages, left to right. */
const ribbon = (templateId: number) =>
  getDb().templateStages
    .filter((s) => s.templateId === templateId)
    .sort((a, b) => a.seq - b.seq);

/**
 * `position` is computed here rather than stored, exactly as the server computes
 * it: it is a fact about a stage's neighbours, and storing it would be a second
 * copy of the order to keep in step with `seq`.
 *
 * `isCodeEditable` is derived from the two counts for the same reason — it is the
 * server's answer to "may I rename this?", and the form reads it rather than
 * restating the rule.
 */
function stageDto(stage: TemplateStage) {
  const position = ribbon(stage.templateId).findIndex((s) => s.id === stage.id) + 1;
  return {
    id: stage.id,
    templateId: stage.templateId,
    stageCode: stage.stageCode,
    displayName: stage.displayName,
    ownerRole: stage.ownerRole,
    slaHours: stage.slaHours,
    isOptional: stage.isOptional,
    canReturnTo: stage.canReturnTo,
    icon: stage.icon,
    seq: stage.seq,
    position,
    transitionCount: stage.transitionCount,
    openTicketCount: stage.openTicketCount,
    isCodeEditable: stage.transitionCount === 0 && stage.openTicketCount === 0,
    isDeprecated: stage.isDeprecated,
    deprecatedAt: stage.deprecatedAt,
    // B-042. Three of the four conditions are facts about *other* rows, which is
    // why the server computes it and the screen does not — the mock has to do the
    // same work or the Delete button appears on rows the backend would refuse.
    isDeletable: stage.transitionCount === 0 && stage.openTicketCount === 0
      && retireBlockers(stage, ribbon(stage.templateId)) === null,
  };
}

/**
 * B-042 · the two states a stage may not be retired into, as the server refuses
 * them — shared by the deprecation setter and the delete, exactly as
 * `guardRetirable` is.
 *
 * Returns the problem document or `null`. Written out in full rather than
 * stubbed, because each sentence is one the confirm dialog renders and a mock
 * answering "Conflict" would let that copy ship having never been read.
 */
function retireBlockers(stage: TemplateStage, stages: TemplateStage[]) {
  const others = stages.filter((s) => s.id !== stage.id);

  if (!others.some((s) => !s.isDeprecated)) {
    return problem(409, 'last-live-stage', "That is the template's last live stage", {
      detail: `${stage.stageCode} is the last stage on this template that is still live. `
        + 'Retiring it would leave a workflow that can route no ticket at all, and the '
        + 'template picker would go on offering it. Add or restore another stage first.',
    });
  }

  const arrows = others
    .filter((s) => !s.isDeprecated)
    .filter((s) => s.canReturnTo.some((t) => t.toUpperCase() === stage.stageCode.toUpperCase()))
    .map((s) => `${s.stageCode} \u2192 ${stage.stageCode}`);

  if (arrows.length > 0) {
    const detail = `${stage.stageCode} is still a return target — ${arrows.join(', ')}. `
      + 'A return target is a move the transition service will honour, so leaving one '
      + 'pointing at a retired stage is an arrow into a stage nothing may enter. Clear it '
      + 'there first.';
    return problem(409, 'return-target-direction', 'That order breaks a return path',
      { detail, pairs: arrows, errors: { stageIds: [detail] } });
  }

  return null;
}

const hash = (value: string) =>
  `"${Math.abs([...value].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

const stageEtag = (stage: TemplateStage) => hash(JSON.stringify(stageDto(stage)));

/**
 * The ribbon's tag, over every row's content and not just the order.
 *
 * Deliberate, and it matches the server: two Admins editing different stages of
 * one template would otherwise both hold a valid tag for a reorder about to
 * discard one of their edits.
 */
const ribbonEtag = (stages: TemplateStage[]) =>
  hash(stages.map((s) => JSON.stringify(stageDto(s))).join('#'));

/**
 * The mock enforces `If-Match` too. A guard the real backend has and the mock
 * waves through is a guard the frontend never gets to exercise.
 */
function stagePrecondition(ifMatch: string | null, expected: string, what: string) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      `If-Match is required. GET the ${what} first and send back its ETag.`);
  }
  if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== expected.replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'Somebody else changed this since you read it. Reload and try again.');
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

// ── statuses and the transition matrix · S-13 tab 1 (B-039) ─────────────────

/**
 * The eight the contract's `StatusCode` can carry — mirrors `StatusService`.
 *
 * A ninth is refused with 400 here exactly as it is on the server, so the S-13
 * form is built against the one error it will actually see rather than against
 * a mock that accepts anything.
 */
const CONTRACT_STATUS_CODES: StatusCode[] = [
  'NEW', 'IN_PROGRESS', 'ON_HOLD', 'AWAITING_INFO',
  'REWORK', 'RESOLVED', 'CLOSED', 'REOPENED',
];

/**
 * The two usage counts, derived rather than stored.
 *
 * Both key on the status **code** against another collection, exactly as the
 * server's SQL keys on a `VARCHAR` rather than joining `statuses.id` — a fixture
 * that joined on the id would make the screen look right against data that
 * cannot exist.
 *
 * `transitionCount` counts **both ends**, because that is what a retire
 * deactivates. Counting only incoming moves would quote the retire dialog a
 * smaller number than the button then acts on.
 */
const statusDto = (status: Status) => {
  const db = getDb();
  return {
    ...status,
    ticketCount: db.tickets.filter((t) => t.status === status.code).length,
    transitionCount: db.workflowTransitions.filter(
      (t) => t.isActive && (t.fromStatus === status.code || t.toStatus === status.code),
    ).length,
    deactivatedTransitions: null as number | null,
  };
};

/**
 * Over the content, and **without `deactivatedTransitions`** — that field
 * describes an event rather than the row, so a status that reads identically has
 * to tag identically whether it was last written by a retire or by a rename.
 */
const statusEtag = (status: Status) =>
  `"${Math.abs([...JSON.stringify({ ...statusDto(status), deactivatedTransitions: null })]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * The matrix's own tag, taken over the whole table even when the read was
 * filtered by role.
 *
 * The one collection in this mock that carries an `ETag`, because it is the one
 * collection that is itself the unit of edit. A tag over a single column would
 * let two Admins editing different columns each save over the other with both
 * preconditions passing.
 */
const matrixEtag = () =>
  `"${Math.abs([...JSON.stringify(getDb().workflowTransitions)]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;

/**
 * The mock enforces `If-Match` too. A guard the real backend has and the mock
 * waves through is a guard the frontend never gets to exercise.
 */
function statusPrecondition(status: Status, ifMatch: string | null) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the status first and send back its ETag.');
  }
  if (ifMatch !== '*'
      && ifMatch.replace(/W\/|"/g, '') !== statusEtag(status).replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'This status changed since you read it. Reload and reapply your edit.');
  }
  return null;
}

function matrixPrecondition(ifMatch: string | null) {
  if (!ifMatch) {
    return problem(428, 'precondition-required',
      'If-Match is required. GET the matrix first and send back its ETag. A replace '
      + 'without one would silently discard whatever another Admin saved while this '
      + 'screen was open.');
  }
  if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== matrixEtag().replace(/"/g, '')) {
    return problem(412, 'precondition-failed',
      'The matrix changed since you read it. Reload and reapply your edit — saving now '
      + 'would delete cells somebody else has just added.');
  }
  return null;
}

/** Stated once, because the create and the patch have to refuse identically. */
const CONTRADICTORY_STATUS =
  'A status cannot be both terminal and open. Terminal means only a reopen moves a '
  + 'ticket on; open means the dashboard counts it as outstanding. Together they would '
  + 'put every ticket that reached this status into an open count nobody can drive to '
  + 'zero.';

/** Null is a real key here: it is the on-create row. */
const cellKey = (from: string | null | undefined, to: string, role: string) =>
  `${from ?? ''} ${to} ${role}`;

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

/**
 * B-033 · `ClientImportSchema.fields()`, mirrored — what `GET
 * /imports/{schema}/fields` answers with.
 *
 * The headers are exactly what B-031's template writes, undecorated: no asterisk
 * on the required ones, because `HeaderMatcher` matches on this text and a
 * decorated header would make the file this product hands out fail to auto-match
 * when it is uploaded back.
 *
 * `allowedValues` is the same list the template's data-validation dropdown is
 * built from, which is why step 3 can safely display it — one declaration means
 * the template cannot offer a value the import rejects.
 *
 * **Account manager and SLA policy are deliberately absent**, matching the real
 * registration: both are foreign keys and a spreadsheet carries only a name. A
 * column that resolves when the spelling happens to match and silently nulls the
 * field when it does not is worse than no column.
 */
const CLIENT_IMPORT_FIELDS = [
  { name: 'clientCode', header: 'Client Code', required: true, naturalKey: true, type: 'TEXT', maxLength: 20, allowedValues: [], example: 'ACME' },
  { name: 'name', header: 'Name', required: true, naturalKey: false, type: 'TEXT', maxLength: 150, allowedValues: [], example: 'Acme Corporation' },
  { name: 'shortName', header: 'Short Name', required: false, naturalKey: false, type: 'TEXT', maxLength: 60, allowedValues: [], example: 'Acme' },
  { name: 'industry', header: 'Industry', required: false, naturalKey: false, type: 'TEXT', maxLength: 80, allowedValues: [], example: 'Manufacturing' },
  { name: 'status', header: 'Status', required: false, naturalKey: false, type: 'ENUM', maxLength: 0, allowedValues: ['ACTIVE', 'INACTIVE'], example: 'ACTIVE' },
  { name: 'supportPlan', header: 'Support Plan', required: false, naturalKey: false, type: 'ENUM', maxLength: 0, allowedValues: ['BASIC', 'STANDARD', 'PREMIUM'], example: 'STANDARD' },
  { name: 'primaryEmail', header: 'Primary Email', required: false, naturalKey: false, type: 'EMAIL', maxLength: 150, allowedValues: [], example: 'accounts@acme.example' },
  { name: 'supportEmail', header: 'Support Email', required: false, naturalKey: false, type: 'EMAIL', maxLength: 150, allowedValues: [], example: 'support@acme.example' },
  { name: 'phone', header: 'Phone', required: false, naturalKey: false, type: 'TEXT', maxLength: 30, allowedValues: [], example: '+91 22 4000 1000' },
  { name: 'websiteDomain', header: 'Website Domain', required: false, naturalKey: false, type: 'TEXT', maxLength: 120, allowedValues: [], example: 'acme.example' },
  { name: 'addressLine1', header: 'Address Line 1', required: false, naturalKey: false, type: 'TEXT', maxLength: 150, allowedValues: [], example: '14 Marine Drive' },
  { name: 'addressLine2', header: 'Address Line 2', required: false, naturalKey: false, type: 'TEXT', maxLength: 150, allowedValues: [], example: null },
  { name: 'city', header: 'City', required: false, naturalKey: false, type: 'TEXT', maxLength: 80, allowedValues: [], example: 'Mumbai' },
  { name: 'state', header: 'State', required: false, naturalKey: false, type: 'TEXT', maxLength: 80, allowedValues: [], example: 'Maharashtra' },
  { name: 'country', header: 'Country', required: false, naturalKey: false, type: 'TEXT', maxLength: 80, allowedValues: [], example: 'India' },
  { name: 'postalCode', header: 'Postal Code', required: false, naturalKey: false, type: 'TEXT', maxLength: 20, allowedValues: [], example: '400020' },
  { name: 'timezone', header: 'Timezone', required: false, naturalKey: false, type: 'TEXT', maxLength: 50, allowedValues: [], example: 'Asia/Kolkata' },
  { name: 'contractStart', header: 'Contract Start', required: false, naturalKey: false, type: 'DATE', maxLength: 0, allowedValues: [], example: '2026-04-01' },
  { name: 'contractEnd', header: 'Contract End', required: false, naturalKey: false, type: 'DATE', maxLength: 0, allowedValues: [], example: '2027-03-31' },
  { name: 'notes', header: 'Notes', required: false, naturalKey: false, type: 'TEXT', maxLength: 0, allowedValues: [], example: 'Renewal due Q1' },
];

/**
 * B-038 · `ResourceImportSchema.fields()`, mirrored — the second registration.
 *
 * Beside the client list rather than derived from it, because they are two
 * declarations of two different masters and the moment one is expressed in terms
 * of the other the mock has invented a hierarchy the server does not have.
 *
 * **Reporting manager, projects and password are absent**, matching the real
 * registration. The first is the one worth knowing about: an employee code
 * identifies a manager exactly, so the column *could* work — it is refused
 * because B-012's cycle rule holds at any depth and a file can name a manager
 * three rows below the person reporting to them.
 */
const RESOURCE_IMPORT_FIELDS = [
  { name: 'employeeCode', header: 'Employee Code', required: true, naturalKey: true, type: 'TEXT', maxLength: 20, allowedValues: [], example: 'EDU-0142' },
  { name: 'fullName', header: 'Full Name', required: true, naturalKey: false, type: 'TEXT', maxLength: 120, allowedValues: [], example: 'Asha Menon' },
  { name: 'username', header: 'Username', required: true, naturalKey: false, type: 'TEXT', maxLength: 50, allowedValues: [], example: 'asha.menon' },
  { name: 'email', header: 'Email', required: true, naturalKey: false, type: 'EMAIL', maxLength: 150, allowedValues: [], example: 'asha.menon@edunext.example' },
  { name: 'role', header: 'Role', required: true, naturalKey: false, type: 'ENUM', maxLength: 0, allowedValues: ['ADMIN', 'PM', 'DEVELOPER', 'QA', 'DEPLOYMENT', 'SUPPORT'], example: 'DEVELOPER' },
  { name: 'mobile', header: 'Mobile', required: false, naturalKey: false, type: 'TEXT', maxLength: 20, allowedValues: [], example: '+91 98200 11223' },
  { name: 'status', header: 'Status', required: false, naturalKey: false, type: 'ENUM', maxLength: 0, allowedValues: ['ACTIVE', 'INACTIVE'], example: 'ACTIVE' },
  { name: 'department', header: 'Department', required: false, naturalKey: false, type: 'TEXT', maxLength: 80, allowedValues: [], example: 'Engineering' },
  { name: 'designation', header: 'Designation', required: false, naturalKey: false, type: 'TEXT', maxLength: 80, allowedValues: [], example: 'Senior Engineer' },
  { name: 'location', header: 'Location', required: false, naturalKey: false, type: 'TEXT', maxLength: 120, allowedValues: [], example: 'Pune' },
  { name: 'dateOfJoining', header: 'Date of Joining', required: false, naturalKey: false, type: 'DATE', maxLength: 0, allowedValues: [], example: '2026-04-01' },
  { name: 'dailyCapacityHrs', header: 'Daily Capacity (hrs)', required: false, naturalKey: false, type: 'TEXT', maxLength: 5, allowedValues: [], example: '8' },
  { name: 'timezone', header: 'Timezone', required: false, naturalKey: false, type: 'TEXT', maxLength: 50, allowedValues: [], example: 'Asia/Kolkata' },
  { name: 'weeklyOff', header: 'Weekly Off', required: false, naturalKey: false, type: 'TEXT', maxLength: 20, allowedValues: [], example: '6, 7' },
  { name: 'skills', header: 'Skills', required: false, naturalKey: false, type: 'TEXT', maxLength: 400, allowedValues: [], example: 'Java, React, MySQL' },
];

/**
 * B-038 · everything the mock needs to answer for one registration.
 *
 * The real engine resolves a path segment to a Spring `@Component` and every
 * route after that is schema-blind. The mock cannot do that — it has no
 * registry — so this is the nearest honest equivalent: one table, looked up by
 * the same key the URL carries, and no handler below branching on the schema
 * itself.
 *
 * An unknown segment falls back to the client registration rather than 404ing,
 * which is deliberately *unlike* the server. The mock exists so screens can be
 * built and tested; answering 404 for a typo would fail a component test with a
 * network error instead of a readable assertion, and the contract's enum already
 * makes an unknown segment unreachable from the generated client.
 */
const IMPORT_REGISTRATIONS = {
  clients: {
    entity: 'CLIENT',
    naturalKey: 'clientCode',
    fields: CLIENT_IMPORT_FIELDS,
    fileName: 'clients.xlsx',
    sheets: ['Clients', 'Archive'],
    headers: ['Client Code', 'Name', 'Website Domain', 'Support Plan', 'Status', 'Account Manager'],
    suggestedMapping: {
      clientCode: 'Client Code', name: 'Name', websiteDomain: 'Website Domain',
      supportPlan: 'Support Plan', status: 'Status',
    },
  },
  users: {
    entity: 'RESOURCE',
    naturalKey: 'employeeCode',
    fields: RESOURCE_IMPORT_FIELDS,
    fileName: 'joiners.xlsx',
    sheets: ['Joiners', 'Leavers'],
    // `Reporting Manager` plays the part `Account Manager` plays for clients: a
    // column an HR export really does carry and the import deliberately has no
    // home for, so step 3's "will not be imported" notice is exercised by the
    // exact case it exists for.
    headers: ['Employee Code', 'Full Name', 'Username', 'Email', 'Role', 'Reporting Manager'],
    suggestedMapping: {
      employeeCode: 'Employee Code', fullName: 'Full Name', username: 'Username',
      email: 'Email', role: 'Role',
    },
  },
} as const;

const registration = (schema: unknown) =>
  IMPORT_REGISTRATIONS[String(schema) as keyof typeof IMPORT_REGISTRATIONS]
  ?? IMPORT_REGISTRATIONS.clients;

/**
 * The media type both import downloads carry — the template (B-031) and the
 * error report (B-036). Named once because a client that branches on it would
 * be reading a string two handlers had to agree about.
 */
const XLSX_MEDIA_TYPE =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/**
 * B-034 · the step-4 preview, built rather than listed.
 *
 * **The counts and the rows have to agree**, and a hand-written fixture of four
 * rows next to `willCreate: 120` does not: the screen shows "All 128" from
 * `rows.length` and four tabs summing to 128 from the counts, and the two would
 * contradict each other on the one screen whose whole job is being believable.
 * The real `ImportPreview` derives its counts from its rows for exactly that
 * reason, so this derives them too.
 *
 * 128 rows, matching what step 2's mock says the Clients sheet holds. That also
 * gives the fifty-a-row paging something to page.
 *
 * Every verdict carries the kind of message its real counterpart does — a
 * rejection names the rule, a duplicate names the winning row, and an update
 * names the fields it would change, which is the one this screen is built
 * around.
 */
function importPreview(schema: unknown = 'clients') {
  return schema === 'users' ? resourceImportPreview() : clientImportPreview();
}

/**
 * B-038 · the step-4 preview for the resource registration.
 *
 * Its own function rather than the client one with the words swapped, for the
 * reason the field lists are separate: the verdicts that matter differ. A
 * rejected client row is usually a malformed email; a rejected joiner row is
 * usually a role somebody typed as "Dev" — which is the case the ENUM dropdown
 * on the template exists to prevent and the one an Admin needs to see named.
 *
 * Smaller than the client fixture (34 rows against 128) and deliberately so: a
 * joiner list is a month's hiring, not a CRM export, and a preview claiming four
 * hundred new employees would be the wrong thing to design a screen against.
 */
function resourceImportPreview() {
  const rows: {
    rowNumber: number;
    verdict: string;
    reason: string | null;
    values: Record<string, string>;
  }[] = [];

  for (let i = 0; i < 28; i++) {
    rows.push({
      rowNumber: rows.length + 2,
      verdict: 'WILL_CREATE',
      reason: null,
      values: {
        employeeCode: `EDU-${String(2001 + i)}`,
        fullName: `New Joiner ${i + 1}`,
      },
    });
  }

  const updates = [
    ['EDU-0142', 'Asha Menon', 'Designation, Mobile'],
    ['EDU-0198', 'Bhavin Rao', 'Department'],
    // The row that reads oddly and is right: an upsert of a row matching what is
    // stored is still an update, and "No change" is worth more than a blank cell.
    ['EDU-0203', 'Chitra Iyer', 'No change'],
  ] as const;
  for (const [employeeCode, fullName, reason] of updates) {
    rows.push({
      rowNumber: rows.length + 2,
      verdict: 'WILL_UPDATE',
      reason,
      values: { employeeCode, fullName },
    });
  }

  rows.push({
    rowNumber: rows.length + 2,
    verdict: 'REJECTED',
    reason: 'Role: Must be one of: ADMIN, PM, DEVELOPER, QA, DEPLOYMENT, SUPPORT',
    values: { employeeCode: 'EDU-0311', fullName: 'Wrong Role', role: 'Dev' },
  });
  // Blueprint §4B.3's own row 5: a blank natural key, so the column has nothing
  // to show and renders as "(blank)".
  rows.push({
    rowNumber: rows.length + 2,
    verdict: 'REJECTED',
    reason: 'Employee Code required',
    values: { fullName: 'No Code Here' },
  });
  rows.push({
    rowNumber: rows.length + 2,
    verdict: 'DUPLICATE_IN_FILE',
    reason: 'Row 2 wins',
    values: { employeeCode: 'EDU-2001', fullName: 'New Joiner 1 (again)' },
  });

  const count = (verdict: string) => rows.filter((row) => row.verdict === verdict).length;
  return {
    willCreate: count('WILL_CREATE'),
    willUpdate: count('WILL_UPDATE'),
    duplicates: count('DUPLICATE_IN_FILE'),
    rejected: count('REJECTED'),
    rows,
  };
}

function clientImportPreview() {
  const rows: {
    rowNumber: number;
    verdict: string;
    reason: string | null;
    values: Record<string, string>;
  }[] = [];

  for (let i = 0; i < 120; i++) {
    rows.push({
      rowNumber: rows.length + 2,
      verdict: 'WILL_CREATE',
      reason: null,
      values: { clientCode: `NEWCO${String(i + 1).padStart(3, '0')}`, name: `Newco ${i + 1} Ltd` },
    });
  }

  const updates = [
    ['ACME', 'Acme Retail Limited', 'Name, Phone'],
    ['NORTHWIND', 'Northwind Traders', 'Name'],
    ['ZENITH', 'Zenith Systems', 'Support Plan, Primary Email'],
    // The one that reads oddly and is right: an upsert of a row that matches
    // what is stored is still an update, and saying "No change" is worth more
    // than a blank cell the user has to interpret.
    ['GLOBEX', 'Globex Corporation', 'No change'],
    // A registration that could not supply current values answers null, which
    // the screen must render as a stated unknown rather than as "no change".
    ['INITECH', 'Initech Ltd', null],
  ] as const;
  for (const [clientCode, name, reason] of updates) {
    rows.push({
      rowNumber: rows.length + 2,
      verdict: 'WILL_UPDATE',
      reason,
      values: { clientCode, name },
    });
  }

  rows.push({
    rowNumber: rows.length + 2,
    verdict: 'REJECTED',
    reason: 'Primary Email: Invalid email',
    values: { clientCode: 'BADCO', primaryEmail: 'not-an-email' },
  });
  // Blueprint §4B.3's own row 5: a blank code, so the natural-key column has
  // nothing to show and renders as "(blank)".
  rows.push({
    rowNumber: rows.length + 2,
    verdict: 'REJECTED',
    reason: 'Client Code required',
    values: { name: 'No Code Here' },
  });
  rows.push({
    rowNumber: rows.length + 2,
    verdict: 'DUPLICATE_IN_FILE',
    reason: 'Row 2 wins',
    values: { clientCode: 'NEWCO001', name: 'Newco 1 Ltd (again)' },
  });

  const count = (verdict: string) => rows.filter((row) => row.verdict === verdict).length;
  return {
    willCreate: count('WILL_CREATE'),
    willUpdate: count('WILL_UPDATE'),
    duplicates: count('DUPLICATE_IN_FILE'),
    rejected: count('REJECTED'),
    rows,
  };
}

/**
 * A-065 · the eighteen report titles, for a schedule row that has to name the
 * report it belongs to.
 *
 * The catalogue handler has its own copy in a closure; this is the third place
 * the vocabulary appears in this file and the second in this module. Left as a
 * lookup rather than hoisting the catalogue's table, because that table also
 * carries category and chart type — pulling it out to share one column would
 * make the catalogue handler read from a structure shaped for somewhere else.
 * The key is returned unchanged for anything unlisted, which is what the server
 * does too.
 */
function reportTitleOf(reportKey: string): string {
  const titles: Record<string, string> = {
    'date-wise': 'Date-wise Report',
    'resource-scorecard': 'Resource Performance Scorecard',
    'resource-velocity': 'Resource Velocity',
    'effort-summary': 'Effort Summary',
    'resource-contribution': 'Resource Contribution',
    'project-health': 'Project Health',
    aging: 'Aging Report',
    'sla-breach': 'Delayed / SLA Breach',
    'workload-capacity': 'Workload & Capacity',
    'reopen-analysis': 'Reopen Analysis',
    'rework-analysis': 'Rework Analysis',
    'task-type-analysis': 'Task Type Analysis',
    'stage-funnel': 'Stage Funnel',
    'stage-cycle-time': 'Stage Cycle Time',
    'deployment-report': 'Deployment Report',
    'client-report': 'Client Report',
    'audit-compliance': 'Audit & Compliance',
    'email-delivery-log': 'Email Delivery Log',
  };
  return titles[reportKey] ?? reportKey;
}

/**
 * A-065 · a schedule's stored filters never include a date range.
 *
 * The period comes from the cadence, so a stored window would make every run
 * cover the same days for ever. Modelled here as well as on the server so a
 * screen built against the mock cannot come to rely on dates surviving.
 */
/** A-065 · the signed-in user's address, for "am I a recipient of this?". */
function meEmail(): string {
  const db = getDb();
  return db.users.find((u) => u.id === db.currentUserId)?.email ?? '';
}

function withoutDates(parameters: Record<string, unknown>): Record<string, unknown> {
  const kept = { ...parameters };
  delete kept.from;
  delete kept.to;
  return kept;
}

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
  /*
    A-069 · reshaped for S-28. Stream A editing Stream D's mocks directory
    (D-004) again, for coverage.test.ts's reason: it asserts a handler per
    contract operation and names this file, so a mock left on the old shape
    would render a screen the real API cannot produce.

    The previous body returned `user`, `openTickets`, `closedThisMonth` and a
    bare list of stage codes. The contract now carries the person, the window
    the figures cover, and a *count* per stage — a manager needs to see that
    eleven of fourteen are stuck in one stage, which a list of names does not
    say.

    Rates are computed rather than hardcoded at 87.5 and 12.0. A mock that
    always reports the same compliance cannot show the null case, and null —
    nothing closed, so nothing could have been on time — is the state most
    worth seeing rendered as an em dash rather than 0%.
  */
  http.get(url('/users/:userId/profile-360'), ({ params }) => {
    const db = getDb();
    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');

    const mine = db.tickets.filter((t) => t.assigneeId === u.id);
    const closed = mine.filter((t) => t.status === 'CLOSED');
    const committed = closed.filter((t) => t.plannedCloseDate);
    const onTime = committed.filter(
      (t) => !t.actualCloseDate || t.actualCloseDate <= (t.plannedCloseDate ?? ""),
    );
    const reopened = closed.filter((t) => (t.reopenCount ?? 0) > 0);

    const stages = new Map();
    for (const t of mine.filter((x) => x.status !== 'CLOSED')) {
      const key = t.currentStageCode ?? '(none)';
      stages.set(key, (stages.get(key) ?? 0) + 1);
    }

    const manager = db.users.find((x) => x.id === u.reportingManagerId);
    const pct = (n: number, d: number) => (d === 0 ? null : round((n * 100) / d));

    return ok({
      person: {
        id: u.id,
        fullName: u.displayName,
        username: u.username,
        email: u.email,
        role: u.role,
        department: u.department ?? null,
        designation: u.designation ?? null,
        active: u.isActive !== false,
        joinedOn: u.dateOfJoining ?? null,
        managerName: manager ? manager.displayName : null,
      },
      from: '2026-07-19',
      to: '2026-08-18',
      openNow: mine.filter((t) => t.status !== 'CLOSED').length,
      closedInWindow: closed.length,
      effortHours: round(
        db.effortLogs.filter((e) => e.userId === u.id).reduce((s, e) => s + e.hours, 0),
      ),
      slaCompliancePct: pct(onTime.length, committed.length),
      reworkRatePct: pct(reopened.length, closed.length),
      currentStages: [...stages.entries()]
        .map(([stage, openCount]) => ({ stage, openCount }))
        .sort((a, b) => b.openCount - a.openCount),
    });
  }),
  /*
    B-063 · §21's timesheet, one person's week laid out as ticket × stage.

    ⚠️ Stream B, in Stream D's `mocks/` — the mock-coverage test (D-004) refuses
    a contract operation with no handler, so the route brings its handler with
    it. Flagged rather than quiet, exactly as A-065's three routes are above.

    Capacity is computed from the seeded calendar rather than assumed to be
    eight hours: `weeklyOff`, org holidays (recurring ones matched on month and
    day) and this resource's approved leave, with a half day counted as half.
    That is the same rule `WorkingHoursService` applies server-side, and a mock
    that returned a flat 8 would make the utilisation figure — the one number on
    the screen worth checking — look right in dev and wrong in production.

    Visibility is deliberately not enforced here, following the profile-360
    handler directly above: the mock server has never modelled row scoping, and
    a screen built against a mock that refused would be built against a rule the
    mock cannot get right anyway. The server answers 404, and the page treats an
    error as "not yours to see" without needing the mock to prove it.
  */
  http.get(url('/users/:userId/timesheet'), ({ params, request }) => {
    const db = getDb();
    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');

    const weekOf = new URL(request.url).searchParams.get('weekOf') ?? isoToday();
    const weekStart = mondayOf(weekOf);
    const dates = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
    const weekEnd = dates[6];

    const entries = db.effortLogs.filter(
      (e) => e.userId === u.id && e.workDate >= weekStart && e.workDate <= weekEnd,
    );

    type Row = {
      ticketId: string; ticketTitle: string;
      project: ReturnType<typeof projectRef>;
      stage: string | null; stageName: string | null;
      iterationNo: number; cycleNo: number;
      hours: Record<string, number>; totalHours: number; hasCorrection: boolean;
    };

    const rows = new Map<string, Row>();
    for (const e of entries) {
      const key = `${e.ticketId}|${e.stageCode ?? ''}|${e.iterationNo}|${e.cycleNo}`;
      const t = db.tickets.find((x) => x.ticketId === e.ticketId);
      // The stage's display name by code alone. The server resolves it through
      // the ticket's own workflow template, because two templates may name the
      // same code differently; the mock's tickets do not carry a template id,
      // and inventing one to model that distinction would be modelling it wrong
      // rather than not at all.
      const stage = db.templateStages.find((x) => x.stageCode === e.stageCode);

      const row = rows.get(key) ?? {
        ticketId: e.ticketId,
        ticketTitle: t?.title ?? '',
        project: t ? projectRef(t.projectId, db) : null,
        stage: e.stageCode ?? null,
        stageName: stage?.displayName ?? null,
        iterationNo: e.iterationNo,
        cycleNo: e.cycleNo,
        hours: {},
        totalHours: 0,
        hasCorrection: false,
      };

      row.hours[e.workDate] = round2((row.hours[e.workDate] ?? 0) + e.hours);
      row.totalHours = round2(row.totalHours + e.hours);
      row.hasCorrection = row.hasCorrection || Boolean(e.isCorrection);
      rows.set(key, row);
    }

    const days = dates.map((date) => {
      const capacityHours = capacityOn(u.id, date);
      const loggedHours = round2(
        entries.filter((e) => e.workDate === date).reduce((s, e) => s + e.hours, 0),
      );
      return { date, capacityHours, loggedHours, isWorkingDay: capacityHours > 0 };
    });

    const totalHours = round2(days.reduce((s, d) => s + d.loggedHours, 0));
    const capacityHours = round2(days.reduce((s, d) => s + d.capacityHours, 0));

    return ok({
      person: userRef(u.id, db),
      weekStart,
      weekEnd,
      days,
      // Busiest first, matching the server: a week should read top-down as
      // where the time actually went.
      rows: [...rows.values()].sort(
        (a, b) => b.totalHours - a.totalHours || a.ticketId.localeCompare(b.ticketId),
      ),
      totalHours,
      capacityHours,
      // Null rather than 0% when nothing was available — a week nobody was
      // expected to work makes no claim about how it was spent.
      utilisationPct:
        capacityHours > 0 ? Math.round((totalHours / capacityHours) * 1000) / 10 : null,
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
        'Content-Type': XLSX_MEDIA_TYPE,
        'Content-Disposition': 'attachment; filename="import-template.xlsx"',
      },
    }),
  ),
  // B-032 · step 2, answering as a two-sheet workbook so the multi-sheet
  // selector has something to exercise. `?sheet=` is honoured and the row count
  // moves with it: a selector that only re-styled a button would otherwise pass,
  // and it is the one control on this screen with state.
  //
  // The uploaded file is deliberately not read. Under vitest no genuine
  // multipart body reaches a handler — jsdom supplies FormData while Node
  // supplies Request, and Node stringifies the foreign object to the literal
  // `[object FormData]` — which `useTicketAttachments.test.tsx` documents at
  // length. `request.formData()` here does not fail, it *hangs*, and every test
  // that uploads times out with no hint why. The name is echoed as a constant
  // instead; the screen shows the browser's own File name anyway.
  http.post(url('/imports/:schema/upload'), ({ params, request }) => {
    const schema = registration(params.schema);
    const sheets: string[] = [...schema.sheets];
    const requested = new URL(request.url).searchParams.get('sheet');
    const sheet = requested && sheets.includes(requested) ? requested : sheets[0];

    return ok({
      uploadId: '11111111-2222-3333-4444-555555555555',
      fileName: schema.fileName,
      sheets, sheet,
      rowCount: sheet === sheets[0] ? importPreview(params.schema).rows.length : 12,
      // B-033 corrected two of these. `suggestedMapping` was keyed `domain` and
      // `isActive`, which are not fields `ClientImportSchema` declares — the
      // real names are `websiteDomain` and `status`. Harmless while step 2 only
      // counted the entries; step 3 renders a row per declared field and looks
      // the mapping up by name, so the stale keys would have shown two columns
      // as matched in the summary and unmapped in the table.
      //
      // `Account Manager` is a column the import deliberately has no home for
      // (see ClientImportSchema: it is a foreign key and a spreadsheet carries
      // only a name), so it exercises step 3's "will not be imported" notice
      // with the exact case that notice exists for.
      headers: [...schema.headers],
      suggestedMapping: { ...schema.suggestedMapping },
    });
  }),
  // B-033 · step 3. The columns the import accepts, mirroring
  // `ClientImportSchema.fields()` — in template order, with the same headers the
  // template writes and the same `allowedValues` its dropdowns are built from.
  //
  // Mirrored in full rather than sampled: the whole point of the route is that
  // the field list is declared once server-side, and a fixture holding five of
  // twenty would let a screen be built against a client master that does not
  // exist. The staleness this can still develop is what the OpenAPI check and
  // the real backend catch.
  http.get(url('/imports/:schema/fields'), ({ params }) => {
    const schema = registration(params.schema);
    return ok({
      schema: String(params.schema),
      entity: schema.entity,
      naturalKey: schema.naturalKey,
      fields: schema.fields,
    });
  }),
  // §4B.3: "Mapping presets can be saved and reused for the next import."
  // Org-wide, so no caller identity is read — every Admin sees the same list.
  http.get(url('/imports/:schema/mapping-presets'), ({ params }) => {
    const db = getDb();
    return ok([...(db.mappingPresets[String(params.schema)] ?? [])]
      .sort((a, b) => a.name.localeCompare(b.name)));
  }),
  // An upsert on (schema, name), so 200 and not 201 — and saving under a name
  // that exists replaces it rather than adding a second entry the picker could
  // not tell apart. Case-insensitive, matching the table's own collation.
  http.post(url('/imports/:schema/mapping-presets'), async ({ params, request }) => {
    const body = (await request.json()) as { name?: string; mapping?: Record<string, string> };
    const schema = String(params.schema);
    const name = (body.name ?? '').trim();
    const mapping = Object.fromEntries(
      Object.entries(body.mapping ?? {}).filter(([, column]) => column.trim().length > 0),
    );

    if (name.length === 0 || Object.keys(mapping).length === 0) {
      return problem(400, 'validation-failed', 'Mapping preset was not saved', {
        detail: 'A preset must have a name and map at least one column.',
        errors: { mapping: ['A preset must map at least one column.'] },
      });
    }

    // The 422 the real service answers: a mapping may only name fields the
    // schema declares, because a preset is applied weeks later against a file
    // nobody is looking at today.
    const declared = new Set(CLIENT_IMPORT_FIELDS.map((field) => field.name));
    const unknown = Object.keys(mapping).filter((field) => !declared.has(field));
    if (unknown.length > 0) {
      return problem(422, 'import-unknown-field', 'Unknown import field', {
        detail: `The '${schema}' import declares no field called ${unknown.join(', ')}.`,
        unknownFields: unknown,
        fields: [...declared],
      });
    }

    const db = getDb();
    const saved = db.mappingPresets[schema] ?? (db.mappingPresets[schema] = []);
    const existing = saved.find(
      (preset) => preset.name.toLowerCase() === name.toLowerCase(),
    );
    const updatedAt = '2026-08-17T09:00:00Z';

    if (existing) {
      existing.mapping = mapping;
      existing.updatedAt = updatedAt;
      return ok(existing);
    }

    const preset = { presetId: nextId(db, 'mappingPreset'), name, mapping, updatedAt };
    saved.push(preset);
    return ok(preset);
  }),
  http.delete(url('/imports/:schema/mapping-presets/:presetId'), ({ params }) => {
    const db = getDb();
    const schema = String(params.schema);
    const saved = db.mappingPresets[schema] ?? [];
    const at = saved.findIndex((preset) => preset.presetId === Number(params.presetId));

    if (at < 0) {
      // Scoped by schema as well as by id, and a 404 rather than a cheerful 204:
      // the ordinary case is another Admin having deleted it, and the picker has
      // to know to drop the entry.
      return problem(404, 'not-found', 'Mapping preset not found', {
        detail: `No mapping preset ${params.presetId} saved for the '${schema}' import.`,
        presetId: Number(params.presetId),
      });
    }

    saved.splice(at, 1);
    return noContent();
  }),
  // B-034 · step 4. A dry run writes nothing and shows a per-row verdict — the
  // step that makes a bulk import safe to run at all.
  http.post(url('/imports/:schema/validate'), ({ params }) =>
    ok(importPreview(params.schema)),
  ),
  // B-035 · step 5. The fixture was rebuilt here for the reason B-034 rebuilt
  // step 4's, and it had two of the same class of defect:
  //
  //   1. `batchId` was a UUID string. `import_batches.id` is a BIGINT and the
  //      contract has said `integer, int64` since B-030 read the baseline DDL —
  //      so the generated client's own types reject what this answered, and
  //      `useGetImportBatch(batchId)` takes a number.
  //   2. The two handlers disagreed with each other. Commit answered
  //      `rejected: 0` and the poll answered `rejected: 3` for the same run, and
  //      the poll answered COMPLETED on its first call — so the progress bar,
  //      the one control this step exists to show, could never be seen moving.
  //
  // It now holds a real batch in the db and advances it a little on every poll,
  // which is what the real runner's fifty-row flush looks like from the client.
  http.post(url('/imports/:schema/commit'), async ({ params, request }) => {
    const body = (await request.json()) as { mapping?: Record<string, string> };

    // The refusal that is worth having in the mock, because it is the one a
    // screen gets wrong: the server re-derives the verdicts, so a commit can be
    // refused for a mapping step 4 accepted.
    if (!body.mapping || Object.keys(body.mapping).length === 0) {
      return problem(400, 'validation-failed', 'Import was not started', {
        detail: 'A commit must carry the column mapping.',
        errors: { mapping: ['A commit must carry the column mapping.'] },
      });
    }

    const db = getDb();
    const schema = registration(params.schema);
    const preview = importPreview(params.schema);
    const rejected = preview.rejected + preview.duplicates;
    const batch = {
      batchId: nextId(db, 'importBatch'),
      entity: schema.entity,
      fileName: schema.fileName,
      status: 'QUEUED' as const,
      processed: rejected,
      total: preview.rows.length,
      created: 0,
      updated: 0,
      rejected,
      errorReportUrl: null,
      // B-037 · a run that has just been queued: it has a start time and an
      // actor, and it is not reversible, because nothing has finished writing.
      // `reversible: false` here is the fixture's most load-bearing field —
      // a mock that sent `true` on a QUEUED batch would let a screen ship with a
      // Reverse button on a running import.
      startedAt: new Date().toISOString(),
      importedBy: 1,
      importedByName: 'Anita Desai',
      reversedAt: null,
      reversedRows: 0,
      retainedRows: 0,
      reversible: false,
    };
    db.importBatches[batch.batchId] = batch;
    return ok(batch, undefined, { status: 202 });
  }),
  // ── B-037 · traceability and reversal ────────────────────────────────────
  //
  // Declared BEFORE `/import-batches/:batchId`, because msw matches in order and
  // `/import-batches` would otherwise never be reached.
  http.get(url('/import-batches'), ({ request }) => {
    const db = getDb();
    const entity = new URL(request.url).searchParams.get('entity') ?? 'CLIENT';
    const batches = Object.values(db.importBatches)
      .filter((batch) => batch.entity === entity)
      // Newest first, like `ix_import_batches_entity (entity, created_at)` walked
      // backwards. Sorted rather than relying on insertion order: the seeded runs
      // and a run committed this session arrive in the object in id order, which
      // happens to agree today and would stop agreeing the moment anything
      // back-dates a fixture.
      .sort((a, b) => b.startedAt.localeCompare(a.startedAt));

    // `limit` is on the response, not applied silently — the panel says "showing
    // the 50 most recent" from this number rather than hardcoding one.
    return ok({ entity, batches, limit: 50 });
  }),
  // B-037 · reverse one run as a set.
  //
  // The two refusals are modelled because they are the two the screen branches
  // on, and the branch is the point: one clears itself and the other never does,
  // so a mock that answered a single "cannot reverse" would let a Try again ship
  // on a batch that will refuse forever.
  http.post(url('/import-batches/:batchId/reverse'), ({ params }) => {
    const db = getDb();
    const batchId = Number(params.batchId);
    const batch = db.importBatches[batchId];

    if (!batch) {
      return problem(404, 'not-found', 'Import batch not found', {
        detail: `No import batch ${batchId} exists.`,
        batchId,
      });
    }
    if (batch.status === 'QUEUED' || batch.status === 'RUNNING') {
      return problem(422, 'import-batch-not-finished', 'This import has not finished', {
        detail: `Import #${batchId} is still ${batch.status.toLowerCase()}. A run can only be reversed once it has finished.`,
        batchId,
        status: batch.status,
      });
    }
    if (batch.reversedAt) {
      return problem(422, 'import-batch-already-reversed', 'This import has already been reversed', {
        detail: `Import #${batchId} has already been reversed. A run can only be reversed once.`,
        batchId,
        reversedAt: batch.reversedAt,
      });
    }

    // One retained row whenever the run created enough to have one, because a
    // clean reversal is the case a screen gets right by accident and a partial
    // one is the case it gets wrong. `retained` is named, not counted — the
    // dialog deliberately promises no count beforehand, so this list is the only
    // place a user learns which clients survived.
    const retained =
      batch.created > 1
        ? [
            {
              naturalKey: 'NORTHWIND',
              reason: 'Kept — 3 tickets have been raised against this client since the import.',
            },
          ]
        : [];
    const deleted = Array.from(
      { length: Math.max(0, batch.created - retained.length) },
      (_, index) => `IMP-${batchId}-${String(index + 1).padStart(3, '0')}`,
    );

    batch.reversedAt = new Date().toISOString();
    batch.reversedRows = deleted.length;
    batch.retainedRows = retained.length;
    batch.reversible = false;

    return ok({
      batch,
      deleted,
      retained,
      // The honest half of the promise: rows the run UPDATED are not restored,
      // and the screen says so. Taken from the batch's own counter rather than
      // invented, so the sentence the user reads matches the row above it.
      updatedRowsNotReverted: batch.updated,
    });
  }),
  http.get(url('/import-batches/:batchId'), ({ params, request }) => {
    const db = getDb();
    const batch = db.importBatches[Number(params.batchId)];
    if (!batch) {
      return problem(404, 'not-found', 'Import batch not found', {
        detail: `No import batch ${params.batchId} exists.`,
        batchId: Number(params.batchId),
      });
    }

    // One step of the run per poll. `writable` is what the commit actually
    // writes, and creates are settled before updates so the two counters move
    // the way a file ordered by row number would move them.
    //
    // B-038 · the run's own registration, read off the stored batch. The poll
    // has no schema in its path — `/import-batches/{id}` is keyed on the run,
    // not on what it imported — which is exactly why `entity` is a stored
    // discriminator rather than something reconstructed from a URL.
    const preview = batch.entity === 'RESOURCE'
      ? importPreview('users')
      : importPreview('clients');
    const writable = preview.willCreate + preview.willUpdate;
    if (batch.created + batch.updated < writable) {
      batch.status = 'RUNNING';
      if (batch.created < preview.willCreate) {
        batch.created += 1;
      } else {
        batch.updated += 1;
      }
      batch.processed = batch.created + batch.updated + batch.rejected;
    } else {
      batch.status = 'COMPLETED';
      // B-036 · the report appears on the same step that makes the run terminal,
      // never a later one — the real runner writes the key in the transaction
      // that sets the status, because a client stops polling the moment it reads
      // COMPLETED. A mock that stamped it on the next poll would let a screen
      // ship that never sees a report, and every poll after that one is a 304.
      //
      // Null when nothing was rejected: the field says whether there is a report,
      // and a client composing the path itself would ask for one that is not
      // there.
      batch.errorReportUrl =
        batch.rejected > 0 ? `/import-batches/${batch.batchId}/error-report` : null;
      // B-037 · reversible on the same step that makes the run terminal, and for
      // the same reason the report key is: a client stops polling the moment it
      // reads COMPLETED, so a flag flipped on a later poll is a Reverse button
      // the screen that wanted it has already stopped asking about.
      batch.reversible = batch.reversedAt === null;
    }

    // The ETag is over the counters *and the report URL*, like the real one —
    // otherwise the last poll of a run, where only the URL appears, would be a
    // 304 and the download button would never enable.
    // B-037 · `reversible` is in the tag too. The real DTO hashes it because a
    // validator that does not cover every field of the representation is a 304
    // that withholds a change — and this is the field a browser would then be
    // holding a stale `false` for, on the one control that deletes rows.
    const etag = `W/"batch-${batch.batchId}-${batch.status}-${batch.processed}-${batch.errorReportUrl ? 'r' : 'x'}-${batch.reversible ? 'v' : 'n'}"`;
    if (request.headers.get('If-None-Match') === etag) {
      return new HttpResponse(null, { status: 304, headers: { ETag: etag } });
    }
    return ok(batch, undefined, { headers: { ETag: etag } });
  }),
  // B-036 · the error report. A real `.xlsx` is not what this needs to be — no
  // test opens it — but a `Blob` with the server's content type and, above all,
  // **the server's `Content-Disposition` name** is: that header is the whole
  // reason `fetchImportErrorReport` exists instead of the generated hook, and a
  // mock that omitted it would let a screen ship that saves every report under
  // one name.
  //
  // The 404 is modelled too, because it is the one a bookmark hits and the one
  // the screen has to render: a batch that is real and has no report.
  http.get(url('/import-batches/:batchId/error-report'), ({ params }) => {
    const db = getDb();
    const batchId = Number(params.batchId);
    const batch = db.importBatches[batchId];

    if (!batch) {
      return problem(404, 'not-found', 'Import batch not found', {
        detail: `No import batch ${batchId} exists.`,
        batchId,
      });
    }
    if (!batch.errorReportUrl) {
      return problem(404, 'not-found', 'Import error report not available', {
        detail: `Import batch ${batchId} has no error report to download (status ${batch.status}).`,
        batchId,
        status: batch.status,
      });
    }

    return new HttpResponse(
      // Enough bytes to be a file and not enough to pretend to be a workbook.
      new Blob([`Row,Client Code,Reason\n`], { type: XLSX_MEDIA_TYPE }),
      {
        status: 200,
        headers: {
          'Content-Type': XLSX_MEDIA_TYPE,
          'Content-Disposition': `attachment; filename="clients-import-errors-${batchId}.xlsx"`,
        },
      },
    );
  }),

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
  // ── statuses and the transition matrix · S-13 tab 1 (B-039) ───────────────
  // Neither table had a contract path, a mock or a client before B-039 — two
  // seeded masters, eighty-two rows, reachable only by a migration. So unlike
  // the priorities above, nothing here is being corrected; it is all new.
  //
  // **Active-only by default**, following the priorities rather than the task
  // types: a retired status handed to a ticket screen's status filter offers a
  // value matching no ticket anybody can still create.
  http.get(url('/masters/statuses'), ({ request }) => {
    const includeInactive =
      new URL(request.url).searchParams.get('includeInactive') === 'true';
    return ok(
      getDb().statuses
        .filter((s) => includeInactive || s.isActive)
        .sort((a, b) => a.seq - b.seq || a.id - b.id)
        .map(statusDto),
    );
  }),

  http.get(url('/masters/statuses/:statusId'), ({ params }) => {
    const status = getDb().statuses.find((s) => s.id === Number(params.statusId));
    if (!status) return notFound('Status');
    return ok(statusDto(status), undefined, { headers: { ETag: statusEtag(status) } });
  }),

  http.post(url('/masters/statuses'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Partial<Status>;
    const code = String(body.code ?? '').trim().toUpperCase() as StatusCode;

    // The headline refusal, mirrored from `StatusService`. A mock that accepted
    // a ninth status would let the S-13 form ship with no handling for the one
    // error it will actually see.
    if (!CONTRACT_STATUS_CODES.includes(code)) {
      return validationFailed({
        code: [`'${code}' is not one of the eight statuses this release supports `
          + `(${[...CONTRACT_STATUS_CODES].sort().join(', ')}). The contract's StatusCode `
          + 'enum types tickets.status on every response, so a ninth code would be '
          + "rejected by the generated client's own validation before any screen "
          + 'rendered it. Opening the set is a coordinated change across '
          + 'contracts/openapi.yaml (Stream D), the ticket screens (Stream C) and the '
          + 'summary tables (Stream A) — not one this screen can make alone.'],
      });
    }
    if (db.statuses.some((s) => s.code === code)) {
      const detail = `A status with code '${code}' already exists. To bring back a `
        + 'retired one, reactivate it instead.';
      return problem(409, 'duplicate', detail, { errors: { code: [detail] } });
    }

    const name = String(body.name ?? '').trim();
    const clash = db.statuses.find((s) => s.name.toLowerCase() === name.toLowerCase());
    if (clash) {
      const detail = `'${clash.name}' already exists. Two statuses with the same name `
        + 'are indistinguishable in the ticket grid, in every status filter and on the '
        + 'board.';
      return problem(409, 'duplicate', detail, { errors: { name: [detail] } });
    }

    const isOpen = body.isOpen ?? true;
    const isTerminal = body.isTerminal ?? false;
    if (isTerminal && isOpen) {
      return problem(409, 'contradictory-state', CONTRADICTORY_STATUS,
        { errors: { isTerminal: [CONTRADICTORY_STATUS] } });
    }

    const created: Status = {
      id: Math.max(0, ...db.statuses.map((s) => s.id)) + 1,
      code,
      name,
      category: (body.category ?? 'TODO') as StatusCategory,
      colour: String(body.colour ?? '').trim(),
      seq: body.seq ?? Math.max(0, ...db.statuses.map((s) => s.seq)) + 10,
      isOpen,
      isTerminal,
      isActive: body.isActive ?? true,
    };
    db.statuses.push(created);
    return ok(statusDto(created), undefined,
      { status: 201, headers: { ETag: statusEtag(created) } });
  }),

  // There is no DELETE, and the absence is the design. Nothing has a foreign key
  // to `statuses`, so a delete would *succeed* — and a status is the left-hand
  // side of every transition lookup, so deleting one strands every ticket in it
  // with no move offered on any screen.
  http.patch(url('/masters/statuses/:statusId'), async ({ params, request }) => {
    const db = getDb();
    const status = db.statuses.find((s) => s.id === Number(params.statusId));
    if (!status) return notFound('Status');

    const refusal = statusPrecondition(status, request.headers.get('If-Match'));
    if (refusal) return refusal;

    const body = (await request.json()) as Partial<Status>;

    if (body.code != null
        && String(body.code).trim().toUpperCase() !== status.code) {
      return problem(409, 'immutable-field',
        `A status code cannot be changed once created. This one is '${status.code}'. `
        + 'tickets.status stores the code and is not a foreign key, so a rename would '
        + 'not cascade — it would orphan every ticket ever raised in this status.',
        { errors: { code: [`A status code cannot be changed once created. This one is `
          + `'${status.code}'.`] } });
    }

    // The end state, derived before anything is written. Reading each flag from
    // the stored row would let `{isTerminal: true}` past a guard that saw the old
    // `isOpen` — mirrors the ordering fix in `StatusService.update`.
    const willBeActive = body.isActive ?? status.isActive;
    const willBeOpen = body.isOpen ?? status.isOpen;
    const willBeTerminal = body.isTerminal ?? status.isTerminal;

    if (willBeTerminal && willBeOpen) {
      return problem(409, 'contradictory-state', CONTRADICTORY_STATUS,
        { errors: { isTerminal: [CONTRADICTORY_STATUS] } });
    }

    const retiring = status.isActive && !willBeActive;
    if (retiring) {
      const ticketCount = db.tickets.filter((t) => t.status === status.code).length;
      if (ticketCount > 0) {
        const detail =
          `${ticketCount} ticket${ticketCount === 1 ? ' is' : 's are'} currently in `
          + `'${status.name}'. Retiring it deactivates every transition out of it, which `
          + `would leave ${ticketCount === 1 ? 'that ticket' : 'those tickets'} with no `
          + 'move offered on any screen. Move them to another status first.';
        return problem(409, 'in-use', detail,
          { ticketCount, errors: { isActive: [detail] } });
      }
    }

    if (body.name != null) status.name = String(body.name).trim();
    if (body.category != null) status.category = body.category;
    if (body.colour != null) status.colour = String(body.colour).trim();
    if (body.seq != null) status.seq = body.seq;
    status.isOpen = willBeOpen;
    status.isTerminal = willBeTerminal;
    status.isActive = willBeActive;

    // The cascade Stream C's whitelist gate cannot do for itself: it reads the
    // *transition* row's isActive and never looks at the status, so a retire that
    // left the matrix alone would go on accepting tickets into a status the master
    // says is gone. Both ends, because a move *into* it is the same disagreement.
    let deactivatedTransitions: number | null = null;
    if (retiring) {
      const affected = db.workflowTransitions.filter(
        (t) => t.isActive && (t.fromStatus === status.code || t.toStatus === status.code),
      );
      affected.forEach((t) => { t.isActive = false; });
      deactivatedTransitions = affected.length;
    }

    return ok({ ...statusDto(status), deactivatedTransitions }, undefined,
      { headers: { ETag: statusEtag(status) } });
  }),

  // The matrix is a **whitelist**: a missing (from, to, role) means the move is
  // impossible for that role. Retired rows are returned rather than filtered,
  // because the grid has to render a cell an Admin *cleared* differently from one
  // nobody ever configured.
  http.get(url('/masters/status-transitions'), ({ request }) => {
    const roleCode = new URL(request.url).searchParams.get('roleCode');
    const rows = getDb().workflowTransitions
      .filter((t) => !roleCode || t.roleCode === roleCode.toUpperCase())
      .sort((a, b) => a.id - b.id);
    return ok(rows, undefined, { headers: { ETag: matrixEtag() } });
  }),

  // PUT, not PATCH: the one invariant worth having — at least one on-create row
  // survives — is uncheckable against a single cell.
  http.put(url('/masters/status-transitions'), async ({ request }) => {
    const db = getDb();
    const refusal = matrixPrecondition(request.headers.get('If-Match'));
    if (refusal) return refusal;

    const body = (await request.json()) as {
      transitions?: {
        fromStatus?: string | null; toStatus?: string; roleCode?: string;
        requiresReason?: boolean | null; requiresEffort?: boolean | null;
      }[];
    };
    const wanted = body.transitions ?? [];

    const knownStatuses = new Set(db.statuses.map((s) => s.code));
    const knownRoles = new Set(db.roles.map((r) => r.code));
    const seen = new Set<string>();
    const cells: WorkflowTransitionRow[] = [];

    for (const row of wanted) {
      const from = (row.fromStatus ?? '').trim().toUpperCase() || null;
      const to = String(row.toStatus ?? '').trim().toUpperCase();
      const role = String(row.roleCode ?? '').trim().toUpperCase();

      // No foreign key on either column, so a wrong code is not a constraint
      // violation — it is a row that silently matches no caller, ever. Exactly
      // the defect B-008 found in the seed.
      if (from && !knownStatuses.has(from as StatusCode)) {
        return problem(409, 'validation', `'${from}' is not a status code this system knows.`,
          { errors: { fromStatus: ['Unknown status'] } });
      }
      if (!knownStatuses.has(to as StatusCode)) {
        return problem(409, 'validation', `'${to}' is not a status code this system knows.`,
          { errors: { toStatus: ['Unknown status'] } });
      }
      if (!knownRoles.has(role)) {
        return problem(409, 'validation', `'${role}' is not a role code this system knows.`,
          { errors: { roleCode: ['Unknown role'] } });
      }
      if (from === to) {
        return problem(409, 'validation',
          `'${from}' cannot transition to itself. A move that changes nothing is not a `
          + 'permission, and the unique key would store it as one.',
          { errors: { toStatus: ['Self-transition'] } });
      }
      const key = cellKey(from, to, role);
      if (seen.has(key)) {
        return problem(409, 'validation',
          `The move ${from ?? 'on creation'} -> ${to} for ${role} appears twice.`,
          { errors: { transitions: ['Duplicate cell'] } });
      }
      seen.add(key);
      cells.push({
        id: 0,
        fromStatus: from as StatusCode | null,
        toStatus: to as StatusCode,
        roleCode: role,
        requiresReason: row.requiresReason === true,
        requiresEffort: row.requiresEffort === true,
        isActive: true,
      });
    }

    // The only edit on this screen that can lock the product out of itself.
    if (!cells.some((c) => c.fromStatus === null)) {
      return problem(409, 'no-create-transition',
        'At least one on-creation move must remain. Those are the rows with no '
        + "'from' status, and they are the only way a ticket enters the system — with "
        + 'none of them, no role can raise a ticket on any screen. Every other cell can '
        + 'be cleared; this one cannot.',
        { errors: { transitions: ['At least one on-create move is required'] } });
    }

    // Upsert: an existing row keeps its id, an absent one is deactivated rather
    // than deleted. `requiresReason`/`requiresEffort` are facts an Admin
    // authored, and a cleared cell that kept them can be restored as it was.
    for (const cell of cells) {
      const existing = db.workflowTransitions.find(
        (t) => t.fromStatus === cell.fromStatus
          && t.toStatus === cell.toStatus
          && t.roleCode === cell.roleCode,
      );
      if (existing) {
        existing.requiresReason = cell.requiresReason;
        existing.requiresEffort = cell.requiresEffort;
        existing.isActive = true;
      } else {
        db.workflowTransitions.push({
          ...cell,
          id: Math.max(0, ...db.workflowTransitions.map((t) => t.id)) + 1,
        });
      }
    }
    db.workflowTransitions
      .filter((t) => t.isActive
        && !seen.has(cellKey(t.fromStatus, t.toStatus, t.roleCode)))
      .forEach((t) => { t.isActive = false; });

    return ok([...db.workflowTransitions].sort((a, b) => a.id - b.id), undefined,
      { headers: { ETag: matrixEtag() } });
  }),

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
  // ── S-13 tab 2 · the stage master (B-040) ─────────────────────────────────
  //
  // The selector, and it no longer carries stages inline. The stage set is the
  // unit of edit for the reorder and needs an `ETag` of its own, so two routes
  // serving the same rows would have meant the reorder preconditioning on a tag
  // the screen had not read the rows from.
  http.get(url('/masters/workflow-templates'), () =>
    ok(getDb().workflowTemplates.map((t) => {
      const stages = ribbon(t.id);
      return {
        ...t,
        stageCount: stages.length,
        // The vocabulary shape, not the editing one — S-25's stage filter reads
        // this array and has since C-013. See `InlineStageView` on the server.
        stages: stages.map((stage, index) => ({
          stageCode: stage.stageCode,
          displayName: stage.displayName,
          sequence: index + 1,
          ownerRole: stage.ownerRole,
          icon: stage.icon,
          stageSlaHrs: stage.slaHours,
          isOptional: stage.isOptional,
          canReturnTo: stage.canReturnTo,
          // B-042's column. A hard-coded false until now, against a
          // TicketListPage branch that has skipped deprecated codes since C-013.
          isDeprecated: stage.isDeprecated,
        })),
      };
    })),
  ),

  /**
   * B-041's, untouched by B-040 and left as the stub it has always been.
   *
   * Creating a template is the tab-3 operation — it needs the project x task-type
   * mapping that has no table yet — so there is nothing here to make faithful.
   * Kept rather than deleted because `mocks.test.ts` requires a handler for every
   * declared operation, and the alternative is deleting a contract operation
   * B-041 is going to implement.
   */
  http.post(url('/masters/workflow-templates'), async ({ request }) =>
    ok({ id: 4, isActive: true, isDefault: false, stageCount: 0, stages: [],
         ...(await request.json() as object) },
       undefined, { status: 201 }),
  ),

  http.get(url('/masters/workflow-templates/:templateId/stages'), ({ params }) => {
    const templateId = Number(params.templateId);
    if (!getDb().workflowTemplates.some((t) => t.id === templateId)) {
      return notFound('Workflow template');
    }
    const stages = ribbon(templateId);
    return ok(stages.map(stageDto), undefined, { headers: { ETag: ribbonEtag(stages) } });
  }),

  http.get(url('/masters/workflow-templates/:templateId/stages/:stageId'), ({ params }) => {
    const stage = ribbon(Number(params.templateId))
      .find((s) => s.id === Number(params.stageId));
    if (!stage) return notFound('Stage');
    return ok(stageDto(stage), undefined, { headers: { ETag: stageEtag(stage) } });
  }),

  http.post(url('/masters/workflow-templates/:templateId/stages'), async ({ request, params }) => {
    const templateId = Number(params.templateId);
    if (!getDb().workflowTemplates.some((t) => t.id === templateId)) {
      return notFound('Workflow template');
    }
    const body = (await request.json()) as {
      stageCode: string; displayName: string; ownerRole: string;
      slaHours?: number | null; isOptional?: boolean;
      canReturnTo?: string[]; icon?: string | null;
    };
    const stages = ribbon(templateId);
    const code = body.stageCode.trim().toUpperCase();

    if (stages.some((s) => s.stageCode === code)) {
      // The full sentence the server sends, not a placeholder. A mock answering
      // "Duplicate" would let the screen ship looking fine and read uselessly
      // against a real backend.
      const detail = `${code} is already a stage on this template. `
        + 'A code is unique within its template.';
      return problem(409, 'duplicate', 'Duplicate stage code',
        { detail, errors: { stageCode: [detail] } });
    }

    const created: TemplateStage = {
      id: Math.max(0, ...getDb().templateStages.map((s) => s.id)) + 1,
      templateId,
      stageCode: code,
      displayName: body.displayName.trim(),
      ownerRole: body.ownerRole,
      slaHours: body.slaHours ?? null,
      isOptional: body.isOptional ?? false,
      canReturnTo: body.canReturnTo ?? [],
      icon: body.icon ?? null,
      seq: Math.max(0, ...stages.map((s) => s.seq)) + 10,
      transitionCount: 0,
      openTicketCount: 0,
      isDeprecated: false,
      deprecatedAt: null,
    };
    getDb().templateStages.push(created);
    return ok(stageDto(created), undefined, {
      status: 201, headers: { ETag: stageEtag(created) },
    });
  }),

  http.patch(url('/masters/workflow-templates/:templateId/stages/:stageId'),
    async ({ request, params }) => {
      const stage = ribbon(Number(params.templateId))
        .find((s) => s.id === Number(params.stageId));
      if (!stage) return notFound('Stage');

      const stale = stagePrecondition(request.headers.get('If-Match'), stageEtag(stage), 'stage');
      if (stale) return stale;

      const body = (await request.json()) as Record<string, unknown>;

      if (typeof body.stageCode === 'string') {
        const code = body.stageCode.trim().toUpperCase();
        if (code !== stage.stageCode) {
          if (stage.transitionCount > 0 || stage.openTicketCount > 0) {
            const detail = `${stage.stageCode} has been used — ${stage.transitionCount} ribbon `
              + `segments and ${stage.openTicketCount} tickets standing in it now. The code is `
              + 'stored as plain text on every one of those rows, so renaming it would leave '
              + 'their journeys unresolvable and stop the stage-SLA scan matching them, both '
              + 'without any error. Change the display name instead.';
            return problem(409, 'immutable-field', 'Stage code cannot be changed', {
              detail,
              transitionCount: stage.transitionCount,
              openTicketCount: stage.openTicketCount,
              errors: { stageCode: [detail] },
            });
          }
          stage.stageCode = code;
        }
      }
      if (typeof body.displayName === 'string') stage.displayName = body.displayName.trim();
      if (typeof body.ownerRole === 'string') stage.ownerRole = body.ownerRole;
      if (body.slaHours !== undefined) stage.slaHours = body.slaHours as number | null;
      if (typeof body.isOptional === 'boolean') stage.isOptional = body.isOptional;
      if (Array.isArray(body.canReturnTo)) stage.canReturnTo = body.canReturnTo as string[];
      if (body.icon !== undefined) stage.icon = body.icon as string | null;

      return ok(stageDto(stage), undefined, { headers: { ETag: stageEtag(stage) } });
    },
  ),

  /**
   * The reorder. Refuses the same three things the server refuses, because each
   * one is a sentence the screen renders rather than a status code it counts.
   */
  http.put(url('/masters/workflow-templates/:templateId/stages/order'),
    async ({ request, params }) => {
      const templateId = Number(params.templateId);
      if (!getDb().workflowTemplates.some((t) => t.id === templateId)) {
        return notFound('Workflow template');
      }
      const stages = ribbon(templateId);
      const stale = stagePrecondition(
        request.headers.get('If-Match'), ribbonEtag(stages), 'stage list');
      if (stale) return stale;

      const { stageIds } = (await request.json()) as { stageIds: number[] };
      const unique = new Set(stageIds);
      if (unique.size !== stageIds.length
          || unique.size !== stages.length
          || stageIds.some((id) => !stages.some((s) => s.id === id))) {
        return validationFailed({
          stageIds: [`Send every stage of this template exactly once — ${stages.length} `
            + `expected, ${unique.size} given. Moving one stage changes the position of every `
            + 'stage after it, so a partial list would leave the order ambiguous.'],
        });
      }

      const ordered = stageIds.map((id) => stages.find((s) => s.id === id)!);
      const position = new Map(ordered.map((s, i) => [s.stageCode, i]));
      const broken = ordered.flatMap((s, i) =>
        s.canReturnTo
          .filter((target) => (position.get(target) ?? -1) >= i)
          .map((target) => `${s.stageCode} \u2192 ${target}`));

      if (broken.length > 0) {
        const detail = `That order would leave ${broken.join(', ')} pointing forwards. `
          + 'A return target is a backward target, so clear it first or move the other '
          + 'stage instead.';
        return problem(409, 'return-target-direction', 'That order breaks a return path',
          { detail, pairs: broken, errors: { stageIds: [detail] } });
      }

      ordered.forEach((stage, index) => { stage.seq = (index + 1) * 10; });
      const next = ribbon(templateId);
      return ok(next.map(stageDto), undefined, { headers: { ETag: ribbonEtag(next) } });
    },
  ),

  /**
   * B-042 · retire or restore — §7.4's "deprecated, never deleted".
   *
   * **No `If-Match`, matching the server.** An idempotent setter naming the state
   * it wants, so a mock that demanded a precondition would make the screen build
   * a guard the real backend does not want.
   *
   * Both refusals are here rather than stubbed to 200, because each is a sentence
   * the dialog renders before the click and a mock that never produced one would
   * let that copy ship untested.
   */
  http.put(url('/masters/workflow-templates/:templateId/stages/:stageId/deprecation'),
    async ({ request, params }) => {
      const templateId = Number(params.templateId);
      const stages = ribbon(templateId);
      const stage = stages.find((s) => s.id === Number(params.stageId));
      if (!stage) return notFound('Stage');

      const { isDeprecated } = (await request.json()) as { isDeprecated: boolean };

      if (isDeprecated && !stage.isDeprecated) {
        const blocked = retireBlockers(stage, stages);
        if (blocked) return blocked;
        stage.isDeprecated = true;
        stage.deprecatedAt = new Date().toISOString();
      } else if (!isDeprecated && stage.isDeprecated) {
        // Cleared together — ck_workflow_stages_deprecation would refuse the row
        // otherwise, and a mock that left the timestamp behind would let a screen
        // ship reading it on a live stage.
        stage.isDeprecated = false;
        stage.deprecatedAt = null;
      }

      return ok(stageDto(stage), undefined, { headers: { ETag: stageEtag(stage) } });
    },
  ),

  /**
   * B-042 · the narrow delete §7.4 leaves room for.
   *
   * `If-Match` **is** required here, unlike on the setter above, and the mock
   * enforces it: the server's whole guard is that both usage counts are zero and
   * both are inside the per-row tag, so a client that skipped the precondition
   * would work against this mock and 428 against the backend.
   */
  http.delete(url('/masters/workflow-templates/:templateId/stages/:stageId'),
    ({ request, params }) => {
      const templateId = Number(params.templateId);
      const stages = ribbon(templateId);
      const stage = stages.find((s) => s.id === Number(params.stageId));
      if (!stage) return notFound('Stage');

      const stale = stagePrecondition(request.headers.get('If-Match'), stageEtag(stage), 'stage');
      if (stale) return stale;

      if (stage.transitionCount > 0 || stage.openTicketCount > 0) {
        const detail = `${stage.stageCode} has been used — ${stage.transitionCount} ribbon `
          + `segments and ${stage.openTicketCount} tickets standing in it now. Deleting it `
          + 'would leave every one of those rows pointing at a stage definition that no longer '
          + 'exists, and nothing would fail: the code travels as plain text with no foreign '
          + 'key. Deprecate it instead — it keeps rendering on the ribbons it is already on '
          + 'and accepts nothing new.';
        return problem(409, 'stage-in-use', 'Stage in use — deprecate it instead', {
          detail,
          transitionCount: stage.transitionCount,
          openTicketCount: stage.openTicketCount,
          canDeprecate: true,
        });
      }

      const blocked = retireBlockers(stage, stages);
      if (blocked) return blocked;

      const db = getDb();
      db.templateStages.splice(db.templateStages.findIndex((s) => s.id === stage.id), 1);
      return noContent();
    },
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
    return ok(widgetPayload(db, key), undefined, { headers: { ETag: etag } });
  }),
  /*
    A-073 · the batch form of the widget route above, for S-05's first paint.

    **Stream A adding a handler in Stream D's file (D-004)** — same situation
    `/reports` below documents, and flagged the same way rather than done
    quietly. `coverage.test.ts` refuses a contract operation with no handler, so
    the alternative is a red `develop`.

    Shares `widgetPayload` with the single route deliberately. Two mocks
    computing the same widget two ways would drift, and the first thing to break
    would be a test asserting that the batched dashboard renders what the
    per-widget one did — which is precisely the property this change has to
    keep.

    Mirrors the server on the two decisions that matter: unknown keys are
    dropped rather than 404ing the set, and one ETag covers the keys actually
    served.
  */
  http.get(url('/dashboard/widgets'), ({ request }) => {
    const db = getDb();
    // Repeated ?keys= and one comma-separated ?keys= are both accepted, because
    // Spring binds both and a mock that took only one shape would pass tests the
    // real server would reject.
    const requested = new URL(request.url).searchParams
      .getAll('keys')
      .flatMap((value) => value.split(','))
      .map((value) => value.trim())
      .filter(Boolean);
    const served = requested.filter((key, i) => requested.indexOf(key) === i);
    const etag = `W/"widgets-${served.join(',')}"`;
    if (request.headers.get('If-None-Match') === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }
    return ok(served.map((key) => widgetPayload(db, key)), undefined, {
      headers: { ETag: etag },
    });
  }),
  /*
    A-063 · the report catalogue behind S-27's card grid.

    **Stream A adding a handler in Stream D's file (D-004).** `coverage.test.ts`
    asserts every contract operation has one and names this directory as where
    to put it, so the alternative to adding it here is leaving `develop` red —
    which is what CI did when `listReports` landed without it. Flagged rather
    than done quietly.

    The seventeen unbuilt reports carry `available: false` deliberately, exactly
    as the server does: the hub's most common state for the next few sprints is
    a greyed card carrying its reason, and a mock that showed eighteen working
    reports would hide the one arrangement worth reviewing.

    `scopeNote` is null here — the mock has no caller identity to narrow by, and
    inventing one would make the mock disagree with the server for a reason no
    test could see. The real scoping is asserted in `ReportsIT` against MySQL.
  */
  http.get(url('/reports'), () => {
    const unbuilt = 'This report is not built yet. The hub lists it so you can see it is coming '
      + 'rather than wonder whether it exists.';

    const declared: [string, string, string, string | null][] = [
      ['resource-scorecard', 'Resource Performance Scorecard', 'PEOPLE', 'bar'],
      ['resource-velocity', 'Resource Velocity', 'PEOPLE', 'line'],
      ['effort-summary', 'Effort Summary', 'PEOPLE', 'stacked-bar'],
      ['resource-contribution', 'Resource Contribution', 'PEOPLE', 'stacked-bar'],
      ['project-health', 'Project Health', 'DELIVERY', 'line'],
      ['aging', 'Aging Report', 'DELIVERY', 'bar'],
      ['sla-breach', 'Delayed / SLA Breach', 'DELIVERY', 'bar'],
      ['workload-capacity', 'Workload & Capacity', 'DELIVERY', 'stacked-bar'],
      ['reopen-analysis', 'Reopen Analysis', 'QUALITY', 'bar'],
      ['rework-analysis', 'Rework Analysis', 'QUALITY', 'bar'],
      ['task-type-analysis', 'Task Type Analysis', 'QUALITY', 'donut'],
      ['stage-funnel', 'Stage Funnel', 'WORKFLOW', 'bar'],
      ['stage-cycle-time', 'Stage Cycle Time', 'WORKFLOW', 'stacked-bar'],
      ['deployment-report', 'Deployment Report', 'WORKFLOW', 'bar'],
      ['client-report', 'Client Report', 'OPERATIONS', 'bar'],
      ['audit-compliance', 'Audit & Compliance', 'OPERATIONS', null],
      ['email-delivery-log', 'Email Delivery Log', 'OPERATIONS', null],
    ];

    return ok({
      reports: [
        {
          key: 'date-wise',
          title: 'Date-wise Report',
          description: 'Created against closed and reopened per day, with the net backlog line.',
          category: 'DELIVERY',
          chart: 'line',
          filters: ['DATE_RANGE', 'PROJECT'],
          available: true,
          unavailableReason: null,
        },
        ...declared.map(([key, title, category, chart]) => ({
          key,
          title,
          description: `${title} — S-27.`,
          category,
          chart,
          filters: ['DATE_RANGE', 'PROJECT'],
          available: false,
          unavailableReason: unbuilt,
        })),
      ],
      scopeNote: null,
    });
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
  /*
    A-065 · §7.8's scheduled report emails.

    ⚠️ Stream A, in Stream D's handler file — flagged, not quiet. The
    mock-coverage test refuses a contract operation with no handler, so the
    three routes A-065 adds arrive with these.

    The POST used to answer a bare `201` with no body, matching what D-001
    declared. It now returns the schedule, because that is what the contract
    says and because the dialog needs the id to offer Cancel without a second
    round trip.
  */
  http.post(url('/reports/schedule'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as {
      reportKey: string;
      cadence: 'DAILY' | 'WEEKLY' | 'MONTHLY';
      format?: 'xlsx' | 'csv' | 'pdf';
      recipients: string[];
      parameters?: Record<string, unknown>;
    };

    // The server's rule, modelled rather than skipped: the mail links to an
    // authenticated download, so an address with no account could never open
    // it. A mock that accepted anything would let the dialog's error path go
    // untested against the one refusal users will actually hit.
    const unknown = body.recipients.filter(
      (address) => !db.users.some((u) => u.email.toLowerCase() === address.toLowerCase()),
    );
    if (unknown.length > 0) {
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          detail:
            'These addresses do not belong to an active EduTrack user, and the report link '
            + `can only be opened by somebody who can sign in: ${unknown.join(', ')}`,
        },
        { status: 400 },
      );
    }

    const owner = db.users.find((u) => u.id === db.currentUserId);
    const row: ReportScheduleRow = {
      id: nextId(db, 'reportSchedule'),
      reportKey: body.reportKey,
      reportTitle: reportTitleOf(body.reportKey),
      cadence: body.cadence,
      format: body.format ?? 'xlsx',
      recipients: body.recipients,
      // Dates are dropped here too. The server drops them because it must —
      // the period comes from the cadence — and the mock drops them so a
      // screen built against it cannot come to rely on them surviving.
      parameters: withoutDates(body.parameters ?? {}),
      active: true,
      createdBy: db.currentUserId,
      createdByName: owner?.displayName ?? null,
      nextRunAt: '2026-08-24T00:30:00.000Z',
      lastRunAt: null,
      recentRuns: [],
    };
    db.reportSchedules.push(row);
    // `ownedByMe` is a per-caller view of the row rather than a column, so it
    // is added on the way out here and in the list, never stored.
    return HttpResponse.json({ data: { ...row, ownedByMe: true } }, { status: 201 });
  }),

  /*
    A-072 · global search.

    ⚠️ Stream A, in Stream D's handler file — flagged, not quiet, exactly as
    A-065's schedule handlers were. The mock-coverage test refuses a contract
    operation with no handler, and this one is reached by every test that
    mounts the shell, because the command palette lives there.

    The mock models the *shape* of the server's three answers rather than its
    matching: a code is exact, words match tickets, a prefix matches people.
    Substring rather than full text, because MSW has no index — what a screen
    built against this must not come to rely on is relevance ordering, which is
    the one thing only MySQL can produce.
  */
  http.get(url('/search'), ({ request }) => {
    const db = getDb();
    const raw = (new URL(request.url).searchParams.get('q') ?? '').trim();
    if (raw === '') {
      return ok({ exactTicket: null, tickets: [], people: [] });
    }

    const hit = (t: (typeof db.tickets)[number]) => ({
      ticketId: t.ticketId,
      title: t.title,
      level: t.level ?? null,
      status: t.status ?? null,
    });

    // The same shapes the server accepts: a bare code, one in brackets from a
    // mail subject, or a whole ticket URL out of an address bar.
    const cleaned = raw.toUpperCase().replace(/^[^A-Z0-9]+|[^A-Z0-9]+$/g, '');
    const candidate = cleaned.includes('/')
      ? cleaned.slice(cleaned.lastIndexOf('/') + 1).split(/[?#]/, 1)[0]
      : cleaned;
    const exact = /^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$/.test(candidate)
      ? (db.tickets.find((t) => t.ticketId === candidate) ?? null)
      : null;

    const needle = raw.toLowerCase();
    const tickets = db.tickets
      .filter((t) => t.ticketId !== exact?.ticketId)
      .filter((t) =>
        t.title?.toLowerCase().includes(needle)
        || t.description?.toLowerCase().includes(needle))
      .slice(0, 6)
      .map(hit);

    const people = db.users
      .filter((u) =>
        u.displayName?.toLowerCase().startsWith(needle)
        || u.username?.toLowerCase().startsWith(needle))
      .slice(0, 6)
      .map((u) => ({
        id: u.id,
        displayName: u.displayName,
        username: u.username ?? null,
        email: u.email ?? null,
        role: u.role ?? null,
      }));

    return ok({ exactTicket: exact ? hit(exact) : null, tickets, people });
  }),

  http.get(url('/reports/schedules'), () => {
    const db = getDb();
    // Cancelled ones included, as the contract says: "why did this stop
    // arriving" is the question the screen exists to answer.
    return HttpResponse.json({
      data: db.reportSchedules
        .filter((s) =>
          s.createdBy === db.currentUserId
          || s.recipients.some((r) => r.toLowerCase() === meEmail().toLowerCase()))
        .map((s) => ({ ...s, ownedByMe: s.createdBy === db.currentUserId })),
    });
  }),

  http.delete(url('/reports/schedules/:id'), ({ params }) => {
    const db = getDb();
    const row = db.reportSchedules.find(
      (s) => s.id === Number(params.id) && s.createdBy === db.currentUserId,
    );
    // 404 for somebody else's, never 403 — §2's rule for an out-of-scope id,
    // modelled so a screen cannot be built against a distinction the server
    // deliberately does not make.
    if (!row) {
      return problem(404, 'not-found', 'No such scheduled report.');
    }
    row.active = false;
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(url('/reports/schedules/:id/runs/:runId/download'), ({ params }) => {
    const db = getDb();
    // Owner or recipient — a recipient who cannot open the file the email told
    // them about is the defect this route was fixed for.
    const schedule = db.reportSchedules.find(
      (s) =>
        s.id === Number(params.id)
        && (s.createdBy === db.currentUserId
          || s.recipients.some((r) => r.toLowerCase() === meEmail().toLowerCase())),
    );
    const run = schedule?.recentRuns.find((r) => r.id === Number(params.runId));
    if (!run?.downloadable) {
      return problem(404, 'not-found', 'No downloadable run for that scheduled report.');
    }
    // Bytes, not JSON — the real route streams a file and the screen turns it
    // into a blob. A JSON body here would let the download path pass in a mock
    // and fail against the server.
    return new HttpResponse(new Blob(['scheduled report']), {
      status: 200,
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Disposition':
          `attachment; filename="${schedule?.reportKey}-${run.periodFrom}_${run.periodTo}.${schedule?.format}"`,
      },
    });
  }),

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
  /**
   * D-053 · §7.6's file and image share.
   *
   * The mock seals CLEAN immediately where the server queues an AV scan and
   * answers PENDING. That is a deliberate divergence and the only one: a mock
   * that stayed PENDING for ever would give the client no way to reach the
   * rendered state at all, and PENDING is still reachable — a fixture can set
   * `scanStatus` on a row directly, which is how "not downloadable until
   * CLEAN" is tested.
   */
  http.post(url('/chat/threads/:threadId/attachments'), ({ params }) => {
    const db = getDb();
    const thread = db.chatThreads.find((t) => t.id === Number(params.threadId));
    // Existence only, matching every other chat handler in this file.
    //
    // ⚠ The **server** additionally requires participation and answers 404 for
    // a thread the caller is not in — the same 404-not-403 rule row scoping
    // follows. This mock has never modelled chat participation on any route
    // (`GET …/messages` and `POST …/messages` both check existence alone), and
    // enforcing it on this one route only would make the mock internally
    // inconsistent in a way that reads as a bug in whichever screen hits it.
    // Modelling it properly is one change across every chat handler, and it
    // belongs to whoever needs it rather than being smuggled in here.
    if (!thread) return notFound('Thread');

    // ⚠ **The uploaded file is deliberately not read**, exactly as
    // `POST /imports/:schema/upload` above does not read its own — and for the
    // reason documented there and at length in `useTicketAttachments.test.tsx`:
    // under vitest no genuine multipart body reaches a handler, because jsdom
    // supplies FormData while Node supplies Request. `request.formData()` here
    // does not fail, it *hangs*, and every test that uploads times out with no
    // hint why.
    //
    // So the row is echoed from constants. That costs one thing worth stating:
    // this handler cannot prove the *name* round-trips. The composer therefore
    // shows the browser's own `File.name` on its pending chip — which is the
    // name the user picked and the honest thing to show before a server has
    // answered — and the rendered message shows the server's, which is what
    // the constant below exercises.
    const contentType = 'image/png';
    const row = {
      id: nextId(db, 'chatAttachment'),
      threadId: thread.id,
      messageId: null,
      fileName: 'screenshot.png',
      contentType,
      sizeBytes: 4096,
      scanStatus: 'CLEAN' as const,
      uploadedById: db.currentUserId,
      createdAt: new Date().toISOString(),
    };
    db.chatAttachments.push(row);
    return ok(
      {
        id: row.id, fileName: row.fileName, contentType: row.contentType,
        sizeBytes: row.sizeBytes, scanStatus: row.scanStatus,
        isImage: contentType.startsWith('image/'),
        downloadUrl: `/mock-files/chat/${row.id}`,
        uploadedBy: userRef(row.uploadedById, db),
        createdAt: row.createdAt,
      },
      undefined,
      { status: 201 },
    );
  }),

  http.post(url('/chat/threads/:threadId/messages'), async ({ params, request }) => {
    const db = getDb();
    const thread = db.chatThreads.find((t) => t.id === Number(params.threadId));
    if (!thread) return notFound('Thread');
    const { body, attachmentIds } = (await request.json()) as {
      body: string; attachmentIds?: number[];
    };
    if (!body?.trim()) return validationFailed({ body: ['must not be blank'] });
    const m = {
      id: nextId(db, 'message'), threadId: thread.id, body,
      authorId: db.currentUserId, kind: 'TEXT' as const,
      isEdited: false, isDeleted: false, readBy: [db.currentUserId],
      createdAt: new Date().toISOString(),
    };
    db.chatMessages.push(m);
    thread.lastMessageAt = m.createdAt;

    // D-053 · bind the uploaded files. An id that is not this thread's, or is
    // already carried, is skipped rather than refused — the server's own rule:
    // refusing lets a caller probe for which ids exist by watching which sends
    // fail, and loses the message rather than one file the sender can re-attach.
    for (const id of attachmentIds ?? []) {
      const file = db.chatAttachments.find(
        (a) => a.id === id && a.threadId === thread.id && a.messageId === null,
      );
      if (file) file.messageId = m.id;
    }

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

/**
 * D-053 · one message's shared files.
 *
 * `downloadUrl` only for a CLEAN row — the server's own rule, and the one
 * worth mirroring: the requirement is that an unscanned file not become
 * *readable*, and an absent URL is what enforces that. A mock that always
 * handed one back would let the client be built without the pending state.
 */
function chatAttachmentsFor(messageId: number, db: import('../db').Db = getDb()) {
  return db.chatAttachments
    .filter((a) => a.messageId === messageId)
    .map((a) => ({
      id: a.id,
      fileName: a.fileName,
      contentType: a.contentType,
      sizeBytes: a.sizeBytes,
      scanStatus: a.scanStatus,
      // From the sniffed type, never the file name — a client that renders an
      // <img> off an extension will happily try it on a renamed executable.
      isImage: a.contentType.startsWith('image/'),
      downloadUrl: a.scanStatus === 'CLEAN' ? `/mock-files/chat/${a.id}` : null,
      uploadedBy: userRef(a.uploadedById, db),
      createdAt: a.createdAt,
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
    // D-053 · §7.6's file share. Empty on a tombstone, exactly as on the
    // server: a deleted message's content is withheld, and a file list is
    // content. The rows survive.
    attachments: m.isDeleted ? [] : chatAttachmentsFor(m.id),
    readBy: m.readBy,
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
