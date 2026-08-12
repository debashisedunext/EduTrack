import { describe, expect, it, beforeEach } from 'vitest';
import { getDb, resetDb } from './db';
import { http, ApiError } from '../api/http';
import { getTicketDetailPathTicketIdRegExp } from '../api/generated/zod/tickets/tickets.zod';

/**
 * The mock API is what Streams B and C build against for the next four weeks.
 * If it is wrong they build the wrong thing, and nobody finds out until the
 * real backend lands. So it gets tested like a real service.
 */

const get = <T,>(url: string) => http<T>({ url, method: 'GET' });
const post = <T,>(url: string, data?: unknown) => http<T>({ url, method: 'POST', data });

interface Envelope<T> { data: T; meta?: Record<string, unknown> }

beforeEach(() => resetDb());

/**
 * A-029's endpoints, mocked in D-004's file. Not Stream A's tests — these pin
 * the *mock's* behaviour, so a screen built against it cannot assume something
 * the real server will not do.
 */
describe('two-factor enrolment', () => {
  interface Setup { secret: string; otpauthUri: string }
  interface Confirm { recoveryCodes: string[] }

  it('setup hands back a secret and an otpauth URI, and leaves 2FA off', async () => {
    const setup = await post<Envelope<Setup>>('/me/2fa/setup');

    expect(setup.data.otpauthUri).toContain(`secret=${setup.data.secret}`);
    // The whole point of the two-step design: a QR that never scans must not
    // have locked the user out of the account they were protecting.
    expect(getDb().twoFactor[getDb().currentUserId].enabled).toBe(false);
  });

  it('confirm turns it on and returns recovery codes', async () => {
    await post('/me/2fa/setup');
    const confirmed = await post<Envelope<Confirm>>('/me/2fa/confirm', { code: '123456' });

    expect(confirmed.data.recoveryCodes).toHaveLength(10);
    expect(getDb().twoFactor[getDb().currentUserId].enabled).toBe(true);
  });

  it('refuses to confirm something that was never started', async () => {
    await expect(post('/me/2fa/confirm', { code: '123456' })).rejects.toSatisfy(
      (e: unknown) => e instanceof ApiError && e.status === 409,
    );
  });

  it('refuses to disable without the password', async () => {
    await post('/me/2fa/setup');
    await post('/me/2fa/confirm', { code: '123456' });

    // The password is what this endpoint is for — a stolen access token must
    // not be enough to remove the second factor.
    //
    // 401 rather than 400, which is the handler's call and the right one: the
    // mock holds no passwords, so it cannot tell an absent one from a wrong
    // one, and collapsing both to "re-authentication failed" is the answer a
    // screen has to handle anyway. The contract declares both codes.
    await expect(post('/me/2fa/disable', {})).rejects.toSatisfy(
      (e: unknown) => e instanceof ApiError && e.status === 401,
    );
    expect(getDb().twoFactor[getDb().currentUserId].enabled).toBe(true);
  });

  it('disabling with the password clears the enrolment', async () => {
    await post('/me/2fa/setup');
    await post('/me/2fa/confirm', { code: '123456' });

    await post('/me/2fa/disable', { password: 'correct horse' });

    expect(getDb().twoFactor[getDb().currentUserId]).toBeUndefined();
  });
})

