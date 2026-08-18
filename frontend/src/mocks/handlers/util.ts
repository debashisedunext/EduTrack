import { HttpResponse } from 'msw';
import type { Db, Ticket, User } from '../db';
import { getDb } from '../db';

export const BASE = '/api/v1';
export const url = (path: string) => `${BASE}${path}`;

/** Success envelope. Everything is `{ data, meta? }` — CONVENTIONS.md §2. */
export const ok = <T,>(data: T, meta?: Record<string, unknown>, init?: ResponseInit) =>
  HttpResponse.json(meta ? { data, meta } : { data }, init);

export const noContent = () => new HttpResponse(null, { status: 204 });

/**
 * RFC 9457 problem document — CONVENTIONS.md §3.
 *
 * The media type matters: the client branches on `Content-Type` rather than
 * probing the body for an `error` key.
 */
export function problem(
  status: number,
  type: string,
  title: string,
  extra: Record<string, unknown> = {},
) {
  return HttpResponse.json(
    { type: `https://edutrack/errors/${type}`, title, status, traceId: 'mock-trace', ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/**
 * Not found — **or out of the caller's scope**. Deliberately the same response.
 *
 * Returning 403 for an out-of-scope row would confirm it exists, and ticket IDs
 * are sequential per project so enumerating them is trivial. CONVENTIONS.md §7.
 */
export const notFound = (what = 'Resource') =>
  problem(404, 'not-found', `${what} not found`);

export const unprocessable = (title: string) =>
  problem(422, 'unprocessable-transition', title);

export const validationFailed = (errors: Record<string, string[]>) =>
  problem(400, 'validation', 'Validation failed', { errors });

// ── pagination ──────────────────────────────────────────────────────────────
/**
 * Cursor pagination. The cursor is the index of the next item, base64'd so
 * nobody is tempted to treat it as an offset they can arithmetic on.
 */
export function paginate<T>(items: T[], requestUrl: URL) {
  const limit = Math.min(Number(requestUrl.searchParams.get('limit')) || 50, 200);
  const raw = requestUrl.searchParams.get('cursor');
  const start = raw ? Number(atob(raw)) || 0 : 0;
  const page = items.slice(start, start + limit);
  const end = start + page.length;
  const hasMore = end < items.length;
  return {
    page,
    meta: {
      nextCursor: hasMore ? btoa(String(end)) : null,
      hasMore,
      totalCount: items.length,
    },
  };
}

// ── scoping ─────────────────────────────────────────────────────────────────
/**
 * The row-scope guard, mirrored.
 *
 * Deliberately mirrored in the mock so Stream C builds against scoped data from
 * day one. If the mock returned everything, the first day on the real backend
 * would be the day half the screens turn out empty for a Developer.
 */
export function scopedTickets(db: Db = getDb()): Ticket[] {
  const me = currentUser(db);
  switch (me.role) {
    case 'ADMIN':
      return db.tickets;
    case 'PM':
    case 'SUPPORT':
      return db.tickets.filter((t) => me.projectIds.includes(t.projectId));
    default:
      return db.tickets.filter((t) => t.assigneeId === me.id);
  }
}

export function findTicket(ticketId: string, db: Db = getDb()): Ticket | undefined {
  return scopedTickets(db).find((t) => t.ticketId === ticketId);
}

export const currentUser = (db: Db = getDb()): User =>
  db.users.find((u) => u.id === db.currentUserId)!;

// ── mappers to the contract's shapes ────────────────────────────────────────
export const userRef = (id: number | null, db: Db = getDb()) => {
  if (id == null) return null;
  const u = db.users.find((x) => x.id === id);
  return u ? { id: u.id, displayName: u.displayName, avatarUrl: u.avatarUrl, role: u.role } : null;
};

/**
 * `ProjectRef` — the label half of a project (B-010).
 *
 * Resolved server-side so the S-07 grid can print project names without a
 * second request per row.
 */
export const projectRef = (id: number, db: Db = getDb()) => {
  const p = db.projects.find((x) => x.id === id);
  return p ? { id: p.id, projectCode: p.projectCode, name: p.name, colourTag: p.colourTag } : null;
};

export const clientRef = (id: number | null, db: Db = getDb()) => {
  if (id == null) return null;
  const c = db.clients.find((x) => x.id === id);
  return c ? { id: c.id, clientCode: c.clientCode, name: c.name } : null;
};

/**
 * D-061 · one row of S-17 — the `TicketSummary` schema, not `Ticket`.
 *
 * The list returns this and the detail page returns the full record. Kept as a
 * projection of {@link ticketDto} rather than a second hand-written object, so
 * a field cannot come to mean one thing here and another there — the mock's
 * whole job is to be indistinguishable from the server, and two independent
 * constructions of the same row is how it stops being.
 *
 * What is deliberately absent is what a row does not display and a list should
 * not carry 25 copies of: `description` and `stepsToGenerate` are sanitised
 * HTML that runs to kilobytes, and `screenName`, `feature`, `pctComplete` and
 * `updatedAt` have no column on S-17.
 */
export function ticketSummaryDto(t: Ticket, db: Db = getDb()) {
  const full = ticketDto(t, db);
  const cycle = db.cycles.filter((c) => c.ticketId === t.ticketId);
  return {
    ticketId: full.ticketId,
    title: full.title,
    project: full.project,
    client: full.client,
    clientContactId: full.clientContactId,
    isClientRaised: full.isClientRaised,
    taskTypeId: full.taskTypeId,
    moduleId: full.moduleId,
    level: full.level,
    originalLevel: full.originalLevel,
    status: full.status,
    currentStageCode: full.currentStageCode,
    assignee: full.assignee,
    reportedBy: full.reportedBy,
    cycleNo: full.cycleNo,
    iterationNo: full.iterationNo,
    reopenCount: full.reopenCount,
    isReopened: cycle.length > 1,
    dateReported: t.createdAt,
    plannedCloseDate: full.plannedCloseDate,
    actualCloseDate: full.actualCloseDate,
    isDelayed: full.isDelayed,
    delayedSince: full.delayedSince,
    estimatedHrs: full.estimatedHrs,
    totalEffortHrs: full.totalEffortHrs,
    commentCount: db.comments.filter((c) => c.ticketId === t.ticketId && !c.deletedAt).length,
    attachmentCount: db.attachments.filter((a) => a.ticketId === t.ticketId && !a.deletedAt).length,
    createdAt: full.createdAt,
  };
}

export function ticketDto(t: Ticket, db: Db = getDb()) {
  const project = db.projects.find((p) => p.id === t.projectId)!;
  const effort = db.effortLogs
    .filter((e) => e.ticketId === t.ticketId)
    .reduce((sum, e) => sum + e.hours, 0);
  return {
    ticketId: t.ticketId,
    title: t.title,
    description: t.description,
    project: {
      id: project.id, projectCode: project.projectCode, name: project.name,
      projectManager: userRef(project.projectManagerId, db),
      // B-016 · `isActive` is derived from `status`, never stored — see
      // `projectDto` in rest.ts, which is the same derivation for the same
      // reason. Both are emitted so a ticket's embedded project reads exactly
      // like one from `/projects`.
      colourTag: project.colourTag,
      status: project.status, isActive: project.status !== 'CLOSED',
    },
    client: clientRef(t.clientId, db),
    clientContactId: t.clientContactId,
    isClientRaised: t.isClientRaised,
    taskTypeId: t.taskTypeId,
    moduleId: t.moduleId,
    screenName: t.screenName,
    feature: t.feature,
    stepsToGenerate: t.stepsToGenerate,
    level: t.level,
    originalLevel: t.originalLevel,
    status: t.status,
    currentStageCode: t.currentStageCode,
    assignee: userRef(t.assigneeId, db),
    reportedBy: userRef(t.reportedById, db),
    cycleNo: t.cycleNo,
    iterationNo: t.iterationNo,
    reopenCount: t.reopenCount,
    plannedCloseDate: t.plannedCloseDate,
    actualCloseDate: t.actualCloseDate,
    isDelayed: t.isDelayed,
    delayedSince: t.delayedSince,
    totalEffortHrs: Math.round(effort * 10) / 10,
    estimatedHrs: t.estimatedHrs,
    pctComplete: t.pctComplete,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
  };
}

/**
 * Working minutes between two instants — weekends excluded, 09:30–18:30.
 *
 * Days are ISO-8601 numbers (Mon=1 … Sun=7), the same convention as
 * `weeklyOff` on `GET /masters/holidays` and as `DayOfWeek.getValue()` in the
 * real service. `Date.getUTCDay()` is Sunday-zero-based, so it is normalised
 * once, here — this stays hardcoded to Sat/Sun rather than reading `weeklyOff`,
 * and feeding ISO values into an un-normalised 0–6 test is precisely how Sunday
 * becomes a working day.
 */
export function workingMinutes(fromIso: string, toIso: string): number {
  const SATURDAY = 6;
  const SUNDAY = 7;
  const DAY_START = 9.5 * 60;
  const DAY_END = 18.5 * 60;
  let total = 0;
  const cursor = new Date(fromIso);
  const end = new Date(toIso);
  while (cursor < end) {
    const day = cursor.getUTCDay() || SUNDAY;
    if (day !== SATURDAY && day !== SUNDAY) {
      const minsIntoDay = cursor.getUTCHours() * 60 + cursor.getUTCMinutes();
      const dayEnd = new Date(cursor);
      dayEnd.setUTCHours(0, DAY_END, 0, 0);
      const sliceEnd = end < dayEnd ? end : dayEnd;
      if (minsIntoDay < DAY_END) {
        const from = Math.max(minsIntoDay, DAY_START);
        const to = Math.max(from, (sliceEnd.getTime() - cursor.getTime()) / 60000 + minsIntoDay);
        total += Math.max(0, Math.min(to, DAY_END) - from);
      }
    }
    cursor.setUTCDate(cursor.getUTCDate() + 1);
    cursor.setUTCHours(0, 0, 0, 0);
  }
  return Math.round(total);
}

/** Simulated latency, so loading states are actually visible while developing. */
export const LATENCY_MS = 120;
