import { http } from 'msw';
import { getDb, nextId } from '../db';
import type { Attachment, Ticket, TicketLink } from '../db';
// C-029 · the client's copy of PLAN.md §3.9's allow-list, so the mock's POST
// refuses what the real server refuses. See the comment handler.
import { isRichTextEmpty, sanitizeRichText } from '@/components/ui/rich-text';
import { plannedCloseDateFor } from './sla';
import {
  currentUser, findTicket, noContent, notFound, ok, paginate,
  problem, scopedTickets, ticketDto, ticketSummaryDto, unprocessable, url, userRef,
  validationFailed,
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
    // D-061 · the list returns TicketSummary; the detail page returns Ticket.
    return ok(page.map((t) => ticketSummaryDto(t, db)), meta);
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
      // C-022 · §4B.2: derived server-side from the two fields above, never
      // trusted from the request. `body.isClientRaised` is ignored on purpose
      // — a client bug or a hand-crafted request could otherwise mark a
      // ticket client-raised with no client on it, which is exactly the value
      // this flag exists to rule out for the client-wise reports and CSAT.
      isClientRaised: body.clientId != null && body.clientContactId != null,
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
      // C-012 · the same resolution and the same calendar walk the preview
      // uses. It used to be `now + defaultSlaHrs` in wall-clock milliseconds,
      // which meant the date the form showed before saving and the date stored
      // on save differed by every weekend and holiday between them — a preview
      // that is confidently wrong is worse than none.
      plannedCloseDate:
        (body.plannedCloseDate as string) ??
        plannedCloseDateFor(
          {
            projectId: project.id,
            taskTypeId: Number(body.taskTypeId),
            level,
            assigneeId: (body.assigneeId as number) ?? null,
            from: now,
          },
          db,
        ).plannedCloseDate,
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
    const refusal = refuseUnlessPmOrAdmin(db);
    if (refusal) return refusal;
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
    return bulkResult(results);
  }),

  // ── C-017 · bulk level change, from the S-17 grid selection ───────────────
  http.patch(url('/tickets/bulk-level'), async ({ request }) => {
    const db = getDb();
    const refusal = refuseUnlessPmOrAdmin(db);
    if (refusal) return refusal;
    const body = (await request.json()) as { ticketIds: string[]; level: string; reason: string };
    if (!body.reason || body.reason.trim().length < 3) {
      return validationFailed({ reason: ['must be at least 3 characters'] });
    }
    const results = body.ticketIds.map((id) => {
      const t = findTicket(id, db);
      if (!t) return { ticketId: id, ok: false, reason: 'Not found or out of scope' };
      const previous = t.level;
      // Already at that level is *not* an error — the caller asked for an end
      // state and it holds. Writing a LEVEL_CHANGED row from HIGH to HIGH
      // would be a history entry recording nothing, which is worse than
      // silence: the History tab is read to find out what actually moved.
      if (previous === body.level) return { ticketId: id, ok: true, reason: null };

      t.level = body.level as Ticket['level'];
      // `originalLevel` is deliberately untouched. It is what makes "born
      // critical versus became critical" reportable, and a bulk path that
      // overwrote it would erase that distinction fifty rows at a time.
      // Recomputed per ticket against the working calendar, from that ticket's
      // own start — not from now, and not one shared date for the batch. A
      // level change moves the target; it does not restart the clock.
      t.plannedCloseDate = plannedCloseDateFor(
        {
          projectId: t.projectId,
          taskTypeId: t.taskTypeId,
          level: t.level,
          assigneeId: t.assigneeId,
          from: t.createdAt,
        },
        db,
      ).plannedCloseDate;
      t.updatedAt = new Date().toISOString();
      db.history.push({
        id: nextId(db, 'history'), ticketId: id, action: 'LEVEL_CHANGED',
        actorId: db.currentUserId, actorType: 'USER',
        fieldName: 'level', oldValue: previous, newValue: body.level,
        note: body.reason, stageCode: t.currentStageCode,
        cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null,
        entryHash: `sha256:${nextId(db, 'hash').toString(16)}`,
        createdAt: new Date().toISOString(),
      });
      return { ticketId: id, ok: true, reason: null };
    });
    return bulkResult(results);
  }),

  // ── C-017 · bulk close, from the S-17 grid selection ──────────────────────
  http.post(url('/tickets/bulk-close'), async ({ request }) => {
    const db = getDb();
    const refusal = refuseUnlessPmOrAdmin(db);
    if (refusal) return refusal;
    const body = (await request.json()) as {
      ticketIds: string[]; resolutionSummary: string; rootCauseCategory?: string;
    };
    if (!body.resolutionSummary || body.resolutionSummary.trim().length < 3) {
      return validationFailed({ resolutionSummary: ['must be at least 3 characters'] });
    }
    const now = new Date().toISOString();
    const results = body.ticketIds.map((id) => {
      const t = findTicket(id, db);
      if (!t) return { ticketId: id, ok: false, reason: 'Not found or out of scope' };
      // Refused rather than re-closed. Re-stamping `actualCloseDate` moves a
      // date reports already depend on, and re-sealing a sealed transition is
      // precisely the mutation the append-only rule forbids.
      if (t.status === 'CLOSED') return { ticketId: id, ok: false, reason: 'Already closed' };

      t.status = 'CLOSED';
      t.actualCloseDate = now;
      t.updatedAt = now;
      // Seal the open transition — the one permitted mutation on that table,
      // and only from NULL. A row already carrying an `exitedAt` is left alone.
      const open = db.transitions.find(
        (tr) => tr.ticketId === id && tr.cycleNo === t.cycleNo && tr.exitedAt === null,
      );
      if (open) {
        open.exitedAt = now;
        open.durationMins = Math.max(
          0, Math.round((Date.parse(now) - Date.parse(open.enteredAt)) / 60_000),
        );
      }
      const cycle = db.cycles.find((c) => c.ticketId === id && c.cycleNo === t.cycleNo);
      if (cycle) {
        cycle.isSealed = true;
        cycle.closedAt = now;
      }
      db.history.push({
        id: nextId(db, 'history'), ticketId: id, action: 'CLOSED',
        actorId: db.currentUserId, actorType: 'USER',
        fieldName: 'status', oldValue: 'OPEN', newValue: 'CLOSED',
        note: body.resolutionSummary, stageCode: t.currentStageCode,
        cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null,
        entryHash: `sha256:${nextId(db, 'hash').toString(16)}`,
        createdAt: now,
      });
      return { ticketId: id, ok: true, reason: null };
    });
    return bulkResult(results);
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
        linkedTickets: ticketLinksFor(t.ticketId, db),
        // Resolved server-side so the client renders buttons from this rather
        // than re-deriving permissions. Two implementations of the same rule
        // always diverge, and the client's copy is the one that gets it wrong.
        availableActions: [
          ...(canAdvance ? ['handoff', 'rework'] : []),
          ...(me.role === 'PM' || me.role === 'ADMIN' ? ['skip-stage', 'priority'] : []),
          // C-040: blueprint §2's "Close ticket" and "Reopen ticket" rows both
          // grant Admin, PM *and* Support — this mock granted only the first
          // two, so a Support Desk viewer never saw either button even though
          // the real backend's workflow_transitions seeds them for all three.
          ...(me.role === 'PM' || me.role === 'ADMIN' || me.role === 'SUPPORT' ? ['close', 'reopen'] : []),
          'comment', 'effort', 'attach',
        ],
      },
      undefined,
      { headers: { ETag: etag } },
    );
  }),

  // ── links · C-064, blueprint §16 item 17 ────────────────────────────────────
  // ⚠ frontend/src/mocks/ is Stream D's (D-004) — flagged rather than done
  // quietly, same as C-015's dueFrom/dueTo fix and C-019's watcher fix in
  // this same file. Without these two handlers `mock coverage > every
  // contract operation has a handler` fails the build the moment the client
  // regenerates with `createTicketLink`/`deleteTicketLink` in it, and
  // TicketLinksControl has no mock to develop against under `npm run dev`.
  http.post(url('/tickets/:ticketId/links'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');

    const body = (await request.json()) as { targetTicketId?: string; linkType?: string };
    const rawType = body.linkType?.trim().toUpperCase();
    if (!rawType || !SUBMITTABLE_LINK_TYPES.includes(rawType as (typeof SUBMITTABLE_LINK_TYPES)[number])) {
      // DUPLICATED_BY falls in here too — it is in the wire enum but never a
      // submittable value, on the real server's own reasoning: it exists only
      // as a computed label for the far side of a DUPLICATE_OF row.
      return validationFailed({
        linkType: [`Unknown link type ${body.linkType ?? ''}`.trim()],
      });
    }
    if (!body.targetTicketId) {
      return validationFailed({ targetTicketId: ['Required'] });
    }

    // Scoped, same as the path ticket — a caller must not learn whether a
    // ticket id outside their scope exists by trying to link to it.
    const target = findTicket(body.targetTicketId, db);
    if (!target) return notFound('Ticket');

    if (target.ticketId === t.ticketId) {
      return validationFailed({ targetTicketId: ['A ticket cannot be linked to itself.'] });
    }

    const canonical = canonicalizeLink(rawType, t.ticketId, target.ticketId);
    const duplicate = db.ticketLinks.some(
      (l) =>
        l.sourceTicketId === canonical.sourceId &&
        l.targetTicketId === canonical.targetId &&
        l.linkType === canonical.linkType,
    );
    if (duplicate) {
      return problem(409, 'ticket-link-conflict', 'Already linked', {
        detail: 'This relationship already exists.',
      });
    }

    const link: TicketLink = {
      id: nextId(db, 'ticketLinks'),
      sourceTicketId: canonical.sourceId,
      targetTicketId: canonical.targetId,
      linkType: canonical.linkType,
      createdById: currentUser(db).id,
      createdAt: new Date().toISOString(),
    };
    db.ticketLinks.push(link);

    return ok(linkedTicketDto(link, t.ticketId, db), undefined, { status: 201 });
  }),

  http.delete(url('/tickets/:ticketId/links/:linkId'), ({ params }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');

    const linkId = Number(params.linkId);
    const idx = db.ticketLinks.findIndex(
      (l) => l.id === linkId && (l.sourceTicketId === t.ticketId || l.targetTicketId === t.ticketId),
    );
    // Same answer whether the id never existed or names a link touching some
    // other pair of tickets entirely — a caller must not learn "link 41 is
    // real, just not yours" by probing ids across tickets.
    if (idx === -1) return notFound('Link');

    db.ticketLinks.splice(idx, 1);
    return noContent();
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

    /*
      C-069 · **one `FIELD_CHANGED` row per field that actually changed**, which
      this handler did not write at all before. `TicketWriteService.patch` writes
      them, and blueprint line 1083 makes it part of the feature — "every change
      writes a `FIELD_CHANGED` history row with the old and new value like any
      other field". Without this the History tab stayed empty after an inline
      edit against the mock, so a client could be built and demoed against a
      screen production does not produce. Same class of gap C-020's note above
      records on the priority handler, fixed the same way.

      `!== undefined` rather than truthiness: `null` is how a field is *cleared*
      and is the most interesting change of the four to record.

      Only the fields the request actually names are considered, and a field
      named with the value it already holds writes nothing — the server compares
      before it writes for the reason its own commit gives, that a history
      recording `screenName Login → Login` is noise in the one place noise is
      most expensive.
    */
    for (const [field, next] of Object.entries(body)) {
      if (next === undefined) continue;
      const previous = (t as unknown as Record<string, unknown>)[field] ?? null;
      if ((previous ?? null) === (next ?? null)) continue;
      db.history.push({
        id: nextId(db, 'history'), ticketId: t.ticketId, action: 'FIELD_CHANGED',
        actorId: db.currentUserId, actorType: 'USER',
        fieldName: field, oldValue: previous == null ? null : String(previous),
        newValue: next == null ? null : String(next),
        note: null, stageCode: t.currentStageCode, cycleNo: t.cycleNo, iterationNo: t.iterationNo,
        isCorrection: false, correctsEntryId: null,
        entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: new Date().toISOString(),
      });
    }

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

  /**
   * C-020 · §4B.1's level change.
   *
   * ⚠ **For Stream D — two things this handler did not do, both of which made
   * the mock disagree with `PriorityChangeService` in ways a client could be
   * written against.** Same class of gap C-028 found in the delete handler and
   * C-029 in the comment POST, fixed the same way and flagged rather than done
   * quietly, because `frontend/src/mocks/` is yours.
   *
   * 1. **The planned close date was not recomputed.** §4B.1's whole second half
   *    is "resolves the SLA policy → recomputes the Planned Close Date", the
   *    dialog previews exactly that number before committing, and the server
   *    writes it. Without this the mock moved the chip and left the date beside
   *    it stale, so `npm run dev` showed a screen production never produces —
   *    and the preview the user had just read would be contradicted by the panel
   *    two rows below it the moment they saved.
   *
   * 2. **Re-picking the current level was recorded as a change.** The server
   *    treats it as a no-op precisely because a `HIGH → HIGH` row cannot be
   *    deleted once written; the mock appended one, so a misclick against the
   *    mock produced history the real one refuses.
   */
  http.patch(url('/tickets/:ticketId/priority'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const { level, reason } = (await request.json()) as { level: Ticket['level']; reason?: string };

    // No-op before anything else, including the reason check — re-picking the
    // selected chip must not be refused for want of a reason for a change that
    // is not happening.
    if (level === t.level) return ok(ticketDto(t, db));

    if (t.assigneeId && !reason) {
      return validationFailed({ reason: ['is mandatory once the ticket is assigned'] });
    }
    const old = t.level;
    t.level = level;
    // originalLevel is never overwritten — it is what makes "born critical vs
    // became critical" reportable, and it is the first thing a manager asks.

    // Measured from the current cycle's start, falling back to when the ticket
    // was raised — NOT from now. An SLA is "resolve within N hours of being
    // reported", so escalating an old ticket to a short level really does land
    // in the past and really does mean it is breached. `PriorityChangeService`
    // and `levelChange.ts` both compute the same instant the same way.
    const clockStart =
      db.cycles.find((c) => c.ticketId === t.ticketId && c.cycleNo === t.cycleNo)?.startedAt ?? t.createdAt;
    t.plannedCloseDate = plannedCloseDateFor(
      { projectId: t.projectId, taskTypeId: t.taskTypeId, level, assigneeId: t.assigneeId, from: clockStart },
      db,
    ).plannedCloseDate;

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
          // C-034 · the merged stream carries the same internal/client-visible
          // fact the Comments tab shows, so a reader of the History tab alone
          // isn't left guessing which of these were internal.
          isClientVisible: c.isClientVisible,
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
    // C-040: matches CloseService — workflow_transitions seeds RESOLVED ->
    // CLOSED and no other row into CLOSED (G-3), so an IN_PROGRESS or
    // already-CLOSED ticket is refused the same way the real backend refuses
    // it, not only the already-closed half of that rule.
    if (t.status !== 'RESOLVED') {
      return unprocessable(
        t.status === 'CLOSED' ? 'Ticket is already closed' : `Ticket is ${t.status}, not RESOLVED`,
      );
    }
    const body = (await request.json()) as {
      resolutionSummary?: string; actualCloseDate?: string;
      rootCauseCategory?: string; finalEffortHours?: number; requestClientVerification?: boolean;
    };
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
    db.history.push({
      id: nextId(db, 'history'), ticketId: t.ticketId, action: 'CLOSED',
      actorId: db.currentUserId, actorType: 'USER',
      fieldName: 'status', oldValue: 'RESOLVED', newValue: 'CLOSED',
      note: body.resolutionSummary, stageCode: t.currentStageCode,
      cycleNo: t.cycleNo, iterationNo: t.iterationNo,
      isCorrection: false, correctsEntryId: null,
      entryHash: `sha256:${nextId(db, 'hash').toString(16)}`, createdAt: now,
    });
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
      body: string; isClientVisible?: boolean;
      mentionUserIds?: number[]; attachmentIds?: number[];
    };
    // C-029 · refused rather than accepted and ignored, mirroring
    // CommentService. §4B.5 does let a comment carry files and the server does
    // not implement it yet — a mock that answered 201 would let a client be
    // written against a promise production does not keep, which is exactly the
    // failure C-028 found in this file's own delete handler.
    if (body.attachmentIds?.length) {
      return validationFailed({
        attachmentIds: ['Files cannot be attached to a comment yet.'],
      });
    }
    if (!body.body?.trim()) return validationFailed({ body: ['must not be blank'] });
    // PLAN.md §3.9 runs on the server before anything is stored, so a body of
    // pure markup is a 400 there and has to be one here too. `sanitizeRichText`
    // is the client's copy of the same allow-list — not the same code as the
    // server's jsoup Safelist, but the same fourteen tags, which is what makes
    // this a faithful stand-in rather than a second opinion.
    const clean = sanitizeRichText(body.body);
    if (isRichTextEmpty(clean)) {
      return validationFailed({
        body: ['A comment needs some text. Formatting on its own is not enough.'],
      });
    }
    const c = {
      id: nextId(db, 'comment'), ticketId: t.ticketId, body: clean, originalBody: null,
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

  // C-033 · both handlers predate the server and both were looser than it.
  //
  // The PATCH found its row by comment id ALONE, so
  // `/tickets/{one-I-can-see}/comments/{one-I-cannot}` edited a comment on a
  // ticket the caller has no scope for; it never sanitised, so the mock stored
  // markup the real server strips; and it never stamped `editedAt`. The DELETE
  // had no permission check of any kind — anybody could tombstone anybody's
  // comment — no tombstone fields, and the same cross-ticket hole. That is the
  // C-028 situation exactly: a client written against a mock that always says
  // yes has no reason to handle the refusal, and the first person to meet one
  // is a user.
  http.patch(url('/tickets/:ticketId/comments/:commentId'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const c = db.comments.find((x) => x.id === Number(params.commentId) && x.ticketId === t.ticketId);
    if (!c) return notFound('Comment');
    if (c.isDeleted) {
      return unprocessable('This comment has been removed, so there is nothing left to edit');
    }
    // Author only — no role widens this, PM and Admin included. §4B.5: "no
    // role, including Admin, can silently rewrite a comment". The mirror of
    // `CommentService.edit`, and deliberately NOT the same rule as the delete
    // below, which does widen.
    if (c.authorId !== db.currentUserId) {
      return unprocessable('Only the author may edit a comment');
    }
    // D-14 · no time limit. §4B.5's five minutes were lifted, so there is no
    // window check here and the mock has none to mirror. The author half of the
    // rule above is what §4B.5 was actually protecting and it stays.
    //
    // The server keeps the enforcement behind `edutrack.comments.edit-window`
    // for whoever wants the blueprint back; this file has no equivalent, so a
    // deployment that restores the window is one the mock stops standing in for.
    // Said here rather than silently: it is the one place the two now differ.
    const body = (await request.json()) as { body: string };
    // §3.9 runs on every write path, an edit included — a mock that sanitised
    // on POST and not on PATCH would let the editor be used to store exactly
    // what the POST refuses.
    const clean = sanitizeRichText(body.body ?? '');
    if (isRichTextEmpty(clean)) {
      return validationFailed({
        body: ['A comment needs some text. Formatting on its own is not enough.'],
      });
    }
    // First edit only. A second one overwriting this would leave the first
    // revision standing as the "original" and lose the actual one, which is the
    // worst outcome available: the record looks intact and is wrong.
    c.originalBody = c.originalBody ?? c.body;
    c.body = clean;
    c.isEdited = true;
    c.editedAt = new Date().toISOString();
    return ok(commentDto(c));
  }),

  http.delete(url('/tickets/:ticketId/comments/:commentId'), ({ params }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const c = db.comments.find((x) => x.id === Number(params.commentId) && x.ticketId === t.ticketId);
    if (!c) return notFound('Comment');
    // Idempotent, like the server: the client does not remove the row
    // optimistically, and a retry after a dropped response is ordinary.
    // Re-stamping would also let a second caller take the first one's name off
    // the record.
    if (c.isDeleted) return noContent();
    // The author, a PM or an Admin — C-028's widening, mirrored. 403 rather
    // than 404 because the caller is looking at the comment in a thread they
    // just fetched; a picker that has only ever seen 204 has no reason to
    // handle a refusal.
    const me = db.users.find((u) => u.id === db.currentUserId);
    const mayDelete = c.authorId === db.currentUserId || me?.role === 'ADMIN' || me?.role === 'PM';
    if (!mayDelete) {
      return problem(403, 'comment-delete-not-permitted', 'That comment is not yours to remove', {
        detail:
          'Only the person who wrote a comment, a project manager or an administrator can remove it. '
          + 'Whoever removes it, the thread keeps a note that it was here.',
      });
    }
    // Tombstone — the row survives, and BOTH copies of the text go. Clearing
    // only `body` would leave a comment that was edited before it was deleted
    // serving its first wording through `originalBody`, which is usually the
    // exact text the deletion was for.
    c.isDeleted = true;
    c.body = '';
    c.originalBody = null;
    c.deletedById = db.currentUserId;
    c.deletedAt = new Date().toISOString();
    return noContent();
  }),

  // ── attachments ───────────────────────────────────────────────────────────
  http.get(url('/tickets/:ticketId/attachments'), ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const q = new URL(request.url).searchParams;
    // C-028 · a removed row is no longer uniformly hidden. §4B.4 wants a
    // tombstone for the removals worth recording, and the mock has to reproduce
    // which ones those are — a mock that hid every deleted row would let the
    // gallery's tombstone rendering pass `npm run dev` without ever appearing.
    let rows = db.attachments.filter((a) => a.ticketId === t.ticketId && (!a.isDeleted || isVisibleTombstone(a)));
    if (q.get('cycle')) rows = rows.filter((a) => a.cycleNo === Number(q.get('cycle')));
    // Applied to tombstones exactly as to live rows: "debug-log.txt was removed"
    // names the internal file as surely as serving it would.
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

    /*
     * C-027 · all three of §4B.4's caps, read from `db.attachmentLimits`.
     *
     * Until this task the mock enforced the per-file cap and nothing else, with
     * the number written inline — so the 50 MB and 20-file rules existed only on
     * the server and only in the picker, and nothing under `npm run dev` or in a
     * test could ever exercise the two 413s the client has messages for. C-023's
     * note flagged it at the time.
     *
     * The order matches `AttachmentService.enforceLimits` and it is not
     * arbitrary: per-file first, so an oversized file is reported as oversized
     * rather than as "would exceed the ticket budget", which would send the user
     * off to delete other attachments to make room for a file that was never
     * going to fit on its own.
     */
    const limits = db.attachmentLimits;
    if (file.size > limits.maxFileBytes) {
      return problem(413, 'attachment-too-large',
        `${mockBytes(file.size)} exceeds the ${mockBytes(limits.maxFileBytes)} limit for one file.`);
    }
    const live = db.attachments.filter((x) => x.ticketId === t.ticketId && !x.isDeleted);
    if (live.length + 1 > limits.maxFiles) {
      return problem(413, 'attachment-too-large',
        `A ticket may hold ${limits.maxFiles} attachments. Remove one before adding another.`);
    }
    const used = live.reduce((sum, x) => sum + x.sizeBytes, 0);
    if (used + file.size > limits.maxTicketBytes) {
      return problem(413, 'attachment-too-large',
        `This ticket holds ${mockBytes(used)} of attachments and this file is ${mockBytes(file.size)}, `
        + `which would take it past its ${mockBytes(limits.maxTicketBytes)} total. Remove an attachment first.`);
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

  /*
   * C-027 · §4B.4's caps, read by every upload surface.
   *
   * Outside `/tickets` because the limits are org-wide and identical for every
   * ticket — see the contract. Kept in this file anyway, next to the routes that
   * enforce them, so the read and the enforcement cannot drift apart unnoticed.
   */
  http.get(url('/attachments/limits'), () => {
    const db = getDb();
    return ok(db.attachmentLimits, undefined, { headers: { ETag: limitsEtag(db) } });
  }),

  http.put(url('/attachments/limits'), async ({ request }) => {
    const db = getDb();

    // The mock enforces `If-Match` too. A guard the real backend has and the
    // mock waves through is a guard the frontend never gets to exercise — and
    // here the loser of a lost update is erased rather than merged, because a
    // PUT replaces all three caps at once.
    const ifMatch = request.headers.get('If-Match');
    if (!ifMatch) {
      return problem(428, 'precondition-required',
        'If-Match is required. GET the limits first and send back their ETag.');
    }
    if (ifMatch !== '*' && ifMatch.replace(/W\/|"/g, '') !== limitsEtag(db).replace(/"/g, '')) {
      return problem(412, 'precondition-failed',
        'The limits changed since you read them. Reload and reapply your edit.');
    }

    const body = (await request.json()) as Partial<typeof db.attachmentLimits>;
    const { maxFileBytes, maxTicketBytes, maxFiles } = body;

    if (![maxFileBytes, maxTicketBytes, maxFiles].every((v) => typeof v === 'number' && v > 0)) {
      return validationFailed({
        maxFileBytes: ['must be a positive number'],
        maxTicketBytes: ['must be a positive number'],
        maxFiles: ['must be a positive number'],
      });
    }
    // The two 422s the server has, mirrored — a mock that accepted a
    // combination the server refuses would let the settings form be built
    // against a validation rule that does not exist.
    if (maxFileBytes! > db.attachmentLimits.ceilingBytes) {
      return problem(422, 'invalid-attachment-limits', 'Those limits cannot be applied', {
        detail: `This server accepts at most ${mockBytes(db.attachmentLimits.ceilingBytes)} in one upload, `
          + `so a per-file limit of ${mockBytes(maxFileBytes!)} could not take effect.`,
      });
    }
    if (maxTicketBytes! < maxFileBytes!) {
      return problem(422, 'invalid-attachment-limits', 'Those limits cannot be applied', {
        detail: `maxTicketBytes (${mockBytes(maxTicketBytes!)}) must be at least maxFileBytes `
          + `(${mockBytes(maxFileBytes!)}), or no file that size could ever be attached.`,
      });
    }

    db.attachmentLimits = { ...db.attachmentLimits, maxFileBytes: maxFileBytes!, maxTicketBytes: maxTicketBytes!, maxFiles: maxFiles! };
    return ok(db.attachmentLimits, undefined, { headers: { ETag: limitsEtag(db) } });
  }),

  /**
   * C-028 · §4B.4's deletion rule.
   *
   * Previously this set `isDeleted` for anybody who asked, with no window, no
   * uploader check and no tombstone — which meant the client could not be
   * developed against the behaviour it has to render. All three are here now,
   * and the 403 in particular: a picker that only ever saw 204 would have no
   * reason to handle the refusal, and the first person to meet one would be a
   * user rather than a test.
   */
  http.delete(url('/tickets/:ticketId/attachments/:attachmentId'), ({ params }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    const a = db.attachments.find((x) => x.id === Number(params.attachmentId));
    // Checked against the path's ticket, not just by id — otherwise
    // /tickets/9/attachments/41 answers differently depending on whose 41 it is,
    // which enumerates attachment ids across tickets.
    if (!a || a.ticketId !== t.ticketId) return notFound('Attachment');
    // Idempotent: the caller asked for the file to be gone and it is gone.
    if (a.isDeleted) return noContent();

    const me = currentUser(db);
    const mayRemove = a.uploadedById === me.id || me.role === 'PM' || me.role === 'ADMIN';
    if (!mayRemove) {
      return problem(403, 'attachment-delete-refused', 'That attachment cannot be removed', {
        detail: withinDeleteWindow(a)
          ? 'Only the person who attached this file can remove it in the first few minutes. '
            + 'After that a project manager or an administrator can remove it, leaving a note that it was here.'
          : 'The window for removing this file has passed. A project manager or an administrator '
            + 'can still remove it, and the ticket will show that it was here.',
      });
    }

    a.isDeleted = true;
    a.deletedById = me.id;
    a.deletedAt = new Date().toISOString();
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

  /**
   * D-055 · Ask Status.
   *
   * Mirrors the three server behaviours a UI would otherwise learn wrong:
   * **422 when nobody is assigned** (there is no one to ask), **idempotent
   * repeat clicks** while the caller's own request is unanswered, and a `202`
   * carrying the request so the caller can render the badge without refetching.
   *
   * It does not mirror the "Reporting Manager, PM or Admin only" rule: the mock
   * has no notion of who reports to whom, and a guess at it here would either
   * block the walkthrough user or teach the UI that anyone may ask. The server
   * answers 404 for a caller who may not, which the client already handles.
   */
  http.post(url('/tickets/:ticketId/ask-status'), async ({ params, request }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    if (t.assigneeId == null) {
      return unprocessable('This ticket has no assignee yet, so there is nobody to ask.');
    }
    if (t.assigneeId === db.currentUserId) {
      return unprocessable('You are the assignee on this ticket.');
    }

    const open = db.statusRequests.find(
      (r) => r.ticketId === t.ticketId && r.requestedById === db.currentUserId && r.answeredAt === null,
    );
    if (open) return ok(statusRequestDto(open, db), undefined, { status: 202 });

    let thread = db.chatThreads.find((x) => x.ticketId === t.ticketId);
    if (!thread) {
      thread = {
        id: nextId(db, 'thread'), kind: 'TICKET', title: t.ticketId, ticketId: t.ticketId,
        participantIds: [db.currentUserId, t.assigneeId], lastMessageAt: null,
      };
      db.chatThreads.push(thread);
    }
    for (const id of [db.currentUserId, t.assigneeId]) {
      if (!thread.participantIds.includes(id)) thread.participantIds.push(id);
    }

    const body = ((await request.json().catch(() => null)) as { note?: string } | null)?.note?.trim();
    const now = new Date().toISOString();
    const message = {
      id: nextId(db, 'message'), threadId: thread.id,
      body: body || 'Please share the current status and expected closure.',
      authorId: db.currentUserId, kind: 'STATUS_REQUEST' as const,
      isEdited: false, isDeleted: false, readBy: [db.currentUserId], createdAt: now,
    };
    db.chatMessages.push(message);
    thread.lastMessageAt = now;

    const created = {
      id: nextId(db, 'statusRequest'), ticketId: t.ticketId, ticketTitle: t.title,
      threadId: thread.id, requestMessageId: message.id,
      requestedById: db.currentUserId, askedOfId: t.assigneeId, requestedAt: now,
      answerMessageId: null, answeredAt: null, responseWorkingMinutes: null,
    };
    db.statusRequests.push(created);
    return ok(statusRequestDto(created, db), undefined, { status: 202 });
  }),

  /** D-056 · the badge — what is still outstanding on this ticket. */
  http.get(url('/tickets/:ticketId/status-requests'), ({ params }) => {
    const db = getDb();
    const t = findTicket(String(params.ticketId), db);
    if (!t) return notFound('Ticket');
    return ok(
      db.statusRequests
        .filter((r) => r.ticketId === t.ticketId && r.answeredAt === null)
        .sort((a, b) => a.requestedAt.localeCompare(b.requestedAt))
        .map((r) => statusRequestDto(r, db)),
    );
  }),
];

// ── shared mappers ──────────────────────────────────────────────────────────
export const round = (n: number) => Math.round(n * 10) / 10;

/**
 * D-055 / D-056 · one status request on the wire.
 *
 * `note` is read from the request message and withheld once it is deleted, the
 * same as the server does — a mock that kept its own copy would keep showing a
 * note the real API has stopped returning, and §7.6's tombstone would appear to
 * leak only in production.
 */
export function statusRequestDto(
  r: import('../db').StatusRequest,
  db: import('../db').Db,
) {
  const message = db.chatMessages.find((m) => m.id === r.requestMessageId);
  return {
    id: r.id,
    ticketId: r.ticketId,
    ticketTitle: r.ticketTitle,
    threadId: r.threadId,
    requestMessageId: r.requestMessageId,
    requestedBy: userRef(r.requestedById, db),
    askedOf: userRef(r.askedOfId, db),
    requestedAt: r.requestedAt,
    note: !message || message.isDeleted ? null : message.body,
    isAnswered: r.answeredAt !== null,
    answerMessageId: r.answerMessageId,
    answeredAt: r.answeredAt,
    responseWorkingMinutes: r.responseWorkingMinutes,
  };
}

// ── links · C-064 ────────────────────────────────────────────────────────────

/**
 * The four names blueprint §7.5's create-form row and `createTicketLink`
 * accept. `DUPLICATED_BY` is a fifth value in the wire enum that never
 * reaches here — see the handler.
 */
const SUBMITTABLE_LINK_TYPES = ['BLOCKS', 'BLOCKED_BY', 'DUPLICATE_OF', 'RELATES_TO'] as const;

/** How a stored type reads from the *other* ticket. Mirrors `TicketLinkType.inverse()`. */
function inverseLinkType(type: string): string {
  switch (type) {
    case 'BLOCKS': return 'BLOCKED_BY';
    case 'BLOCKED_BY': return 'BLOCKS';
    case 'DUPLICATE_OF': return 'DUPLICATED_BY';
    case 'DUPLICATED_BY': return 'DUPLICATE_OF';
    default: return type; // RELATES_TO — symmetric
  }
}

/**
 * One row per relationship. Mirrors `TicketLinkService.canonicalize` on the
 * real backend: `BLOCKED_BY` is rewritten to the `BLOCKS` row the other
 * ticket would have written, source and target swapped, and `RELATES_TO` is
 * ordered by ticket code so either direction of submission lands on the
 * same row. `DUPLICATE_OF` is genuinely directional and stored as submitted.
 */
function canonicalizeLink(type: string, sourceId: string, targetId: string) {
  if (type === 'BLOCKED_BY') return { linkType: 'BLOCKS', sourceId: targetId, targetId: sourceId };
  if (type === 'RELATES_TO') {
    return sourceId <= targetId
      ? { linkType: 'RELATES_TO', sourceId, targetId }
      : { linkType: 'RELATES_TO', sourceId: targetId, targetId: sourceId };
  }
  return { linkType: type, sourceId, targetId }; // BLOCKS, DUPLICATE_OF
}

/** The contract's `LinkedTicket`, as it reads from `fromTicketId`'s own side. */
function linkedTicketDto(link: import('../db').TicketLink, fromTicketId: string, db: import('../db').Db) {
  const otherId = link.sourceTicketId === fromTicketId ? link.targetTicketId : link.sourceTicketId;
  // Scoped — a caller must not learn the title or level of a ticket outside
  // their own scope just because it happens to be linked to one they can see.
  const other = findTicket(otherId, db);
  const linkType = link.sourceTicketId === fromTicketId ? link.linkType : inverseLinkType(link.linkType);
  return {
    id: link.id,
    linkType,
    ticket: other && { ticketId: other.ticketId, title: other.title, level: other.level, status: other.status },
    createdAt: link.createdAt,
    createdBy: userRef(link.createdById, db),
  };
}

/** Every link touching `ticketId`, for `TicketDetailResponse.linkedTickets`. */
function ticketLinksFor(ticketId: string, db: import('../db').Db) {
  return db.ticketLinks
    .filter((l) => l.sourceTicketId === ticketId || l.targetTicketId === ticketId)
    .map((l) => linkedTicketDto(l, ticketId, db))
    // A link to a ticket outside this caller's scope is dropped rather than
    // shown with a broken reference — `linkedTicketDto`'s `ticket` comes back
    // undefined for it, same "absence, not refusal" contract `findTicket`
    // itself keeps.
    .filter((view) => view.ticket != null)
    .sort((a, b) => a.id - b.id);
}

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
  // C-033 · a tombstone carries no text in either field, no deadline, and does
  // carry its actor. A deliberate mirror of `CommentDto.of`; the two are meant
  // to be read side by side, the way `isVisibleTombstone` mirrors the server's.
  //
  // `originalBody` is the one worth spelling out: a comment edited and then
  // deleted would otherwise serve its first wording through the field designed
  // to preserve wording, which is usually the exact text the deletion was for.
  // The delete handler clears it too — this is the second guard, so a row
  // written by a fixture cannot leak either.
  return {
    id: c.id,
    body: c.isDeleted ? '' : c.body,
    originalBody: c.isDeleted ? null : c.originalBody,
    author: userRef(c.authorId, db),
    authorRole: db.users.find((u) => u.id === c.authorId)?.role,
    isClientVisible: c.isClientVisible, isEdited: c.isEdited, isDeleted: c.isDeleted,
    // D-14 · always null, because there is no deadline. NOT "cannot edit" —
    // `CommentDto.of` sends the same null for the same reason, and
    // `canEditComment` reads it that way. A mock that kept inventing a
    // five-minute deadline would put a countdown on screen and then hide the
    // Edit button, describing a rule neither side enforces any more.
    editableUntil: null,
    editedAt: c.editedAt ?? null,
    deletedBy: c.isDeleted && c.deletedById != null ? userRef(c.deletedById, db) : null,
    deletedAt: c.isDeleted ? (c.deletedAt ?? null) : null,
    stageCode: c.stageCode, cycleNo: c.cycleNo, iterationNo: c.iterationNo,
    mentions: c.mentionIds.map((id) => userRef(id, db)),
    attachments: [], createdAt: c.createdAt,
  };
}

/**
 * C-027 · content-derived, like the real controller's, and over the three caps
 * only.
 *
 * `ceilingBytes` is deliberately excluded: it is the server's own multipart
 * configuration rather than part of the resource, so including it would make
 * two differently-configured nodes hand out tags that disagree and fail a
 * precondition nobody violated.
 */
/**
 * C-017 · the three bulk actions are **PM and Admin only, refused here**.
 *
 * The grid draws no selection column for the other four roles, but that is a
 * courtesy and not the rule — a request built past the DOM has to be refused,
 * or the permission is decoration. Same reason `commentPermissions.ts` says its
 * functions decide what is *shown* and never what is *allowed*.
 *
 * `403` rather than `404` because the refusal is about the *capability*, not
 * about any row: no ticket id has been examined at this point, so nothing about
 * which tickets exist has leaked. Out-of-scope ids are handled further in, where
 * `findTicket` narrows through `scopedTickets` and the result reads
 * `Not found or out of scope` per ticket — that one stays indistinguishable
 * from a ticket that was never there.
 */
function refuseUnlessPmOrAdmin(db: import('../db').Db) {
  const role = currentUser(db).role;
  if (role === 'ADMIN' || role === 'PM') return null;
  return problem(403, 'forbidden', 'Bulk ticket actions are restricted to PM and Admin');
}

/** The `BulkResultResponse` envelope, counted from the per-ticket outcomes. */
function bulkResult(results: { ticketId: string; ok: boolean; reason: string | null }[]) {
  return ok({
    succeeded: results.filter((r) => r.ok).length,
    failed: results.filter((r) => !r.ok).length,
    results,
  });
}

const limitsEtag = (db: import('../db').Db) => {
  const { maxFileBytes, maxTicketBytes, maxFiles } = db.attachmentLimits;
  return `"${Math.abs([...JSON.stringify({ maxFileBytes, maxTicketBytes, maxFiles })]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)).toString(16)}"`;
};

/**
 * C-027 · binary units with decimal labels — the one spelling this product uses.
 *
 * Deliberately duplicated from `formatFileSize` in `components/ui/attachments.ts`
 * rather than imported: `mocks/` stands in for the server, and importing the
 * client's formatter would make the mock's messages agree with the client's by
 * construction instead of by both matching the real `AttachmentLimits.Bytes`.
 */
function mockBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1; }
  return `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10} ${units[unit]}`;
}

export function attachmentDto(a: import('../db').Attachment) {
  return {
    id: a.id, fileName: a.fileName, contentType: a.contentType, sizeBytes: a.sizeBytes,
    scanStatus: a.scanStatus,
    // Only downloadable once CLEAN, and always via a short-lived signed URL.
    downloadUrl: a.scanStatus === 'CLEAN' ? `/mock-files/${a.id}/${a.fileName}?sig=mock` : null,
    // C-026 · a thumbnail is NOT simply "any image that scanned clean".
    //
    // The server reduces PNG, JPEG and GIF and nothing else — the JVM ships no
    // WebP reader — so a CLEAN WebP comes back with `thumbnailUrl: null` and the
    // client falls back to the full file. That is the single easiest thing for a
    // UI to get wrong, because reading the null as "not an image" produces a
    // grey file icon for a perfectly good screenshot with nothing failing
    // anywhere. It has to be reachable under `npm run dev`, or the fallback path
    // is only ever exercised by a unit test.
    thumbnailUrl:
      a.scanStatus === 'CLEAN' && ['image/png', 'image/jpeg', 'image/gif'].includes(a.contentType)
        ? `/mock-files/${a.id}/thumb.png?sig=mock`
        : null,
    isClientVisible: a.isClientVisible, isDeleted: a.isDeleted,
    uploadedBy: userRef(a.uploadedById), stageCode: a.stageCode,
    cycleNo: a.cycleNo, createdAt: a.createdAt,
    // C-028 · the two halves of "file removed by X on date". Null on a live row,
    // and `deletedBy` is additionally null when the account no longer exists —
    // userRef already answers null for an unknown id, so the client's
    // no-actor branch is reachable here rather than only in a unit test.
    deletedBy: a.isDeleted ? userRef(a.deletedById ?? null) : null,
    deletedAt: a.isDeleted ? (a.deletedAt ?? null) : null,
  };
}

/**
 * C-028 · §4B.4's fifteen minutes, from upload.
 *
 * Hard-coded rather than read from `/attachments/limits`: the window is not one
 * of the three caps C-027 made configurable, and the server keeps it in
 * `application.yml` for the same reason — an operator who could set it to a year
 * could make the tombstone unreachable.
 */
const DELETE_WINDOW_MS = 15 * 60 * 1000;

function withinDeleteWindow(a: Attachment): boolean {
  return Date.now() - new Date(a.createdAt).getTime() <= DELETE_WINDOW_MS;
}

/**
 * C-028 · whether a removed row still says so on the ticket — the mock's copy of
 * `AttachmentService.isVisibleTombstone`, and deliberately the same shape.
 *
 * The uploader fixing their own mis-paste promptly leaves nothing behind;
 * everyone else, and the same person later, leaves a mark. The uploader
 * comparison is the half that matters: a clock-only rule would hide a PM's
 * supervisory removal at minute three, which is exactly the deletion the ticket
 * most needs to record.
 */
function isVisibleTombstone(a: Attachment): boolean {
  if (!a.isDeleted) return false;
  if (!a.deletedAt) return true;
  if (a.deletedById == null || a.deletedById !== a.uploadedById) return true;
  return new Date(a.deletedAt).getTime() - new Date(a.createdAt).getTime() > DELETE_WINDOW_MS;
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