describe('walkthrough A — the fixture Stream C is judged against', () => {
  const T = 'CRM-26-00347';

  it('reconciles to 38.0 h across both cycles', async () => {
    const db = getDb();
    db.currentUserId = 1; // Admin, so nothing is scoped away
    const journey = await get<Envelope<{ allCyclesTotalHrs: number }>>(
      `/tickets/${T}/journey`,
    );
    expect(journey.data.allCyclesTotalHrs).toBe(38.0);
  });

  it('seals cycle 1 at 24.5 h over 2 iterations', async () => {
    getDb().currentUserId = 1;
    const c1 = await get<Envelope<{ cycleTotalHrs: number; rows: { iterationNo: number }[] }>>(
      `/tickets/${T}/journey?cycle=1`,
    );
    expect(c1.data.cycleTotalHrs).toBe(24.5);
    expect(Math.max(...c1.data.rows.map((r) => r.iterationNo))).toBe(2);
  });

  it('attributes every hour to one of exactly 5 named resources', async () => {
    getDb().currentUserId = 1;
    const all = await Promise.all([1, 2].map((c) =>
      get<Envelope<{ perResource: { resource: { displayName: string }; effortHrs: number }[] }>>(
        `/tickets/${T}/journey?cycle=${c}`,
      ),
    ));
    const names = new Set(all.flatMap((j) => j.data.perResource.map((p) => p.resource.displayName)));
    expect(names).toEqual(
      new Set(['Priya Nair', 'Meera Iyer', 'Ravi Kumar', 'Anil Shah', 'Karan Bose']),
    );
  });

  it('keeps cycle 1 effort out of cycle 2 — the join must include cycleNo', async () => {
    getDb().currentUserId = 1;
    const c1 = await get<Envelope<{ cycleTotalHrs: number }>>(`/tickets/${T}/journey?cycle=1`);
    const c2 = await get<Envelope<{ cycleTotalHrs: number }>>(`/tickets/${T}/journey?cycle=2`);
    // Without cycleNo in the join, cycle 1's hours leak into cycle 2 and the
    // two totals sum to more than the grand total. That is the blueprint's own
    // defect, corrected in PLAN.md §3.4.
    expect(c1.data.cycleTotalHrs + c2.data.cycleTotalHrs).toBe(38.0);
    expect(c2.data.cycleTotalHrs).toBe(13.5);
  });

  it('reports idle separately from duration — the insight the tab exists for', async () => {
    getDb().currentUserId = 1;
    const j = await get<Envelope<{ rows: { stageCode: string; durationMins: number | null; effortHrs: number; idleMins: number | null }[] }>>(
      `/tickets/${T}/journey?cycle=1`,
    );
    const dev = j.data.rows.find((r) => r.stageCode === 'DEVELOPMENT')!;
    expect(dev.effortHrs).toBe(9.0);
    // Two days in the stage against nine hours of work — a queue problem, and
    // the number that makes it visible.
    expect(dev.idleMins).toBeGreaterThan(0);
    expect(dev.idleMins).toBe(dev.durationMins! - Math.round(dev.effortHrs * 60));
  });

  it('preserves originalLevel through the auto-escalation', async () => {
    getDb().currentUserId = 1;
    const d = await get<Envelope<{ ticket: { level: string; originalLevel: string } }>>(
      `/tickets/${T}/full`,
    );
    expect(d.data.ticket.level).toBe('CRITICAL');
    expect(d.data.ticket.originalLevel).toBe('HIGH');
  });
});

