import { http } from 'msw';
import { getDb, type Db, type Level, type SlaPolicy } from '../db';
import { notFound, ok, url, validationFailed } from './util';

/**
 * C-012 · SLA resolution and the planned close date, mirroring
 * `PlannedCloseDateService`.
 *
 * Exported rather than inlined into the handler because **the create path has
 * to use the same function**. `POST /tickets` used to stamp
 * `now + defaultSlaHrs` in wall-clock milliseconds, which meant the date the
 * preview showed and the date the mock stored differed by every weekend and
 * holiday between them — the preview would look correct and the ticket would be
 * wrong, which is worse than no preview at all.
 *
 * ## Two deliberate simplifications
 *
 * **The working window is treated as UTC**, not as `calendar.week.timezone`.
 * `util.ts`'s `workingMinutes` already does this — its comment explains why —
 * and the seeded §14 walkthrough hours were built against that convention. Two
 * mock helpers disagreeing by 5.5 hours would break the fixture the ribbon and
 * the Journey roll-up are judged on. The real service uses the calendar's zone;
 * this is a mock artefact and stays inside the mock.
 *
 * **Half-day leave is the second half of the day**, matching
 * `WorkingHoursService.workingWindowOnDate`'s stated convention. What matters
 * is that both halves of the product agree on it, not which half is picked.
 */

const MINUTES = 60_000;
const DAY_MS = 24 * 60 * MINUTES;

/** A walk that reaches this many days has hit a broken calendar, not a far answer. */
const MAX_WALK_DAYS = 3650;

/** Which rung of the ladder answered. Mirrors the contract's `SlaSource`. */
export type SlaSource =
  | 'PROJECT_TASK_TYPE'
  | 'PROJECT_LEVEL'
  | 'ORG_DEFAULT'
  | 'PRIORITY_DEFAULT'
  | 'TASK_TYPE_DEFAULT'
  | 'NONE';

export interface Resolution {
  source: SlaSource;
  slaPolicyId: number | null;
  responseHrs: number | null;
  resolutionHrs: number | null;
}

const NONE: Resolution = { source: 'NONE', slaPolicyId: null, responseHrs: null, resolutionHrs: null };

const fromPolicy = (source: SlaSource, p: SlaPolicy): Resolution => ({
  source,
  slaPolicyId: p.id,
  responseHrs: p.responseHrs,
  resolutionHrs: p.resolutionHrs,
});

/**
 * The priority master's default hours, kept in step with
 * `GET /masters/priorities`. Rung 4 — the last figure that still varies with
 * the level, which is why it is tried before the task type's.
 */
const PRIORITY_DEFAULT_HRS: Record<Level, number> = {
  LOW: 120,
  MEDIUM: 48,
  HIGH: 16,
  CRITICAL: 4,
};

/**
 * The five rungs, most specific first, stopping at the first that answers.
 *
 * Falling past the three policy rungs onto the two master defaults is
 * deliberate and is explained on the contract's `previewPlannedCloseDate`: a
 * project with no configured matrix would otherwise give every ticket a null
 * planned close date, which quietly removes it from the breach sweep, the
 * delayed KPI and the Due Today view.
 */
export function resolveSla(
  projectId: number | null,
  taskTypeId: number | null,
  level: Level,
  db: Db = getDb(),
): Resolution {
  const active = db.slaPolicies.filter((p) => p.isActive && p.level === level);
  const byId = (a: SlaPolicy, b: SlaPolicy) => a.id - b.id;

  if (projectId != null && taskTypeId != null) {
    const exact = active.find((p) => p.projectId === projectId && p.taskTypeId === taskTypeId);
    if (exact) return fromPolicy('PROJECT_TASK_TYPE', exact);
  }
  if (projectId != null) {
    const projectDefault = active.filter((p) => p.projectId === projectId && p.taskTypeId == null).sort(byId)[0];
    if (projectDefault) return fromPolicy('PROJECT_LEVEL', projectDefault);
  }
  const orgDefault = active.filter((p) => p.projectId == null && p.taskTypeId == null).sort(byId)[0];
  if (orgDefault) return fromPolicy('ORG_DEFAULT', orgDefault);

  // D-066 · a level added through S-12 is not in this table, and falling to
  // the task-type rung is the right answer rather than a bug: the master's own
  // `defaultSlaHrs` is what a new level carries, and the mock has no policy for
  // it either. Written as an explicit `?? 0` because `Level` is no longer a
  // closed union, so the index is genuinely partial — it only *looked* total.
  const priorityDefault = PRIORITY_DEFAULT_HRS[level] ?? 0;
  if (priorityDefault > 0) {
    return { source: 'PRIORITY_DEFAULT', slaPolicyId: null, responseHrs: null, resolutionHrs: priorityDefault };
  }

  const taskTypeDefault = db.taskTypes.find((t) => t.id === taskTypeId)?.defaultSlaHrs;
  if (taskTypeDefault != null && taskTypeDefault > 0) {
    return { source: 'TASK_TYPE_DEFAULT', slaPolicyId: null, responseHrs: null, resolutionHrs: taskTypeDefault };
  }
  return NONE;
}

