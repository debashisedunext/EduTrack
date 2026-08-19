import { http } from 'msw';
import { getDb, nextId } from '../db';
import { buildRibbon, round } from './tickets';
import {
  currentUser, findTicket, notFound, ok, paginate,
  ticketDto, unprocessable, url, userRef, validationFailed, workingMinutes,
} from './util';

/**
 * The Workflow Ribbon — the transitions that make it move, and the roll-up.
 *
 * This is the part of the mock that has to behave, not just respond. Stream C
 * builds handoff, rework and the journey grid against it before any of the real
 * endpoints exist, so the rules that are easy to get wrong are enforced here
 * too: the golden rule, the two independent counters, and effort at handoff.
 */

/** Seal the open transition for a ticket's current cycle. */
function sealCurrent(ticketId: string, cycleNo: number, at: string) {
  const db = getDb();
  const open = db.transitions.find(
    (t) => t.ticketId === ticketId && t.cycleNo === cycleNo && t.exitedAt === null,
  );
  if (!open) return null;
  open.exitedAt = at;
  // Working minutes, not wall-clock. A ticket handed over on Friday evening has
  // not been "in Deployment for 60 hours" by Monday morning.
  open.durationMins = workingMinutes(open.enteredAt, at);
  return open;
}

/** May this caller move this ticket? The golden rule, in one place. */
function mayAdvance(ticketId: string) {
  const db = getDb();
  const t = db.tickets.find((x) => x.ticketId === ticketId)!;
  const me = currentUser(db);
  return me.role === 'ADMIN' || me.role === 'PM' || t.assigneeId === me.id;
}