describe('the ribbon behaves, not just responds', () => {
  const T = 'CRM-26-00347';

  it('rework increments iterationNo and leaves cycleNo alone', async () => {
    const db = getDb();
    db.currentUserId = 2; // Meera, PM
    const t = db.tickets.find((x) => x.ticketId === T)!;
    t.status = 'IN_PROGRESS';
    t.currentStageCode = 'QA';
    t.assigneeId = 4;
    db.transitions.push({
      id: 9001, ticketId: T, cycleNo: 2, iterationNo: 1, stageCode: 'QA',
      ownerId: 4, ownerRole: 'QA', enteredAt: new Date().toISOString(),
      exitedAt: null, durationMins: null, action: 'FORWARD', note: null, skipReason: null,
    });

    const before = { cycle: t.cycleNo, iteration: t.iterationNo };
    await post(`/tickets/${T}/rework`, {
      toStageCode: 'DEVELOPMENT', reason: 'Two defects on the retry path', effortHours: 1,
    });

    expect(t.iterationNo).toBe(before.iteration + 1);
    expect(t.cycleNo).toBe(before.cycle); // the counters are independent
    expect(t.currentStageCode).toBe('DEVELOPMENT');
  });

  it('refuses a backward move the workflow does not allow', async () => {
    const db = getDb();
    db.currentUserId = 2;
    const t = db.tickets.find((x) => x.ticketId === T)!;
    t.status = 'IN_PROGRESS';
    t.currentStageCode = 'TRIAGE'; // may only return to INTAKE

    const err = await post(`/tickets/${T}/rework`, {
      toStageCode: 'DEPLOYMENT', reason: 'nope',
    }).catch((e: unknown) => e as ApiError);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(422);
  });

  it('enforces the golden rule — seeing a ticket is not the same as being able to move it', async () => {
    const db = getDb();
    // Priya is Support, so the ticket is in her project scope and she can read
    // it. She is not PM, not Admin, and not the current stage owner.
    db.currentUserId = 6;
    const t = db.tickets.find((x) => x.ticketId === T)!;
    t.status = 'IN_PROGRESS';
    t.currentStageCode = 'QA';
    t.assigneeId = 4; // Anil holds it

    const err = await post(`/tickets/${T}/handoff`, {
      toStageCode: 'DEPLOYMENT', toUserId: 5, effortHours: 1,
    }).catch((e: unknown) => e as ApiError);

    expect((err as ApiError).status).toBe(422);
    expect((err as ApiError).problem.title).toContain('current stage owner');
  });

  it('a Developer cannot even see a ticket held by someone else — 404, not 422', async () => {
    const db = getDb();
    db.currentUserId = 3; // Ravi
    const t = db.tickets.find((x) => x.ticketId === T)!;
    t.status = 'IN_PROGRESS';
    t.currentStageCode = 'QA';
    t.assigneeId = 4;

    // The two failures are genuinely different and must not be conflated:
    // out of scope is 404 because a 422 would confirm the ticket exists.
    const err = await post(`/tickets/${T}/handoff`, {
      toStageCode: 'DEPLOYMENT', toUserId: 5, effortHours: 1,
    }).catch((e: unknown) => e as ApiError);

    expect((err as ApiError).status).toBe(404);
  });

  it('blocks a handoff with no effort confirmation — decision G-1', async () => {
    const db = getDb();
    db.currentUserId = 2;
    const t = db.tickets.find((x) => x.ticketId === T)!;
    t.status = 'IN_PROGRESS';
    t.currentStageCode = 'TRIAGE';
    t.assigneeId = 2;

    const err = await post(`/tickets/${T}/handoff`, {
      toStageCode: 'DEVELOPMENT', toUserId: 3,
    }).catch((e: unknown) => e as ApiError);

    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).fieldErrors.effortHours).toBeDefined();
  });

  it('reopen seals the cycle, opens the next, and never touches prior effort', async () => {
    const db = getDb();
    db.currentUserId = 2;
    const before = db.effortLogs.filter((e) => e.ticketId === T && e.cycleNo === 1).length;

    await post(`/tickets/${T}/reopen`, { reason: 'Recurred again on 3 accounts' });

    const t = db.tickets.find((x) => x.ticketId === T)!;
    expect(t.cycleNo).toBe(3);
    expect(t.iterationNo).toBe(1);      // iteration restarts inside a new cycle
    expect(t.actualCloseDate).toBeNull();
    expect(db.cycles.find((c) => c.ticketId === T && c.cycleNo === 2)!.isSealed).toBe(true);
    expect(db.effortLogs.filter((e) => e.ticketId === T && e.cycleNo === 1)).toHaveLength(before);
  });
});

describe('row scoping is mirrored, so screens are built against scoped data', () => {
  it('a Developer sees only their own tickets', async () => {
    const db = getDb();
    db.currentUserId = 3; // Ravi
    const list = await get<Envelope<{ ticketId: string }[]>>('/tickets?limit=200');
    const mine = db.tickets.filter((t) => t.assigneeId === 3);
    expect(list.data).toHaveLength(mine.length);
    expect(list.data.length).toBeLessThan(db.tickets.length);
  });

  it('an out-of-scope ticket is 404, never 403 — no existence leak', async () => {
    const db = getDb();
    db.currentUserId = 3;
    const notMine = db.tickets.find((t) => t.assigneeId !== 3)!;

    const err = await get(`/tickets/${notMine.ticketId}/full`).catch((e: unknown) => e as ApiError);
    expect((err as ApiError).status).toBe(404);

    // And a genuinely absent ID is indistinguishable from it.
    const absent = await get('/tickets/CRM-26-99999/full').catch((e: unknown) => e as ApiError);
    expect((absent as ApiError).status).toBe(404);
    expect((absent as ApiError).problem.title).toBe((err as ApiError).problem.title);
  });

  it('an Admin sees everything', async () => {
    const db = getDb();
    db.currentUserId = 1;
    const list = await get<Envelope<unknown[]>>('/tickets?limit=200');
    expect(list.data).toHaveLength(db.tickets.length);
  });
});