// ── the calendar walk ────────────────────────────────────────────────────────

const isoDate = (d: Date) => d.toISOString().slice(0, 10);

/** ISO-8601 day of week: Mon=1 … Sun=7. `getUTCDay()` is Sunday-zero. */
const isoDay = (d: Date) => d.getUTCDay() || 7;

/**
 * Every holiday date in play, with recurring ones expanded into the year asked
 * for. A recurring holiday stored against 2026 still falls in 2027, and a walk
 * that only matched the stored date would count Independence Day as a working
 * day every year but one.
 */
function isHoliday(db: Db, date: Date, projectId: number | null): boolean {
  const stamp = isoDate(date);
  const monthDay = stamp.slice(5);
  return db.calendar.holidays.some((h) => {
    if (!h.isActive) return false;
    if (h.projectId != null && h.projectId !== projectId) return false;
    return h.isRecurring ? h.date.slice(5) === monthDay : h.date === stamp;
  });
}

function leaveOn(db: Db, date: Date, userId: number | null) {
  if (userId == null) return undefined;
  const stamp = isoDate(date);
  return db.calendar.leaves.find(
    (l) => l.userId === userId && l.status === 'APPROVED' && l.startDate <= stamp && stamp <= l.endDate,
  );
}

const minutesOf = (time: string) => {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
};

/**
 * The window a date can supply, in epoch milliseconds, or null when it supplies
 * nothing. A weekly off, a holiday and a full-day leave are all "no window" —
 * the distinction only matters for readability, since none of them contribute.
 */
function windowOn(db: Db, date: Date, projectId: number | null, userId: number | null) {
  if (db.calendar.week.weeklyOff.includes(isoDay(date))) return null;
  if (isHoliday(db, date, projectId)) return null;

  const midnight = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
  const start = midnight + minutesOf(db.calendar.week.workDayStart) * MINUTES;
  const end = midnight + minutesOf(db.calendar.week.workDayEnd) * MINUTES;

  const leave = leaveOn(db, date, userId);
  if (!leave) return { start, end };
  if (!leave.isHalfDay) return null;
  return { start: start + (end - start) / 2, end };
}

/**
 * How much working time actually elapsed between two instants, in minutes.
 *
 * The inverse of {@link addWorkingHours}, and the mock's stand-in for B-024's
 * `workingHoursBetween`. D-056 needs it: a manager who asks at 17:40 on Friday
 * and is answered at 09:30 on Monday waited a few working minutes, not
 * sixty-four hours, and a mock that returned wall clock would have the client
 * rendering a response time the server never produces.
 *
 * Returns 0 rather than a negative number when the window is inverted — the
 * caller is measuring a wait, and "answered before it was asked" is a clock
 * problem, not a figure to report.
 */
export function workingMinutesBetween(
  fromIso: string,
  toIso: string,
  projectId: number | null = null,
  userId: number | null = null,
  db: Db = getDb(),
): number {
  const start = new Date(fromIso).getTime();
  const end = new Date(toIso).getTime();
  if (!(end > start)) return 0;

  let total = 0;
  let cursor = start;
  for (let walked = 0; walked <= MAX_WALK_DAYS && cursor < end; walked += 1) {
    const day = windowOn(db, new Date(cursor), projectId, userId);
    if (day) {
      const from = Math.max(day.start, start);
      const to = Math.min(day.end, end);
      if (to > from) total += to - from;
    }
    cursor = Math.floor(cursor / DAY_MS) * DAY_MS + DAY_MS;
  }
  return Math.round(total / MINUTES);
}