export const ribbonHandlers = [
  http.get(url('/tickets/:ticketId/ribbon'), ({ params, request }) => {
    const t = findTicket(String(params.ticketId));
    if (!t) return notFound('Ticket');
    const cycle = Number(new URL(request.url).searchParams.get('cycle')) || t.cycleNo;
    return ok(buildRibbon(t.ticketId, cycle));
  }),

  // ── handoff ───────────────────────────────────────────────────────────────
  http.post(url('/tickets/:ticketId/handoff'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    if (t.status === 'CLOSED') return unprocessable('Ticket is closed');
    if (!mayAdvance(t.ticketId)) {
      // A developer cannot push a ticket into Deployment while it sits with QA.
      return unprocessable(
        'Only the current stage owner may advance this ticket. It is currently with ' +
          (userRef(t.assigneeId, db)?.displayName ?? 'nobody'),
      );
    }
    const body = (await request.json()) as {
      toStageCode: string; toUserId: number; note?: string; effortHours?: number;
    };
    const target = db.stages.find((s) => s.stageCode === body.toStageCode);
    if (!target) return validationFailed({ toStageCode: ['is not a stage on this workflow'] });
    // Blocking by default — decision G-1. Without it the per-resource roll-up
    // is fiction within a month.
    if (body.effortHours == null) {
      return validationFailed({
        effortHours: ['effort for the stage you are leaving is required at handoff'],
      });
    }

    const now = new Date().toISOString();
    const leaving = sealCurrent(t.ticketId, t.cycleNo, now);
    if (body.effortHours > 0) {
      db.effortLogs.push({
        id: nextId(db, 'effort'), ticketId: t.ticketId, userId: db.currentUserId,
        hours: body.effortHours, workDate: now.slice(0, 10), note: null,
        stageCode: leaving?.stageCode ?? t.currentStageCode,
        cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null, createdAt: now,
      });
    }

    db.transitions.push({
      id: nextId(db, 'transition'), ticketId: t.ticketId,
      cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      stageCode: target.stageCode, ownerId: body.toUserId, ownerRole: target.ownerRole,
      enteredAt: now, exitedAt: null, durationMins: null,
      action: 'FORWARD', note: body.note ?? null, skipReason: null,
    });
    t.currentStageCode = target.stageCode;
    t.assigneeId = body.toUserId;
    t.status = 'IN_PROGRESS';
    t.version += 1;
    t.updatedAt = now;

    db.history.push({
      id: nextId(db, 'history'), ticketId: t.ticketId, action: 'STAGE_ADVANCED',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: 'currentStageCode', oldValue: leaving?.stageCode ?? null,
      newValue: target.stageCode, note: body.note ?? null,
      stageCode: target.stageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: now,
    });
    db.notifications.push({
      id: nextId(db, 'notification'), userId: body.toUserId, eventKey: 'TICKET_HANDED_OFF',
      title: `${t.ticketId} handed to you at ${target.displayName}`,
      body: body.note ?? '', ticketId: t.ticketId, isRead: false,
      deepLink: `/tickets/${t.ticketId}`, createdAt: now,
    });
    return ok(buildRibbon(t.ticketId, t.cycleNo));
  }),

  // ── rework ────────────────────────────────────────────────────────────────
  http.post(url('/tickets/:ticketId/rework'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    if (!mayAdvance(t.ticketId)) {
      return unprocessable('Only the current stage owner may move this ticket');
    }
    const body = (await request.json()) as {
      toStageCode: string; reason: string; action?: string; toUserId?: number; effortHours?: number;
    };
    if (!body.reason?.trim()) return validationFailed({ reason: ['is mandatory on a backward move'] });

    const from = db.stages.find((s) => s.stageCode === t.currentStageCode);
    const target = db.stages.find((s) => s.stageCode === body.toStageCode);
    if (!target) return validationFailed({ toStageCode: ['is not a stage on this workflow'] });
    if (from && !from.canReturnTo.includes(target.stageCode)) {
      return unprocessable(
        `${from.displayName} may only return to ${from.canReturnTo.join(', ') || 'nothing'}`,
      );
    }

    const now = new Date().toISOString();
    const leaving = sealCurrent(t.ticketId, t.cycleNo, now);
    if (leaving) leaving.action = body.action ?? 'REWORK';
    if (body.effortHours) {
      db.effortLogs.push({
        id: nextId(db, 'effort'), ticketId: t.ticketId, userId: db.currentUserId,
        hours: body.effortHours, workDate: now.slice(0, 10), note: null,
        stageCode: leaving?.stageCode ?? t.currentStageCode,
        cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null, createdAt: now,
      });
    }

    // iterationNo increments — and cycleNo does NOT. Two independent counters:
    // iteration measures rework inside a cycle, cycle measures reopens after
    // closure. Conflating them is the likeliest defect in this domain.
    t.iterationNo += 1;
    t.currentStageCode = target.stageCode;
    t.assigneeId = body.toUserId ?? t.assigneeId;
    t.status = 'REWORK';
    t.version += 1;
    t.updatedAt = now;
    // The planned close date deliberately does not move — decision G-2. The
    // original commitment stands; rework is what iterationNo measures.

    db.transitions.push({
      id: nextId(db, 'transition'), ticketId: t.ticketId,
      cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      stageCode: target.stageCode, ownerId: t.assigneeId, ownerRole: target.ownerRole,
      enteredAt: now, exitedAt: null, durationMins: null,
      action: body.action ?? 'REWORK', note: body.reason, skipReason: null,
    });
    db.history.push({
      id: nextId(db, 'history'), ticketId: t.ticketId, action: 'REWORK',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: 'iterationNo', oldValue: String(t.iterationNo - 1), newValue: String(t.iterationNo),
      note: body.reason, stageCode: target.stageCode,
      cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: now,
    });
    return ok(buildRibbon(t.ticketId, t.cycleNo));
  }),

  // ── skip ──────────────────────────────────────────────────────────────────
  http.post(url('/tickets/:ticketId/skip-stage'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const me = currentUser(db);
    if (me.role !== 'PM' && me.role !== 'ADMIN') {
      return unprocessable('Only a PM or Admin may skip a stage');
    }
    const body = (await request.json()) as { reason: string; toStageCode?: string };
    if (!body.reason?.trim()) return validationFailed({ reason: ['is mandatory when skipping'] });

    const now = new Date().toISOString();
    const skipped = sealCurrent(t.ticketId, t.cycleNo, now);
    // The segment renders struck-through with the reason on hover rather than
    // disappearing — a stage that silently vanishes makes history unreadable.
    if (skipped) { skipped.action = 'SKIP'; skipped.skipReason = body.reason; }

    const current = db.stages.find((s) => s.stageCode === t.currentStageCode);
    const next = db.stages.find(
      (s) => s.stageCode === (body.toStageCode ?? nextStageCode(current?.sequence ?? 0)),
    );
    if (!next) return validationFailed({ toStageCode: ['no stage to skip to'] });

    db.transitions.push({
      id: nextId(db, 'transition'), ticketId: t.ticketId,
      cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      stageCode: next.stageCode, ownerId: t.assigneeId, ownerRole: next.ownerRole,
      enteredAt: now, exitedAt: null, durationMins: null,
      action: 'FORWARD', note: null, skipReason: null,
    });
    t.currentStageCode = next.stageCode;
    t.version += 1;
    return ok(buildRibbon(t.ticketId, t.cycleNo));
  }),

  // ── journey roll-up ───────────────────────────────────────────────────────
  http.get(url('/tickets/:ticketId/journey'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const cycle = Number(new URL(request.url).searchParams.get('cycle')) || t.cycleNo;

    const rows = db.transitions
      .filter((x) => x.ticketId === t.ticketId && x.cycleNo === cycle)
      .map((h) => {
        // Effort joins on cycleNo AS WELL AS stage and iteration. Omitting
        // cycleNo double-counts cycle 1's effort into cycle 2 after a reopen —
        // a defect in the blueprint's own query, corrected in PLAN.md §3.4.
        const effortHrs = round(
          db.effortLogs
            .filter(
              (e) =>
                e.ticketId === t.ticketId &&
                e.cycleNo === h.cycleNo &&
                e.stageCode === h.stageCode &&
                e.iterationNo === h.iterationNo,
            )
            .reduce((s, e) => s + e.hours, 0),
        );
        return {
          iterationNo: h.iterationNo, cycleNo: h.cycleNo, stageCode: h.stageCode,
          resource: userRef(h.ownerId, db), role: h.ownerRole,
          enteredAt: h.enteredAt, exitedAt: h.exitedAt, durationMins: h.durationMins,
          effortHrs,
          // The insight that justifies the whole feature: a stage showing two
          // days of duration against two hours of effort is a queue problem,
          // not a capacity problem.
          idleMins: h.durationMins != null
            ? Math.max(0, h.durationMins - Math.round(effortHrs * 60))
            : null,
        };
      });

    const byResource = new Map<number, number>();
    for (const e of db.effortLogs.filter((e) => e.ticketId === t.ticketId && e.cycleNo === cycle)) {
      byResource.set(e.userId, (byResource.get(e.userId) ?? 0) + e.hours);
    }

    return ok({
      rows,
      perResource: [...byResource.entries()].map(([id, hrs]) => ({
        resource: userRef(id, db), effortHrs: round(hrs),
      })),
      cycleTotalHrs: round(
        db.effortLogs.filter((e) => e.ticketId === t.ticketId && e.cycleNo === cycle)
          .reduce((s, e) => s + e.hours, 0),
      ),
      allCyclesTotalHrs: round(
        db.effortLogs.filter((e) => e.ticketId === t.ticketId).reduce((s, e) => s + e.hours, 0),
      ),
    });
  }),

  // ── stage queue ───────────────────────────────────────────────────────────
  /**
   * C-062 · S-31's team inbox.
   *
   * ## `scopedTickets` is deliberately not used here, and that is the whole task
   *
   * It applied `assigned_to = me` for Developer, QA and Deployment — §10.2's row
   * scope, and correct everywhere else — which made this endpoint **return only
   * what the caller is already holding**. A QA resource's "Waiting in QA" listed
   * their own work and nothing queued, so the one screen §17 item 12 asks for
   * ("without a shared 'waiting in QA' list, tickets stall between the handoff
   * and someone noticing") showed exactly the stall it exists to prevent. The
   * handler read plausibly and was empty in the only session that matters.
   *
   * Project membership instead — Admin sees every project, everybody else sees
   * the projects they are on. This is `StageQueueSubscriptionScope`'s rule
   * (D-014), which chose it for this screen and said so: the room grants on
   * membership and its subscriber then "refetches `GET /stages/queue`, which
   * applies whatever scope C-062 gives it". This is C-062 giving it.
   *
   * 🔴 **The server has not agreed to this yet.** `ScopeResolver` still answers
   * `assigned_to = me` for these three roles on every ticket read, so on the
   * real backend two things follow: `GET /stages/queue` does not exist at all,
   * and a queue built on this rule would list tickets whose detail page 404s.
   * That is a decision on Stream A's guard rather than one this file can make —
   * raised with Shivendra rather than assumed. What is here is the shape S-31
   * needs, which is what the screen is built and demoed against.
   *
   * Two narrowings that keep this honest and should survive whatever is decided:
   * **`stage` is mandatory**, so this can never degrade into "every ticket on my
   * projects", and closed tickets are excluded — a queue is work waiting, not an
   * archive.
   */
  http.get(url('/stages/queue'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    const stage = q.get('stage');
    const now = Date.now();

    const me = currentUser(db);
    const onMyProjects = (t: { projectId: number }) =>
      me.role === 'ADMIN' || me.projectIds.includes(t.projectId);

    const rows = db.tickets
      .filter(onMyProjects)
      .filter((t) => t.currentStageCode === stage && t.status !== 'CLOSED')
      .filter((t) => !q.get('projectId') || t.projectId === Number(q.get('projectId')))
      .filter((t) => q.get('unassignedOnly') !== 'true' || t.assigneeId == null)
      .map((t) => {
        const open = db.transitions.find(
          (x) => x.ticketId === t.ticketId && x.exitedAt === null,
        );
        const enteredStageAt = open?.enteredAt ?? t.createdAt;
        const stageDef = db.stages.find((s) => s.stageCode === stage);
        const mins = workingMinutes(enteredStageAt, new Date(now).toISOString());
        return {
          ticket: ticketDto(t, db),
          enteredStageAt,
          timeInStageMins: mins,
          stageSlaBreached: !!stageDef?.stageSlaHrs && mins > stageDef.stageSlaHrs * 60,
        };
      })
      // Oldest first — the ticket rotting longest is the one to pick up.
      .sort((a, b) => b.timeInStageMins - a.timeInStageMins);

    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),
];

function nextStageCode(afterSequence: number): string | undefined {
  return getDb().stages.find((s) => s.sequence === afterSequence + 1)?.stageCode;
}