describe('conventions hold at runtime, not just in the document', () => {
  it('wraps every success in { data } and paginates with a cursor', async () => {
    getDb().currentUserId = 1;
    const first = await get<Envelope<unknown[]>>('/tickets?limit=5');
    expect(Array.isArray(first.data)).toBe(true);
    expect(first.meta!.hasMore).toBe(true);

    const second = await get<Envelope<unknown[]>>(`/tickets?limit=5&cursor=${first.meta!.nextCursor}`);
    expect(second.data).toHaveLength(5);
    expect(second.data).not.toEqual(first.data);
  });

  it('creates a ticket with a generated ID in the contract format', async () => {
    getDb().currentUserId = 1;
    const created = await post<Envelope<{ ticketId: string; originalLevel: string }>>('/tickets', {
      projectId: 1, title: 'Mock-created ticket', taskTypeId: 2, level: 'HIGH',
    });
    // The generated constant, not a copied literal. This assertion claims to
    // check "the contract format", so it must fail when the contract moves — a
    // hand-written copy silently keeps asserting last month's rules, and this
    // one did: \d{5}, exactly five digits, which C-011 widened to \d{5,}
    // because ticket_seq never resets at year rollover.
    //
    // Deliberately the *path* pattern. A new ticket whose ID the detail route
    // cannot address is created, stored, and unreachable — which is the failure
    // the widening exists to prevent, and the one worth asserting here.
    expect(created.data.ticketId).toMatch(getTicketDetailPathTicketIdRegExp);
    expect(created.data.originalLevel).toBe('HIGH');

    // And it is really in the list, not just echoed back.
    const list = await get<Envelope<{ ticketId: string }[]>>('/tickets?limit=200');
    expect(list.data.map((t) => t.ticketId)).toContain(created.data.ticketId);
  });

  it('returns field-keyed 400s that React Hook Form can consume', async () => {
    getDb().currentUserId = 1;
    const err = await post('/tickets', { projectId: 1, title: 'no' }).catch((e: unknown) => e as ApiError);
    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).fieldErrors.title).toEqual(['must be at least 3 characters']);
  });

  it('answers 501 for an endpoint nobody mocked, instead of escaping to the network', async () => {
    const err = await get('/does-not-exist').catch((e: unknown) => e as ApiError);
    expect((err as ApiError).status).toBe(501);
    expect((err as ApiError).problem.title).toContain('No mock handler');
  });

  it('honours If-None-Match on the detail read', async () => {
    getDb().currentUserId = 1;
    const res = await fetch('/api/v1/tickets/CRM-26-00347/full');
    const etag = res.headers.get('ETag')!;
    expect(etag).toBeTruthy();
    const again = await fetch('/api/v1/tickets/CRM-26-00347/full', {
      headers: { 'If-None-Match': etag },
    });
    expect(again.status).toBe(304);
  });
});

