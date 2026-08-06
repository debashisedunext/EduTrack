import { http, HttpResponse } from 'msw';
import { getDb, nextId } from '../db';
import { round } from './tickets';
import {
  clientRef, currentUser, noContent, notFound, ok, paginate, problem,
  scopedTickets, ticketDto, url, userRef, validationFailed,
} from './util';

/** Everything outside tickets and the ribbon: auth, masters, clients, the rest. */

const me = () => {
  const db = getDb();
  const u = currentUser(db);
  return {
    id: u.id, displayName: u.displayName, avatarUrl: u.avatarUrl, role: u.role,
    username: u.username, email: u.email,
    permissions: PERMISSIONS[u.role],
    projectIds: u.projectIds,
    reporteeIds: db.users.filter((x) => x.reportingManagerId === u.id).map((x) => x.id),
    timezone: u.timezone,
  };
};

const PERMISSIONS: Record<string, string[]> = {
  ADMIN: ['ticket.read', 'ticket.write', 'ticket.assign', 'master.write', 'report.read', 'audit.read'],
  PM: ['ticket.read', 'ticket.write', 'ticket.assign', 'master.write', 'report.read'],
  DEVELOPER: ['ticket.read', 'ticket.write'],
  QA: ['ticket.read', 'ticket.write'],
  DEPLOYMENT: ['ticket.read', 'ticket.write'],
  SUPPORT: ['ticket.read', 'ticket.write'],
};