/**
 * Where `hours` of working time lands, starting at `fromIso`.
 *
 * Returns a precise instant inside a working day, not a date: "Friday 18:00
 * plus four working hours" has to land partway through Monday morning, not at
 * Monday's start. A non-positive `hours` returns the start unchanged, and the
 * callers treat that as "no target" rather than "due now" — a planned close
 * date already in the past hands the scanner an immediate breach on a ticket
 * nobody has read.
 */
export function addWorkingHours(
  fromIso: string,
  hours: number,
  projectId: number | null,
  userId: number | null,
  db: Db = getDb(),
): string {
  const start = new Date(fromIso).getTime();
  if (!(hours > 0)) return new Date(start).toISOString();

  let remaining = hours * 60 * MINUTES;
  let cursor = start;
  for (let walked = 0; walked <= MAX_WALK_DAYS; walked += 1) {
    const day = windowOn(db, new Date(cursor), projectId, userId);
    if (day) {
      const effectiveStart = walked === 0 ? Math.max(day.start, start) : day.start;
      const available = day.end - effectiveStart;
      if (available >= remaining) return new Date(effectiveStart + remaining).toISOString();
      if (available > 0) remaining -= available;
    }
    cursor = Math.floor(cursor / DAY_MS) * DAY_MS + DAY_MS;
  }
  throw new Error(`mock calendar has no working day within ${MAX_WALK_DAYS} days of ${fromIso}`);
}

export interface PlannedCloseDate {
  from: string;
  plannedCloseDate: string | null;
  firstResponseDue: string | null;
  responseHrs: number | null;
  resolutionHrs: number | null;
  source: SlaSource;
  slaPolicyId: number | null;
}

/** Resolution and walk together — what both the preview and `POST /tickets` use. */
export function plannedCloseDateFor(
  args: { projectId: number | null; taskTypeId: number | null; level: Level; assigneeId: number | null; from?: string },
  db: Db = getDb(),
): PlannedCloseDate {
  const from = args.from ?? new Date().toISOString();
  const sla = resolveSla(args.projectId, args.taskTypeId, args.level, db);
  const hasTarget = sla.resolutionHrs != null && sla.resolutionHrs > 0;

  return {
    from,
    plannedCloseDate: hasTarget
      ? addWorkingHours(from, sla.resolutionHrs as number, args.projectId, args.assigneeId, db)
      : null,
    firstResponseDue:
      sla.responseHrs != null && sla.responseHrs > 0
        ? addWorkingHours(from, sla.responseHrs, args.projectId, args.assigneeId, db)
        : null,
    responseHrs: sla.responseHrs,
    resolutionHrs: sla.resolutionHrs,
    source: sla.source,
    slaPolicyId: sla.slaPolicyId,
  };
}

// ── the handler ──────────────────────────────────────────────────────────────

const LEVELS: readonly Level[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export const slaHandlers = [
  /**
   * Registered before `/tickets/:ticketId` would match, and the literal segment
   * could not be a ticket id in any case. 404 for an unknown project and 400
   * for an unknown level, the same two the server answers — a preview that
   * silently returned "no SLA" for a typo'd level looks identical to a correctly
   * spelled level nobody has configured, and the two need opposite fixes.
   */
  http.get(url('/tickets/planned-close-date'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;

    const projectId = Number(q.get('projectId'));
    if (!db.projects.some((p) => p.id === projectId)) return notFound();

    const level = q.get('level') as Level | null;
    if (!level || !LEVELS.includes(level)) {
      return validationFailed({ level: [`No priority with code ${level ?? ''}`] });
    }

    const taskTypeId = q.get('taskTypeId') ? Number(q.get('taskTypeId')) : null;
    const assigneeId = q.get('assigneeId') ? Number(q.get('assigneeId')) : null;
    const from = q.get('from') ?? undefined;

    return ok(plannedCloseDateFor({ projectId, taskTypeId, level, assigneeId, from }, db));
  }),
];