describe('GET /tickets filters — C-015 saved views', () => {
  interface TicketRow {
    ticketId: string;
    status: string;
    plannedCloseDate: string;
    actualCloseDate: string | null;
    assignee: { id: number } | null;
  }

  it('dueFrom/dueTo actually filter plannedCloseDate — declared in the contract since C-014 but never read by the handler until now', async () => {
    getDb().currentUserId = 1;
    // The walkthrough ticket's plannedCloseDate is 2026-08-13.
    const day = '2026-08-13';
    const unfiltered = await get<Envelope<TicketRow[]>>('/tickets?limit=200');
    const list = await get<Envelope<TicketRow[]>>(`/tickets?dueFrom=${day}&dueTo=${day}&limit=200`);
    expect(list.data.length).toBeGreaterThan(0);
    expect(list.data.length).toBeLessThan(unfiltered.data.length);
    for (const t of list.data) {
      expect(t.plannedCloseDate.slice(0, 10)).toBe(day);
    }
    expect(list.data.map((t) => t.ticketId)).toContain('CRM-26-00347');
  });

  it('unassigned=true returns only tickets with no assignee', async () => {
    const db = getDb();
    db.currentUserId = 1;
    const someTicket = db.tickets.find((t) => t.ticketId !== 'CRM-26-00347')!;
    someTicket.assigneeId = null;

    const list = await get<Envelope<TicketRow[]>>('/tickets?unassigned=true&limit=200');
    expect(list.data.length).toBeGreaterThan(0);
    expect(list.data.every((t) => t.assignee == null)).toBe(true);
    expect(list.data.map((t) => t.ticketId)).toContain(someTicket.ticketId);
  });

  it('excludeClosed=true drops every CLOSED ticket', async () => {
    getDb().currentUserId = 1;
    const list = await get<Envelope<TicketRow[]>>('/tickets?excludeClosed=true&limit=200');
    expect(list.data.length).toBeGreaterThan(0);
    expect(list.data.some((t) => t.status === 'CLOSED')).toBe(false);
  });

  it('closedFrom/closedTo filter actualCloseDate, not plannedCloseDate', async () => {
    getDb().currentUserId = 1;
    // The walkthrough ticket closed 2026-08-14; its plannedCloseDate is the
    // 13th, so a range that only matches the 14th proves the handler is
    // reading actualCloseDate and not accidentally reusing dueFrom/dueTo.
    const inRange = await get<Envelope<TicketRow[]>>('/tickets?closedFrom=2026-08-14&closedTo=2026-08-14&limit=200');
    expect(inRange.data.map((t) => t.ticketId)).toContain('CRM-26-00347');

    const outOfRange = await get<Envelope<TicketRow[]>>('/tickets?closedFrom=2026-08-01&closedTo=2026-08-01&limit=200');
    expect(outOfRange.data.map((t) => t.ticketId)).not.toContain('CRM-26-00347');
  });
});

/** D-045 — the opt-in half of browser push. */
describe('push subscriptions', () => {
  interface Key { publicKey: string }
  const del = (url: string) => http<void>({ url, method: 'DELETE' });

  it('serves a VAPID key the browser could actually use', async () => {
    const key = await get<Envelope<Key>>('/push/public-key');
    // 65 base64url bytes — an uncompressed P-256 point. A shorter placeholder
    // would be rejected by pushManager.subscribe() rather than by us.
    expect(atob(key.data.publicKey.replace(/-/g, '+').replace(/_/g, '/'))).toHaveLength(65);
  });

  it('subscribes, and subscribing twice is one subscription', async () => {
    const body = { endpoint: 'https://push.example/abc', keys: { p256dh: 'x', auth: 'y' } };
    await post('/me/push-subscriptions', body);
    await post('/me/push-subscriptions', body);

    expect(getDb().pushSubscriptions).toHaveLength(1);
  });

  it('moves the subscription when a second user takes over the browser', async () => {
    const db = getDb();
    const body = { endpoint: 'https://push.example/shared', keys: { p256dh: 'x', auth: 'y' } };
    await post('/me/push-subscriptions', body);

    db.currentUserId = 4;
    await post('/me/push-subscriptions', body);

    expect(db.pushSubscriptions).toHaveLength(1);
    expect(db.pushSubscriptions[0].userId).toBe(4);
  });

  it('unsubscribes by endpoint, and says nothing about one that was never here', async () => {
    await post('/me/push-subscriptions', {
      endpoint: 'https://push.example/gone', keys: { p256dh: 'x', auth: 'y' },
    });

    await del('/me/push-subscriptions?endpoint=' + encodeURIComponent('https://push.example/gone'));
    await del('/me/push-subscriptions?endpoint=' + encodeURIComponent('https://push.example/never'));

    expect(getDb().pushSubscriptions).toHaveLength(0);
  });
})

/**
 * D-060 — the four §7.5 fields, in the contract and the mock before C-065…C-070
 * are built against them.
 */
