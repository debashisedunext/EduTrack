import { http } from 'msw';
import { getDb, nextId } from '../db';
import type { Ticket } from '../db';
import {
  currentUser, findTicket, noContent, notFound, ok, paginate,
  problem, scopedTickets, ticketDto, unprocessable, url, userRef, validationFailed,
} from './util';

/** Tickets, comments, attachments, effort, history — everything under /tickets. */
export const ticketHandlers = [
  // ── list ──────────────────────────────────────────────────────────────────
  http.get(url('/tickets'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    let rows = scopedTickets(db);

    const text = q.get('q')?.toLowerCase();
    if (text) {
      rows = rows.filter(
        (t) =>
          t.title.toLowerCase().includes(text) ||
          t.description.toLowerCase().includes(text) ||
          t.ticketId.toLowerCase().includes(text),
      );
    }
    const eq = (key: string, get: (t: Ticket) => unknown) => {
      const v = q.get(key);
      if (v !== null && v !== '') rows = rows.filter((t) => String(get(t)) === v);
    };
    eq('projectId', (t) => t.projectId);
    eq('clientId', (t) => t.clientId);
    eq('taskTypeId', (t) => t.taskTypeId);
    eq('moduleId', (t) => t.moduleId);
    eq('level', (t) => t.level);
    eq('status', (t) => t.status);
    eq('stage', (t) => t.currentStageCode);
    eq('assigneeId', (t) => t.assigneeId);
    eq('isDelayed', (t) => t.isDelayed);
    eq('isClientRaised', (t) => t.isClientRaised);
    if (q.get('reopenedOnly') === 'true') rows = rows.filter((t) => t.reopenCount > 0);
    if (q.get('unassigned') === 'true') rows = rows.filter((t) => t.assigneeId == null);
    if (q.get('excludeClosed') === 'true') rows = rows.filter((t) => t.status !== 'CLOSED');

    // Date-only, inclusive range filters. C-015's "Due Today" and "Closed This
    // Month" saved views are the reason `dueFrom`/`dueTo` actually filter here
    // now — the contract already declared them but no handler read them.
    const dateRange = (fromKey: string, toKey: string, get: (t: Ticket) => string | null) => {
      const from = q.get(fromKey);
      const to = q.get(toKey);
      if (!from && !to) return;
      rows = rows.filter((t) => {
        const raw = get(t);
        if (!raw) return false;
        const date = raw.slice(0, 10);
        if (from && date < from) return false;
        if (to && date > to) return false;
        return true;
      });
    };
    dateRange('dueFrom', 'dueTo', (t) => t.plannedCloseDate);
    dateRange('closedFrom', 'closedTo', (t) => t.actualCloseDate);

    const sort = q.get('sort') ?? '-createdAt';
    const desc = sort.startsWith('-');
    const key = (desc ? sort.slice(1) : sort) as keyof Ticket;
    rows = [...rows].sort((a, b) => {
      const av = String(a[key] ?? ''), bv = String(b[key] ?? '');
      return desc ? bv.localeCompare(av) : av.localeCompare(bv);
    });

    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map((t) => ticketDto(t, db)), meta);
  }),

  // ── create ────────────────────────────────────────────────────────────────
  http.post(url('/tickets'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, unknown>;

    const errors: Record<string, string[]> = {};
    if (!body.title || String(body.title).trim().length < 3) {
      errors.title = ['must be at least 3 characters'];
    }
    if (!body.projectId) errors.projectId = ['must not be null'];
    if (!body.taskTypeId) errors.taskTypeId = ['must not be null'];
    // §7.5's four fields. The module is checked against the master because an
    // unknown id is a 400 on the real thing, not a ticket that quietly stores
    // one. Deliberately NOT checked: whether it is active — a client that only
    // ever offers active rows cannot send an inactive one, and rejecting it here
    // would make this the second place that rule lives.
    if (body.moduleId != null && !db.modules.some((m) => m.id === Number(body.moduleId))) {
      errors.moduleId = ['no such module'];
    }
    const tooLong = (v: unknown, max: number) => typeof v === 'string' && v.length > max;
    if (tooLong(body.screenName, 120)) errors.screenName = ['must be at most 120 characters'];
    if (tooLong(body.feature, 120)) errors.feature = ['must be at most 120 characters'];
    if (tooLong(body.stepsToGenerate, 20000)) {
      errors.stepsToGenerate = ['must be at most 20000 characters'];
    }
    if (Object.keys(errors).length) return validationFailed(errors);

    const project = db.projects.find((p) => p.id === Number(body.projectId));
    if (!project) return notFound('Project');

    // The real thing uses `UPDATE projects SET ticket_seq = LAST_INSERT_ID(seq+1)`.
    // Never a COUNT(*) — that produces duplicate IDs under concurrency and fails
    // silently. The mock mirrors the increment so the format is exercised.
    project.ticketSeq += 1;
    const ticketId = `${project.projectCode}-26-${String(project.ticketSeq).padStart(5, '0')}`;
    const now = new Date().toISOString();
    const taskType = db.taskTypes.find((t) => t.id === Number(body.taskTypeId));
    const level = (body.level as Ticket['level']) ?? taskType?.defaultLevel ?? 'MEDIUM';

    const ticket: Ticket = {
      ticketId,
      title: String(body.title),
      description: String(body.description ?? ''),
      projectId: project.id,
      clientId: (body.clientId as number) ?? null,
      clientContactId: (body.clientContactId as number) ?? null,
      isClientRaised: Boolean(body.isClientRaised),
      taskTypeId: Number(body.taskTypeId),
      moduleId: body.moduleId == null ? null : Number(body.moduleId),
      screenName: (body.screenName as string) ?? null,
      feature: (body.feature as string) ?? null,
      stepsToGenerate: (body.stepsToGenerate as string) ?? null,
      level,
      originalLevel: level,
      status: body.saveAsDraft ? 'NEW' : 'NEW',
      currentStageCode: 'INTAKE',
      assigneeId: (body.assigneeId as number) ?? null,
      reportedById: db.currentUserId,
      cycleNo: 1,
      iterationNo: 1,
      reopenCount: 0,
      plannedCloseDate:
        (body.plannedCloseDate as string) ??
        new Date(Date.now() + (taskType?.defaultSlaHrs ?? 48) * 3_600_000).toISOString(),
      actualCloseDate: null,
      isDelayed: false,
      delayedSince: null,
      estimatedHrs: (body.estimatedHrs as number) ?? null,
      pctComplete: 0,
      // Kept, not dropped. `watcherIds` has been on the request since D-001 and
      // C-010's watcher picker sends it; until now `/full` answered
      // `watchers: []` regardless, so the whole path looked wired and was not.
      watcherIds: Array.isArray(body.watcherIds) ? (body.watcherIds as number[]) : [],
      createdAt: now,
      updatedAt: now,
      version: 1,
    };
    db.tickets.unshift(ticket);
    db.cycles.push({ ticketId, cycleNo: 1, isSealed: false, startedAt: now, closedAt: null, reason: null });
    db.transitions.push({
      id: nextId(db, 'transition'), ticketId, cycleNo: 1, iterationNo: 1,
      stageCode: 'INTAKE', ownerId: db.currentUserId, ownerRole: 'SUPPORT',
      enteredAt: now, exitedAt: null, durationMins: null,
      action: 'FORWARD', note: null, skipReason: null,
    });
    db.history.push({
      id: nextId(db, 'history'), ticketId, action: 'CREATED',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: null, oldValue: null, newValue: null, note: null,
      stageCode: 'INTAKE', cycleNo: 1, iterationNo: 1,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16).padStart(16, '0')}`,
      createdAt: now,
    });

    return ok(ticketDto(ticket, db), undefined, {
      status: 201,
      headers: { Location: `/api/v1/tickets/${ticketId}/full` },
    });
  }),

  // ── bulk reassign ─────────────────────────────────────────────────────────
  http.post(url('/tickets/bulk-reassign'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as { ticketIds: string[]; toUserId: number; reason: string };
    if (!body.reason || body.reason.trim().length < 3) {
      return validationFailed({ reason: ['must be at least 3 characters'] });
    }
    const results = body.ticketIds.map((id) => {
      const t = findTicket(id, db);
      if (!t) return { ticketId: id, ok: false, reason: 'Not found or out of scope' };
      t.assigneeId = body.toUserId;
      t.updatedAt = new Date().toISOString();
      // Each move writes its own history entry — a single summary row would
      // destroy the per-ticket audit trail, which is the point of having one.
      db.history.push({
        id: nextId(db, 'history'), ticketId: id, action: 'REASSIGNED',
        actorId: db.currentUserId, actorType: 'USER',
        fieldName: 'assigneeId', oldValue: null, newValue: String(body.toUserId),
        note: body.reason, stageCode: t.currentStageCode,
        cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null,
        entryHash: `sha256:${nextId(db, 'hash').toString(16)}`,
        createdAt: new Date().toISOString(),
      });
      return { ticketId: id, ok: true, reason: null };
    });
    return ok({
      succeeded: results.filter((r) => r.ok).length,
      failed: results.filter((r) => !r.ok).length,
      results,
    });
  }),

  // ── detail ────────────────────────────────────────────────────────────────
  http.get(url('/tickets/:ticketId/full'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');

    const cycle = Number(new URL(request.url).searchParams.get('cycle')) || t.cycleNo;
    const me = currentUser(db);
    const stage = db.stages.find((s) => s.stageCode === t.currentStageCode);
    const canAdvance =
      !!stage &&
      t.status !== 'CLOSED' &&
      (me.role === 'ADMIN' || me.role === 'PM' || t.assigneeId === me.id);

    const etag = `W/"${t.ticketId}-${t.version}"`;
    if (request.headers.get('If-None-Match') === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }

    return ok(
      {
        ticket: ticketDto(t, db),
        cycles: db.cycles
          .filter((c) => c.ticketId === t.ticketId)
          .map((c) => ({
            ...c,
            totalEffortHrs: round(
              db.effortLogs
                .filter((e) => e.ticketId === t.ticketId && e.cycleNo === c.cycleNo)
                .reduce((s, e) => s + e.hours, 0),
            ),
          })),
        ribbon: buildRibbon(t.ticketId, cycle),
        history: db.history
          .filter((h) => h.ticketId === t.ticketId && h.cycleNo === cycle)
          .map(historyDto),
        effortLogs: db.effortLogs
          .filter((e) => e.ticketId === t.ticketId && e.cycleNo === cycle)
          .map(effortDto),
        comments: db.comments
          .filter((c) => c.ticketId === t.ticketId && c.cycleNo === cycle)
          .map(commentDto),
        attachments: db.attachments
          .filter((a) => a.ticketId === t.ticketId && a.cycleNo === cycle)
          .map(attachmentDto),
        watchers: t.watcherIds.map((id) => userRef(id, db)).filter(Boolean),
        // Resolved server-side so the client renders buttons from this rather
        // than re-deriving permissions. Two implementations of the same rule
        // always diverge, and the client's copy is the one that gets it wrong.
        availableActions: [
          ...(canAdvance ? ['handoff', 'rework'] : []),
          ...(me.role === 'PM' || me.role === 'ADMIN' ? ['skip-stage', 'close', 'reopen', 'priority'] : []),
          'comment', 'effort', 'attach',
        ],
      },
      undefined,
      { headers: { ETag: etag } },
    );
  }),

  // ── field updates ─────────────────────────────────────────────────────────
  http.patch(url('/tickets/:ticketId'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');

    const ifMatch = request.headers.get('If-Match');
    if (ifMatch && ifMatch !== `W/"${t.ticketId}-${t.version}"`) {
      return problem(412, 'stale', 'This ticket changed since you loaded it');
    }
    const body = (await request.json()) as Partial<Ticket>;
    Object.assign(t, body);
    t.version += 1;
    t.updatedAt = new Date().toISOString();
    return ok(ticketDto(t, db));
  }),

  http.post(url('/tickets/:ticketId/assign'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const { assigneeId } = (await request.json()) as { assigneeId: number };
    t.assigneeId = assigneeId;
    t.version += 1;
    // Reassignment inside a stage does NOT create a new ribbon segment.
    db.history.push({
      id: nextId(db, 'history'), ticketId: t.ticketId, action: 'STAGE_REASSIGNED',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: 'assigneeId', oldValue: null, newValue: String(assigneeId),
      note: null, stageCode: t.currentStageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: new Date().toISOString(),
    });
    return ok(ticketDto(t, db));
  }),

  http.patch(url('/tickets/:ticketId/priority'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const { level, reason } = (await request.json()) as { level: Ticket['level']; reason?: string };
    if (t.assigneeId && !reason) {
      return validationFailed({ reason: ['is mandatory once the ticket is assigned'] });
    }
    const old = t.level;
    t.level = level;
    // originalLevel is never overwritten — it is what makes "born critical vs
    // became critical" reportable, and it is the first thing a manager asks.
    t.version += 1;
    db.history.push({
      id: nextId(db, 'history'), ticketId: t.ticketId, action: 'LEVEL_CHANGED',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: 'level', oldValue: old, newValue: level, note: reason ?? null,
      stageCode: t.currentStageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: new Date().toISOString(),
    });
    return ok(ticketDto(t, db));
  }),

  http.post(url('/tickets/:ticketId/quick-update'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const body = (await request.json()) as Record<string, unknown>;
    if (body.status) t.status = body.status as Ticket['status'];
    if (body.pctComplete != null) t.pctComplete = Number(body.pctComplete);
    if (body.revisedEta) t.plannedCloseDate = String(body.revisedEta);
    if (body.effortHours) {
      db.effortLogs.push({
        id: nextId(db, 'effort'), ticketId: t.ticketId, userId: db.currentUserId,
        hours: Number(body.effortHours),
        workDate: String(body.effortDate ?? new Date().toISOString().slice(0, 10)),
        note: (body.workNote as string) ?? null,
        // Auto-stamped from the ticket's current position — never sent by the
        // client, which would let a stale tab attribute effort to a stage the
        // ticket left twenty minutes ago.
        stageCode: t.currentStageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null, createdAt: new Date().toISOString(),
      });
    }
    t.version += 1;
    t.updatedAt = new Date().toISOString();
    return ok(ticketDto(t, db));
  }),

  // ── effort: POST only, deliberately ───────────────────────────────────────
  http.post(url('/tickets/:ticketId/effort'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const body = (await request.json()) as { hours: number; workDate: string; note?: string };
    if (!(body.hours > 0) || body.hours > 24) {
      return validationFailed({ hours: ['must be between 0.25 and 24'] });
    }
    const entry = {
      id: nextId(db, 'effort'), ticketId: t.ticketId, userId: db.currentUserId,
      hours: body.hours, workDate: body.workDate, note: body.note ?? null,
      stageCode: t.currentStageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      isCorrection: false, correctsEntryId: null, createdAt: new Date().toISOString(),
    };
    db.effortLogs.push(entry);
    return ok(effortDto(entry), undefined, { status: 201 });
  }),

  http.get(url('/tickets/:ticketId/effort-logs'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const q = new URL(request.url).searchParams;
    let rows = db.effortLogs.filter((e) => e.ticketId === t.ticketId);
    if (q.get('cycle')) rows = rows.filter((e) => e.cycleNo === Number(q.get('cycle')));
    if (q.get('stage')) rows = rows.filter((e) => e.stageCode === q.get('stage'));
    if (q.get('iteration')) rows = rows.filter((e) => e.iterationNo === Number(q.get('iteration')));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(effortDto), {
      ...meta,
      cycleTotalHrs: round(rows.filter((e) => e.cycleNo === t.cycleNo).reduce((s, e) => s + e.hours, 0)),
      grandTotalHrs: round(db.effortLogs.filter((e) => e.ticketId === t.ticketId).reduce((s, e) => s + e.hours, 0)),
    });
  }),

  // ── history: GET only, deliberately ───────────────────────────────────────
  http.get(url('/tickets/:ticketId/history'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const q = new URL(request.url).searchParams;
    let rows: ReturnType<typeof historyDto>[] = db.history
      .filter((h) => h.ticketId === t.ticketId)
      .filter((h) => !q.get('cycle') || h.cycleNo === Number(q.get('cycle')))
      .map(historyDto);

    // Comments interleave into one chronological stream rather than arriving as
    // a second list the reader has to reconcile by timestamp.
    if (q.get('include')?.includes('comments')) {
      const asHistory = db.comments
        .filter((c) => c.ticketId === t.ticketId)
        .filter((c) => !q.get('cycle') || c.cycleNo === Number(q.get('cycle')))
        .map((c) => ({
          id: 100_000 + c.id, action: 'COMMENTED',
          actor: userRef(c.authorId, db), actorType: 'USER' as const,
          fieldName: null, oldValue: null, newValue: null, note: c.body,
          stageCode: c.stageCode, cycleNo: c.cycleNo, iterationNo: c.iterationNo,
          isCorrection: false, correctsEntryId: null,
          entryHash: 'sha256:comment', createdAt: c.createdAt,
        }));
      rows = [...rows, ...asHistory];
    }
    rows.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),

  // ── closure and cycles ────────────────────────────────────────────────────
  http.post(url('/tickets/:ticketId/resolve'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const body = (await request.json()) as { resolutionSummary?: string };
    if (!body.resolutionSummary) return validationFailed({ resolutionSummary: ['must not be blank'] });
    t.status = 'RESOLVED';
    t.version += 1;
    return ok(ticketDto(t, db));
  }),

  http.post(url('/tickets/:ticketId/close'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    if (t.status === 'CLOSED') return unprocessable('Ticket is already closed');
    const body = (await request.json()) as { resolutionSummary?: string; actualCloseDate?: string };
    if (!body.resolutionSummary) return validationFailed({ resolutionSummary: ['must not be blank'] });

    const now = body.actualCloseDate ?? new Date().toISOString();
    t.status = 'CLOSED';
    t.actualCloseDate = now;
    t.currentStageCode = 'CLOSED';
    t.version += 1;
    const open = db.transitions.find(
      (x) => x.ticketId === t.ticketId && x.cycleNo === t.cycleNo && x.exitedAt === null,
    );
    if (open) {
      open.exitedAt = now;
      open.durationMins = Math.round((Date.parse(now) - Date.parse(open.enteredAt)) / 60000);
    }
    const cycle = db.cycles.find((c) => c.ticketId === t.ticketId && c.cycleNo === t.cycleNo);
    if (cycle) { cycle.isSealed = true; cycle.closedAt = now; }
    return ok(ticketDto(t, db));
  }),

  http.post(url('/tickets/:ticketId/reopen'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    if (t.status !== 'CLOSED') {
      return unprocessable('Ticket is not closed, so there is nothing to reopen');
    }
    const body = (await request.json()) as {
      reason: string; restartStageCode?: string; assigneeId?: number; plannedCloseDate?: string;
    };
    if (!body.reason) return validationFailed({ reason: ['must not be blank'] });

    const now = new Date().toISOString();
    // Seal cycle N, open N+1. Cycle N's effort is never touched — each cycle
    // keeps its own ribbon and its own hours.
    const sealed = db.cycles.find((c) => c.ticketId === t.ticketId && c.cycleNo === t.cycleNo);
    if (sealed) { sealed.isSealed = true; sealed.closedAt = sealed.closedAt ?? now; }

    t.cycleNo += 1;
    t.iterationNo = 1;
    t.reopenCount += 1;
    t.status = 'REOPENED';
    t.actualCloseDate = null;
    t.currentStageCode = body.restartStageCode ?? 'TRIAGE';
    t.assigneeId = body.assigneeId ?? t.assigneeId;
    t.plannedCloseDate = body.plannedCloseDate ?? t.plannedCloseDate;
    t.version += 1;

    db.cycles.push({
      ticketId: t.ticketId, cycleNo: t.cycleNo, isSealed: false,
      startedAt: now, closedAt: null, reason: body.reason,
    });
    const stage = db.stages.find((s) => s.stageCode === t.currentStageCode)!;
    db.transitions.push({
      id: nextId(db, 'transition'), ticketId: t.ticketId, cycleNo: t.cycleNo, iterationNo: 1,
      stageCode: t.currentStageCode!, ownerId: t.assigneeId, ownerRole: stage.ownerRole,
      enteredAt: now, exitedAt: null, durationMins: null,
      action: 'FORWARD', note: body.reason, skipReason: null,
    });
    db.history.push({
      id: nextId(db, 'history'), ticketId: t.ticketId, action: 'REOPENED',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: 'cycleNo', oldValue: String(t.cycleNo - 1), newValue: String(t.cycleNo),
      note: body.reason, stageCode: t.currentStageCode,
      cycleNo: t.cycleNo, iterationNo: 1,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: now,
    });
    return ok(ticketDto(t, db));
  }),

  // ── comments ──────────────────────────────────────────────────────────────
  http.get(url('/tickets/:ticketId/comments'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const cycle = new URL(request.url).searchParams.get('cycle');
    const rows = db.comments
      .filter((c) => c.ticketId === t.ticketId)
      .filter((c) => !cycle || c.cycleNo === Number(cycle));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(commentDto), meta);
  }),

  http.post(url('/tickets/:ticketId/comments'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const body = (await request.json()) as {
      body: string; isClientVisible?: boolean; mentionUserIds?: number[];
    };
    if (!body.body?.trim()) return validationFailed({ body: ['must not be blank'] });
    const c = {
      id: nextId(db, 'comment'), ticketId: t.ticketId, body: body.body, originalBody: null,
      authorId: db.currentUserId,
      // Default internal, always. An accidental leak to a client costs far more
      // than an extra click.
      isClientVisible: body.isClientVisible ?? false,
      isEdited: false, isDeleted: false,
      stageCode: t.currentStageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      mentionIds: body.mentionUserIds ?? [], createdAt: new Date().toISOString(),
    };
    db.comments.push(c);
    return ok(commentDto(c), undefined, { status: 201 });
  }),

  http.patch(url('/tickets/:ticketId/comments/:commentId'), async ({ params, request }) => {
    const db = getDb();
    const c = db.comments.find((x) => x.id === Number(params.commentId));
    if (!c) return notFound('Comment');
    if (c.authorId !== db.currentUserId) {
      return unprocessable('Only the author may edit a comment');
    }
    const ageMs = Date.now() - Date.parse(c.createdAt);
    if (ageMs > 5 * 60_000) {
      return unprocessable('The five-minute edit window has closed');
    }
    const body = (await request.json()) as { body: string };
    c.originalBody = c.originalBody ?? c.body;
    c.body = body.body;
    c.isEdited = true;
    return ok(commentDto(c));
  }),

  http.delete(url('/tickets/:ticketId/comments/:commentId'), ({ params }) => {
    const db = getDb();
    const c = db.comments.find((x) => x.id === Number(params.commentId));
    if (!c) return notFound('Comment');
    // Tombstone — the row survives, the body is cleared. Nothing vanishes.
    c.isDeleted = true;
    c.body = '';
    return noContent();
  }),

  // ── attachments ───────────────────────────────────────────────────────────
  http.get(url('/tickets/:ticketId/attachments'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const q = new URL(request.url).searchParams;
    let rows = db.attachments.filter((a) => a.ticketId === t.ticketId && !a.isDeleted);
    if (q.get('cycle')) rows = rows.filter((a) => a.cycleNo === Number(q.get('cycle')));
    if (q.get('clientVisibleOnly') === 'true') rows = rows.filter((a) => a.isClientVisible);
    return ok(rows.map(attachmentDto));
  }),

  http.post(url('/tickets/:ticketId/attachments'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const form = await request.formData();
    const file = form.get('file') as File | null;
    if (!file) return validationFailed({ file: ['must not be null'] });
    if (file.size > 10 * 1024 * 1024) {
      return problem(413, 'file-too-large', 'File exceeds the 10 MB limit');
    }
    const a = {
      id: nextId(db, 'attachment'), ticketId: t.ticketId, fileName: file.name,
      contentType: file.type || 'application/octet-stream', sizeBytes: file.size,
      // PENDING until the scan passes — the file is not downloadable before then.
      scanStatus: 'PENDING' as import('../db').Attachment['scanStatus'],
      isClientVisible: form.get('isClientVisible') === 'true',
      isDeleted: false, uploadedById: db.currentUserId,
      stageCode: t.currentStageCode, cycleNo: t.cycleNo, createdAt: new Date().toISOString(),
    };
    db.attachments.push(a);
    // The scan completing a moment later, so the UI's PENDING state is real.
    setTimeout(() => { a.scanStatus = 'CLEAN'; }, 1500);
    return ok(attachmentDto(a), undefined, { status: 201 });
  }),

  http.delete(url('/tickets/:ticketId/attachments/:attachmentId'), ({ params }) => {
    const db = getDb();
    const a = db.attachments.find((x) => x.id === Number(params.attachmentId));
    if (!a) return notFound('Attachment');
    a.isDeleted = true;
    return noContent();
  }),

  // ── mail log ──────────────────────────────────────────────────────────────
  http.get(url('/tickets/:ticketId/emails'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const rows = db.emailLog.filter((e) => e.ticketId === t.ticketId);
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page, meta);
  }),

  http.post(url('/tickets/:ticketId/ask-status'), ({ params }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const thread = db.chatThreads.find((x) => x.ticketId === t.ticketId);
    if (thread) {
      db.chatMessages.push({
        id: nextId(db, 'message'), threadId: thread.id,
        body: 'Status update requested on this ticket.',
        authorId: db.currentUserId, kind: 'STATUS_REQUEST',
        isEdited: false, isDeleted: false, readBy: [], createdAt: new Date().toISOString(),
      });
    }
    return new Response(null, { status: 202 });
  }),
];

// ── shared mappers ──────────────────────────────────────────────────────────
export const round = (n: number) => Math.round(n * 10) / 10;

export function historyDto(h: import('../db').HistoryEntry) {
  return {
    id: h.id, action: h.action, actor: userRef(h.actorId), actorType: h.actorType,
    fieldName: h.fieldName, oldValue: h.oldValue, newValue: h.newValue, note: h.note,
    stageCode: h.stageCode, cycleNo: h.cycleNo, iterationNo: h.iterationNo,
    isCorrection: h.isCorrection, correctsEntryId: h.correctsEntryId,
    entryHash: h.entryHash, createdAt: h.createdAt,
  };
}

export function effortDto(e: import('../db').EffortLog) {
  return {
    id: e.id, user: userRef(e.userId), hours: e.hours, workDate: e.workDate, note: e.note,
    stageCode: e.stageCode, cycleNo: e.cycleNo, iterationNo: e.iterationNo,
    isCorrection: e.isCorrection, correctsEntryId: e.correctsEntryId, createdAt: e.createdAt,
  };
}

export function commentDto(c: import('../db').Comment) {
  const db = getDb();
  return {
    id: c.id, body: c.body, originalBody: c.originalBody,
    author: userRef(c.authorId, db),
    authorRole: db.users.find((u) => u.id === c.authorId)?.role,
    isClientVisible: c.isClientVisible, isEdited: c.isEdited, isDeleted: c.isDeleted,
    editableUntil: new Date(Date.parse(c.createdAt) + 5 * 60_000).toISOString(),
    stageCode: c.stageCode, cycleNo: c.cycleNo, iterationNo: c.iterationNo,
    mentions: c.mentionIds.map((id) => userRef(id, db)),
    attachments: [], createdAt: c.createdAt,
  };
}

export function attachmentDto(a: import('../db').Attachment) {
  return {
    id: a.id, fileName: a.fileName, contentType: a.contentType, sizeBytes: a.sizeBytes,
    scanStatus: a.scanStatus,
    // Only downloadable once CLEAN, and always via a short-lived signed URL.
    downloadUrl: a.scanStatus === 'CLEAN' ? `/mock-files/${a.id}/${a.fileName}?sig=mock` : null,
    thumbnailUrl: a.scanStatus === 'CLEAN' && a.contentType.startsWith('image/')
      ? `/mock-files/${a.id}/thumb.png?sig=mock` : null,
    isClientVisible: a.isClientVisible, isDeleted: a.isDeleted,
    uploadedBy: userRef(a.uploadedById), stageCode: a.stageCode,
    cycleNo: a.cycleNo, createdAt: a.createdAt,
  };
}

/** Shared with the ribbon handlers — see ribbon.ts for the transition writes. */
export function buildRibbon(ticketId: string, cycleNo: number) {
  const db = getDb();
  const t = db.tickets.find((x) => x.ticketId === ticketId)!;
  const me = currentUser(db);
  const hops = db.transitions.filter((x) => x.ticketId === ticketId && x.cycleNo === cycleNo);
  const isPast = cycleNo < t.cycleNo;

  const segments = db.stages
    .filter((s) => s.stageCode !== 'CLOSED')
    .map((stage) => {
      const forStage = hops.filter((h) => h.stageCode === stage.stageCode);
      const last = forStage[forStage.length - 1];
      const effortHrs = round(
        db.effortLogs
          .filter((e) => e.ticketId === ticketId && e.cycleNo === cycleNo && e.stageCode === stage.stageCode)
          .reduce((s, e) => s + e.hours, 0),
      );
      let state: import('../db').SegmentState = 'PENDING';
      if (last?.skipReason) state = 'SKIPPED';
      else if (last && last.exitedAt === null) state = 'CURRENT';
      else if (forStage.length > 1) state = 'REWORKED';
      else if (forStage.length === 1) state = 'COMPLETED';

      return {
        stageCode: stage.stageCode, displayName: stage.displayName, icon: stage.icon,
        state, sequence: stage.sequence,
        owner: userRef(last?.ownerId ?? null, db), ownerRole: stage.ownerRole,
        enteredAt: last?.enteredAt ?? null, exitedAt: last?.exitedAt ?? null,
        durationMins: last?.durationMins ?? null,
        effortHrs,
        idleMins: last?.durationMins != null
          ? Math.max(0, last.durationMins - Math.round(effortHrs * 60))
          : null,
        iterationNo: last?.iterationNo ?? 1,
        loopBackCount: Math.max(0, forStage.length - 1),
        skipReason: last?.skipReason ?? null,
        handoffNote: last?.note ?? null,
      };
    });

  const stage = db.stages.find((s) => s.stageCode === t.currentStageCode);
  return {
    cycleNo,
    iterationNo: isPast ? Math.max(...hops.map((h) => h.iterationNo), 1) : t.iterationNo,
    isSealed: isPast || t.status === 'CLOSED',
    currentStageCode: isPast ? null : t.currentStageCode,
    // The golden rule, resolved server-side: only the current stage owner —
    // plus PM and Admin — may advance a ticket.
    canAdvance:
      !isPast && !!stage && t.status !== 'CLOSED' &&
      (me.role === 'ADMIN' || me.role === 'PM' || t.assigneeId === me.id),
    segments,
  };
}