const LANDING: Record<string, string> = {
  ADMIN: '/dashboard', PM: '/dashboard', DEVELOPER: '/my-tasks',
  SUPPORT: '/tickets', QA: '/stages/queue', DEPLOYMENT: '/stages/queue',
};

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

  // ── users ─────────────────────────────────────────────────────────────────
  http.get(url('/users'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url).searchParams;
    let rows = db.users;
    const text = q.get('q')?.toLowerCase();
    if (text) {
      rows = rows.filter((u) =>
        [u.displayName, u.username, u.email, u.employeeCode].some((f) => f.toLowerCase().includes(text)),
      );
    }
    if (q.get('role')) rows = rows.filter((u) => u.role === q.get('role'));
    if (q.get('isActive')) rows = rows.filter((u) => String(u.isActive) === q.get('isActive'));
    if (q.get('projectId')) rows = rows.filter((u) => u.projectIds.includes(Number(q.get('projectId'))));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map(userDto), meta);
  }),
  http.post(url('/users'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, string>;
    if (db.users.some((u) => u.username === body.username)) {
      return problem(409, 'duplicate', 'That username is already taken');
    }
    const u = {
      id: db.users.length + 1, displayName: body.displayName, username: body.username,
      email: body.email, role: body.role as never, employeeCode: body.employeeCode ?? '',
      avatarUrl: null, reportingManagerId: null, projectIds: [], isActive: true,
      timezone: body.timezone ?? 'Asia/Kolkata',
    };
    db.users.push(u);
    return ok(userDto(u), undefined, { status: 201 });
  }),
  http.patch(url('/users/:userId'), async ({ params, request }) => {
    const db = getDb();
    const u = db.users.find((x) => x.id === Number(params.userId));
    if (!u) return notFound('User');
    const body = (await request.json()) as Record<string, unknown>;
    // A→B→C→A is as broken as A→A, and the database CHECK only catches the latter.
    if (body.reportingManagerId != null && createsCycle(u.id, Number(body.reportingManagerId))) {
      return problem(409, 'manager-cycle', 'That would create a reporting cycle');
    }
    Object.assign(u, body);
    return ok(userDto(u));
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

  // ── projects ──────────────────────────────────────────────────────────────
  http.get(url('/projects'), ({ request }) => {
    const { page, meta } = paginate(getDb().projects, new URL(request.url));
    return ok(page.map(projectDto), meta);
  }),
  http.post(url('/projects'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Record<string, string>;
    if (db.projects.some((p) => p.projectCode === body.projectCode)) {
      return problem(409, 'duplicate', 'That project code is already in use');
    }
    const p = {
      id: db.projects.length + 1, projectCode: body.projectCode, name: body.name,
      projectManagerId: Number(body.projectManagerId ?? 2),
      colourTag: body.colourTag ?? '#4F46E5', isActive: true, ticketSeq: 0,
    };
    db.projects.push(p);
    return ok(projectDto(p), undefined, { status: 201 });
  }),
  http.patch(url('/projects/:projectId'), async ({ params, request }) => {
    const db = getDb();
    const p = db.projects.find((x) => x.id === Number(params.projectId));
    if (!p) return notFound('Project');
    const body = (await request.json()) as Record<string, unknown>;
    // Immutable once a ticket exists — it is the prefix of every ID already
    // issued, and changing it orphans every reference in mail, chat and history.
    if (body.projectCode && body.projectCode !== p.projectCode &&
        db.tickets.some((t) => t.projectId === p.id)) {
      return problem(409, 'immutable-project-code',
        'Project code cannot change once tickets exist on the project');
    }
    Object.assign(p, body);
    return ok(projectDto(p));
  }),
  http.post(url('/projects/:projectId/members'), () => new HttpResponse(null, { status: 201 })),
  http.delete(url('/projects/:projectId/members/:userId'), () => noContent()),
  http.get(url('/projects/:projectId/sla-policies'), () =>
    ok(getDb().taskTypes.flatMap((tt) =>
      (['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const).map((level) => ({
        taskTypeId: tt.id, level,
        responseHrs: tt.defaultSlaHrs / 4, resolutionHrs: tt.defaultSlaHrs,
        l1EscalationUserId: 2, l2EscalationUserId: 1,
      })),
    )),
  ),
  http.put(url('/projects/:projectId/sla-policies'), async ({ request }) =>
    ok(await request.json()),
  ),

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

  // ── masters ───────────────────────────────────────────────────────────────
  http.get(url('/masters/task-types'), () => ok(getDb().taskTypes)),
  http.get(url('/masters/priorities'), () =>
    ok([
      { id: 1, level: 'LOW', colour: '#84CC16', defaultSlaHrs: 120, autoEscalates: false },
      { id: 2, level: 'MEDIUM', colour: '#F59E0B', defaultSlaHrs: 48, autoEscalates: false },
      { id: 3, level: 'HIGH', colour: '#9A3412', defaultSlaHrs: 16, autoEscalates: true },
      { id: 4, level: 'CRITICAL', colour: '#BE185D', defaultSlaHrs: 4, autoEscalates: true },
    ]),
  ),
  http.get(url('/masters/holidays'), () =>
    ok({
      holidays: [
        { date: '2026-08-15', name: 'Independence Day' },
        { date: '2026-10-02', name: 'Gandhi Jayanti' },
        { date: '2026-11-08', name: 'Diwali' },
      ],
      weeklyOff: [0, 6], workDayStart: '09:30', workDayEnd: '18:30',
    }),
  ),
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
  http.patch(url('/notifications/:id/read'), ({ params }) => {
    const n = getDb().notifications.find((x) => x.id === Number(params.id));
    if (!n) return notFound('Notification');
    n.isRead = true;
    return noContent();
  }),
  http.patch(url('/notifications/read-all'), () => {
    const db = getDb();
    db.notifications.filter((n) => n.userId === db.currentUserId).forEach((n) => { n.isRead = true; });
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
    return ok(messageDto(m), undefined, { status: 201 });
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

// ── mappers ─────────────────────────────────────────────────────────────────
function userDto(u: import('../db').User) {
  const db = getDb();
  return {
    id: u.id, displayName: u.displayName, avatarUrl: u.avatarUrl, role: u.role,
    username: u.username, email: u.email, employeeCode: u.employeeCode,
    reportingManager: userRef(u.reportingManagerId, db),
    projectIds: u.projectIds, isActive: u.isActive,
    openTicketCount: db.tickets.filter((t) => t.assigneeId === u.id && t.status !== 'CLOSED').length,
    createdAt: '2026-08-03T09:00:00.000Z',
  };
}

function projectDto(p: import('../db').Project) {
  return {
    id: p.id, projectCode: p.projectCode, name: p.name,
    projectManager: userRef(p.projectManagerId), colourTag: p.colourTag,
    startDate: null, endDate: null, isActive: p.isActive, autoAssignRule: 'LEAST_LOADED',
  };
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

function messageDto(m: import('../db').ChatMessage) {
  return {
    id: m.id, body: m.body, author: userRef(m.authorId), kind: m.kind,
    isEdited: m.isEdited, isDeleted: m.isDeleted,
    editableUntil: new Date(Date.parse(m.createdAt) + 5 * 60_000).toISOString(),
    attachments: [], readBy: m.readBy, createdAt: m.createdAt,
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