describe('where it happened — module, screen, feature, steps', () => {
  interface ModuleRow { id: number; code: string; name: string; seq: number; isActive: boolean }
  interface TriageRow {
    ticketId: string;
    moduleId: number | null;
    screenName: string | null;
    feature: string | null;
    stepsToGenerate: string | null;
  }

  it('returns the eight modules in seq order', async () => {
    const list = await get<Envelope<ModuleRow[]>>('/masters/modules');
    expect(list.data.filter((m) => m.isActive).map((m) => m.name)).toEqual([
      'Student', 'Admission', 'Fees', 'Examination',
      'Attendance', 'Library', 'Inventory', 'Parent App',
    ]);
    expect([...list.data].sort((a, b) => a.seq - b.seq)).toEqual(list.data);
  });

  it('includes deactivated modules, because an old ticket still has to render its name', async () => {
    const list = await get<Envelope<ModuleRow[]>>('/masters/modules');
    const retired = list.data.find((m) => !m.isActive);
    expect(retired).toBeDefined();

    getDb().currentUserId = 1;
    const tickets = await get<Envelope<TriageRow[]>>('/tickets?limit=200');
    // Filtering inactive rows out of the master would leave this ticket's module
    // cell blank — which is the whole reason the endpoint returns them.
    expect(tickets.data.some((t) => t.moduleId === retired!.id)).toBe(true);
  });

  it('every seeded moduleId resolves against the master', async () => {
    getDb().currentUserId = 1;
    const [modules, tickets] = await Promise.all([
      get<Envelope<ModuleRow[]>>('/masters/modules'),
      get<Envelope<TriageRow[]>>('/tickets?limit=200'),
    ]);
    const known = new Set(modules.data.map((m) => m.id));
    const orphans = tickets.data.filter((t) => t.moduleId != null && !known.has(t.moduleId));
    expect(orphans).toEqual([]);
  });

  it('leaves some tickets with no module at all — the state of everything raised before the fields existed', async () => {
    getDb().currentUserId = 1;
    const tickets = await get<Envelope<TriageRow[]>>('/tickets?limit=200');
    expect(tickets.data.some((t) => t.moduleId == null)).toBe(true);
    expect(tickets.data.some((t) => t.stepsToGenerate == null)).toBe(true);
  });

  it('moduleId filters the list, and excludes tickets with no module', async () => {
    getDb().currentUserId = 1;
    const all = await get<Envelope<TriageRow[]>>('/tickets?limit=200');
    const target = all.data.find((t) => t.moduleId != null)!.moduleId!;

    const filtered = await get<Envelope<TriageRow[]>>(`/tickets?moduleId=${target}&limit=200`);
    expect(filtered.data.length).toBeGreaterThan(0);
    expect(filtered.data.length).toBeLessThan(all.data.length);
    expect(filtered.data.every((t) => t.moduleId === target)).toBe(true);
  });

  it('round-trips all four fields through create', async () => {
    getDb().currentUserId = 1;
    const created = await post<Envelope<TriageRow>>('/tickets', {
      projectId: 1, title: 'Fee receipt prints without the duplicate watermark',
      taskTypeId: 2, level: 'HIGH',
      moduleId: 3, screenName: 'Fee Receipt Print', feature: 'Reprint with watermark',
      stepsToGenerate: '<ol><li>Open a paid receipt.</li><li>Press Reprint.</li></ol>',
    });
    expect(created.data.moduleId).toBe(3);
    expect(created.data.screenName).toBe('Fee Receipt Print');
    expect(created.data.feature).toBe('Reprint with watermark');
    expect(created.data.stepsToGenerate).toContain('<li>Press Reprint.</li>');

    // Read it back through the aggregated detail call — the only GET for one
    // ticket, and what C-069 renders from.
    const read = await get<Envelope<{ ticket: TriageRow }>>(`/tickets/${created.data.ticketId}/full`);
    expect(read.data.ticket.stepsToGenerate).toBe(created.data.stepsToGenerate);
    expect(read.data.ticket.moduleId).toBe(3);
  });

  it('creates without any of them — all four are optional on the wire', async () => {
    getDb().currentUserId = 1;
    const created = await post<Envelope<TriageRow>>('/tickets', {
      projectId: 1, title: 'A ticket that names no module', taskTypeId: 1, level: 'LOW',
    });
    expect(created.data.moduleId).toBeNull();
    expect(created.data.screenName).toBeNull();
    expect(created.data.stepsToGenerate).toBeNull();
  });

  it('rejects a module the master does not have', async () => {
    getDb().currentUserId = 1;
    await expect(
      post('/tickets', { projectId: 1, title: 'Unknown module', taskTypeId: 1, level: 'LOW', moduleId: 999 }),
    ).rejects.toSatisfy((e: unknown) =>
      e instanceof ApiError && e.problem.status === 400 && 'moduleId' in (e.problem.errors ?? {}),
    );
  });
});

/**
 * C-012 · SLA resolution and the planned close date.
 *
 * These pin the *mock's* behaviour, not the server's — but the mock is what the
 * create form is built and demonstrated against, so a mock that resolves
 * differently teaches the screen the wrong thing.
 *
 * The seeded calendar is Mon–Fri, 09:30–18:30 **read as UTC** (`handlers/sla.ts`
 * explains that deliberate simplification), with 15 Aug and 2 Oct recurring
 * holidays, Anil away 24–28 Aug and Karan on a half day on 3 Sep.
 */
describe('planned close date — C-012', () => {
  interface Preview {
    from: string;
    plannedCloseDate: string | null;
    firstResponseDue: string | null;
    responseHrs: number | null;
    resolutionHrs: number | null;
    source: string;
    slaPolicyId: number | null;
  }

  const FRIDAY_1730 = '2026-08-14T17:30:00.000Z';

  const preview = (query: string) => get<Envelope<Preview>>(`/tickets/planned-close-date?${query}`);

  describe('the resolution ladder', () => {
    it('takes the project x task type policy first', async () => {
      // CRM's Production Bug is 8 h where the org-wide High default is 16 h.
      const { data } = await preview(`projectId=1&taskTypeId=2&level=HIGH&from=${FRIDAY_1730}`);
      expect(data.source).toBe('PROJECT_TASK_TYPE');
      expect(data.resolutionHrs).toBe(8);
      expect(data.slaPolicyId).toBe(5);
    });

    it('falls to the project default for the level when no task type matches', async () => {
      // PAY tightens Critical for every type; task type 1 has no row of its own.
      const { data } = await preview(`projectId=2&taskTypeId=1&level=CRITICAL&from=${FRIDAY_1730}`);
      expect(data.source).toBe('PROJECT_LEVEL');
      expect(data.resolutionHrs).toBe(3);
    });

    it('falls to the org-wide default when the project has nothing', async () => {
      const { data } = await preview(`projectId=3&taskTypeId=1&level=HIGH&from=${FRIDAY_1730}`);
      expect(data.source).toBe('ORG_DEFAULT');
      expect(data.resolutionHrs).toBe(16);
    });

    /**
     * The behaviour C-012 exists to produce. A matrix answering the same hours
     * for every level still renders a date, so a test that only asserts "a date
     * appears" passes against a broken feature.
     */
    it('gives a different answer for every level on the same project and type', async () => {
      const levels = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
      const hours = await Promise.all(
        levels.map(async (level) =>
          (await preview(`projectId=3&taskTypeId=1&level=${level}&from=${FRIDAY_1730}`)).data.resolutionHrs,
        ),
      );
      expect(hours).toEqual([120, 48, 16, 4]);
    });

    it('previews before a task type is picked, starting the ladder at rung 2', async () => {
      const { data } = await preview(`projectId=2&level=CRITICAL&from=${FRIDAY_1730}`);
      expect(data.source).toBe('PROJECT_LEVEL');
      expect(data.plannedCloseDate).not.toBeNull();
    });
  });

  describe('the working-calendar walk', () => {
    /**
     * The rule the whole feature is judged on: a Friday-evening ticket with a
     * four-hour target must not land on Saturday morning. One hour is left in
     * Friday's window, so three hours spill into Monday.
     */
    it('carries the remainder over the weekend rather than through it', async () => {
      const { data } = await preview(`projectId=3&taskTypeId=1&level=CRITICAL&from=${FRIDAY_1730}`);
      expect(data.resolutionHrs).toBe(4);
      expect(data.plannedCloseDate).toBe('2026-08-17T12:30:00.000Z');
    });

    it('skips a recurring org holiday', async () => {
      // 2 Oct 2026 is a Friday and Gandhi Jayanti, so the walk lands Monday 5th.
      const { data } = await preview('projectId=3&taskTypeId=1&level=CRITICAL&from=2026-10-01T17:30:00.000Z');
      expect(data.plannedCloseDate).toBe('2026-10-05T12:30:00.000Z');
    });

    it('stops the clock for the assignee approved leave', async () => {
      const from = 'from=2026-08-21T17:30:00.000Z';
      const unassigned = await preview(`projectId=3&taskTypeId=1&level=CRITICAL&${from}`);
      // Anil (4) is away Mon 24 - Fri 28 Aug, so his week is skipped entirely.
      const anil = await preview(`projectId=3&taskTypeId=1&level=CRITICAL&assigneeId=4&${from}`);

      expect(unassigned.data.plannedCloseDate).toBe('2026-08-24T12:30:00.000Z');
      expect(anil.data.plannedCloseDate).toBe('2026-08-31T12:30:00.000Z');
    });

    it('treats a half day as the second half of the working day', async () => {
      const from = 'from=2026-09-03T00:00:00.000Z';
      const karan = await preview(`projectId=1&taskTypeId=2&level=HIGH&assigneeId=5&${from}`);
      const nobody = await preview(`projectId=1&taskTypeId=2&level=HIGH&${from}`);

      // Karan starts at 14:00, so 8 h is 4.5 h on the 3rd and 3.5 h on the 4th.
      // With nobody assigned the whole 9 h window is available and it fits in one day.
      expect(karan.data.plannedCloseDate).toBe('2026-09-04T13:00:00.000Z');
      expect(nobody.data.plannedCloseDate).toBe('2026-09-03T17:30:00.000Z');
    });

    it('echoes from, so the caller can check which instant it measured from', async () => {
      const { data } = await preview(`projectId=3&taskTypeId=1&level=HIGH&from=${FRIDAY_1730}`);
      expect(data.from).toBe(FRIDAY_1730);
    });

    it('walks the response target separately from the resolution one', async () => {
      const { data } = await preview(`projectId=1&taskTypeId=2&level=HIGH&from=${FRIDAY_1730}`);
      expect(data.responseHrs).toBe(2);
      // 1 h left on Friday, 1 h into Monday.
      expect(data.firstResponseDue).toBe('2026-08-17T10:30:00.000Z');
      expect(data.plannedCloseDate).not.toBe(data.firstResponseDue);
    });
  });

  describe('what it refuses', () => {
    it('404s an unknown project rather than answering with the org default', async () => {
      await expect(preview('projectId=999&taskTypeId=1&level=HIGH')).rejects.toSatisfy(
        (e: unknown) => e instanceof ApiError && e.problem.status === 404,
      );
    });

    it('400s an unknown level, against the level field', async () => {
      await expect(preview('projectId=1&taskTypeId=1&level=URGENT')).rejects.toSatisfy(
        (e: unknown) =>
          e instanceof ApiError && e.problem.status === 400 && 'level' in (e.problem.errors ?? {}),
      );
    });
  });

  /**
   * The defect this replaced. `POST /tickets` stamped `now + defaultSlaHrs` in
   * wall-clock milliseconds, so the date the preview showed and the date the
   * ticket carried differed by every weekend and holiday between them — and
   * only one of the two was ever on screen.
   */
  it('stores on create what the same resolution and walk produce', async () => {
    getDb().currentUserId = 1;
    const created = await post<Envelope<{ ticketId: string; plannedCloseDate: string }>>('/tickets', {
      projectId: 1, title: 'A ticket whose date must match its preview', taskTypeId: 2, level: 'HIGH',
    });

    const stored = new Date(created.data.plannedCloseDate);
    const minutes = stored.getUTCHours() * 60 + stored.getUTCMinutes();
    expect(minutes).toBeGreaterThanOrEqual(9 * 60 + 30);
    expect(minutes).toBeLessThanOrEqual(18 * 60 + 30);
    expect([0, 6]).not.toContain(stored.getUTCDay());
  });

  it('keeps an explicit planned close date on create rather than recomputing it', async () => {
    getDb().currentUserId = 1;
    const explicit = '2026-12-25T09:00:00.000Z';
    const created = await post<Envelope<{ plannedCloseDate: string }>>('/tickets', {
      projectId: 1, title: 'A date the PM chose', taskTypeId: 2, level: 'HIGH', plannedCloseDate: explicit,
    });
    expect(created.data.plannedCloseDate).toBe(explicit);
  });
});
