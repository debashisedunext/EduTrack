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
    const list = await get<Envelope<{ ticketCode: string }[]>>('/tickets?limit=200');
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
    const list = await get<Envelope<{ ticketCode: string }[]>>('/tickets?limit=200');
    expect(list.data.map((t) => t.ticketCode)).toContain(created.data.ticketId);
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
  /** A row of `GET /tickets` — flat ids, per the contract's TicketSummary. */
  interface TicketRow {
    ticketCode: string;
    status: string;
    plannedCloseDate: string;
    actualCloseDate: string | null;
    assignedTo: number | null;
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
    expect(list.data.map((t) => t.ticketCode)).toContain('CRM-26-00347');
  });

  it('unassigned=true returns only tickets with no assignee', async () => {
    const db = getDb();
    db.currentUserId = 1;
    const someTicket = db.tickets.find((t) => t.ticketId !== 'CRM-26-00347')!;
    someTicket.assigneeId = null;

    const list = await get<Envelope<TicketRow[]>>('/tickets?unassigned=true&limit=200');
    expect(list.data.length).toBeGreaterThan(0);
    expect(list.data.every((t) => t.assignedTo == null)).toBe(true);
    expect(list.data.map((t) => t.ticketCode)).toContain(someTicket.ticketId);
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
    expect(inRange.data.map((t) => t.ticketCode)).toContain('CRM-26-00347');

    const outOfRange = await get<Envelope<TicketRow[]>>('/tickets?closedFrom=2026-08-01&closedTo=2026-08-01&limit=200');
    expect(outOfRange.data.map((t) => t.ticketCode)).not.toContain('CRM-26-00347');
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
  /**
   * The §7.5 fields, as the *detail* and *create* responses carry them.
   *
   * `GET /tickets` returns `TicketSummary` and carries none of them — no
   * `moduleId`, no `stepsToGenerate`, and its code is `ticketCode`. The tests
   * below that check seed shape therefore read the store directly, and the one
   * that checks the module filter proves it by which codes come back.
   */
  interface TriageRow {
    ticketId: string;
    ticketCode?: string;
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
    // Filtering inactive rows out of the master would leave this ticket's module
    // cell blank — which is the whole reason the endpoint returns them.
    // Asserted against the store, not the list: GET /tickets returns
    // TicketSummary and has never carried moduleId. What matters here is the
    // seed — a ticket does point at a retired module — and the endpoint that
    // must keep returning it is /masters/modules, checked above.
    expect(getDb().tickets.some((t) => t.moduleId === retired!.id)).toBe(true);
  });

  it('every seeded moduleId resolves against the master', async () => {
    getDb().currentUserId = 1;
    const modules = await get<Envelope<ModuleRow[]>>('/masters/modules');
    const known = new Set(modules.data.map((m) => m.id));
    const orphans = getDb().tickets.filter((t) => t.moduleId != null && !known.has(t.moduleId));
    expect(orphans).toEqual([]);
  });

  it('leaves some tickets with no module at all — the state of everything raised before the fields existed', async () => {
    getDb().currentUserId = 1;
    expect(getDb().tickets.some((t) => t.moduleId == null)).toBe(true);
    expect(getDb().tickets.some((t) => t.stepsToGenerate == null)).toBe(true);
  });

  it('moduleId filters the list, and excludes tickets with no module', async () => {
    getDb().currentUserId = 1;
    const all = await get<Envelope<TriageRow[]>>('/tickets?limit=200');
    const target = getDb().tickets.find((t) => t.moduleId != null)!.moduleId!;

    const filtered = await get<Envelope<TriageRow[]>>(`/tickets?moduleId=${target}&limit=200`);
    expect(filtered.data.length).toBeGreaterThan(0);
    expect(filtered.data.length).toBeLessThan(all.data.length);

    // Checked by which tickets came back, since the row shape carries no
    // moduleId — every returned code must belong to a ticket on that module.
    const onTarget = new Set(
      getDb().tickets.filter((t) => t.moduleId === target).map((t) => t.ticketId),
    );
    expect(filtered.data.every((t) => onTarget.has(t.ticketCode as string))).toBe(true);
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

/**
 * D-055 / D-056 · Ask Status.
 *
 * The mock is what Divyansh builds S-25's card and badge against, so what these
 * pin is the behaviour a screen would otherwise get wrong: that a second click
 * is not a second question, and that a reply clears the list.
 */
describe('ask status', () => {
  interface StatusRequest {
    id: number; ticketId: string; isAnswered: boolean; note: string | null;
    responseWorkingMinutes: number | null;
  }
  const RAVI = 3;
  let T: string;

  beforeEach(() => {
    // Meera — a PM, and the manager the seeded requests come from.
    getDb().currentUserId = 2;
    // Chosen rather than hard-coded. The walkthrough ticket is CLOSED and
    // assigned to Meera herself, so asking about it is refused for a reason
    // that has nothing to do with what these tests are checking — and a
    // hard-coded id would make that look like a bug in Ask Status.
    T = getDb().tickets.find((t) => t.assigneeId === RAVI && t.status !== 'CLOSED')!.ticketId;
  });

  it('posts the card as a STATUS_REQUEST message the client can draw', async () => {
    const before = getDb().chatMessages.length;
    const asked = await post<Envelope<StatusRequest>>(`/tickets/${T}/ask-status`, {
      note: 'Client call at four — where are we?',
    });

    const card = getDb().chatMessages.at(-1);
    expect(getDb().chatMessages).toHaveLength(before + 1);
    expect(card?.kind).toBe('STATUS_REQUEST');
    expect(card?.body).toBe('Client call at four — where are we?');
    expect(asked.data.note).toBe('Client call at four — where are we?');
    expect(asked.data.isAnswered).toBe(false);
  });

  it('defaults to the blueprint’s own wording', async () => {
    await post(`/tickets/${T}/ask-status`);
    expect(getDb().chatMessages.at(-1)?.body)
      .toBe('Please share the current status and expected closure.');
  });

  it('clicking twice asks once', async () => {
    const first = await post<Envelope<StatusRequest>>(`/tickets/${T}/ask-status`);
    const cards = getDb().chatMessages.length;
    const second = await post<Envelope<StatusRequest>>(`/tickets/${T}/ask-status`);

    expect(second.data.id).toBe(first.data.id);
    expect(getDb().chatMessages).toHaveLength(cards);
  });

  it('refuses when there is nobody to ask', async () => {
    const db = getDb();
    db.tickets.find((t) => t.ticketId === T)!.assigneeId = null;

    await expect(post(`/tickets/${T}/ask-status`)).rejects.toBeInstanceOf(ApiError);
  });

  it('the badge is one ticket’s and the awaiting list is one manager’s', async () => {
    const db = getDb();
    const asked = await post<Envelope<StatusRequest>>(`/tickets/${T}/ask-status`);

    // Two different questions. The badge answers "what is outstanding HERE",
    // the awaiting list answers "what am I waiting on ANYWHERE" — and the
    // seeded request on the walkthrough ticket is in the second and not the
    // first. Asserting the two lists were the same length would have passed on
    // a fixture with one ticket and hidden the difference entirely.
    const badge = await get<Envelope<StatusRequest[]>>(`/tickets/${T}/status-requests`);
    const awaiting = await get<Envelope<StatusRequest[]>>('/me/awaiting-response');
    expect(badge.data.map((r) => r.id)).toEqual([asked.data.id]);
    expect(awaiting.data.map((r) => r.id)).toContain(asked.data.id);
    expect(awaiting.data.length).toBeGreaterThan(badge.data.length);

    // Longest wait first: the seeded 7 Aug ask comes before the one just made.
    expect(awaiting.data.at(-1)!.id).toBe(asked.data.id);

    const thread = db.chatThreads.find((t) => t.ticketId === T)!;
    db.currentUserId = RAVI; // the assignee
    await post(`/chat/threads/${thread.id}/messages`, { body: 'Fix is in review, closing today.' });

    db.currentUserId = 2;
    expect((await get<Envelope<StatusRequest[]>>(`/tickets/${T}/status-requests`)).data).toHaveLength(0);
    expect((await get<Envelope<StatusRequest[]>>('/me/awaiting-response')).data.map((r) => r.id))
      .not.toContain(asked.data.id);
  });

  it('measures the wait in working minutes, so a weekend is not held against anybody', async () => {
    const db = getDb();
    // The seeded open request was asked at 17:40 on Friday 7 Aug and is still
    // unanswered. Wall clock to any later weekday is days; the working answer
    // is minutes on Friday plus whatever of the working week has elapsed since.
    const waiting = db.statusRequests.find((r) => r.answeredAt === null)!;
    const thread = db.chatThreads.find((t) => t.id === waiting.threadId)!;

    db.currentUserId = waiting.askedOfId;
    await post(`/chat/threads/${thread.id}/messages`, { body: 'Sorry — was off. Looking now.' });

    const minutes = db.statusRequests.find((r) => r.id === waiting.id)!.responseWorkingMinutes!;
    const wallClock = (Date.now() - new Date(waiting.requestedAt).getTime()) / 60_000;
    expect(minutes).toBeGreaterThan(0);
    expect(minutes).toBeLessThan(wallClock);
  });

  it('withholds the note once the manager deletes their own question', async () => {
    const db = getDb();
    const asked = await post<Envelope<StatusRequest>>(`/tickets/${T}/ask-status`, { note: 'Where are we?' });
    const card = db.chatMessages.at(-1)!;

    card.isDeleted = true;

    const open = await get<Envelope<StatusRequest[]>>(`/tickets/${T}/status-requests`);
    expect(open.data.find((r) => r.id === asked.data.id)?.note).toBeNull();
  });
});

/**
 * C-026 · `/mock-files/*`, the stand-in object store.
 *
 * Every attachment URL the mock has minted since C-023 pointed at this path and
 * nothing served it, so the gallery would have rendered broken images under
 * `npm run dev` while working perfectly against the real backend. These pin the
 * one thing that could regress silently: that the bytes are a *real* PNG. A
 * hand-rolled encoder that emits a plausible-looking file every decoder rejects
 * would look completely fine in review.
 */
describe('mock file storage', () => {
  const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

  const fetchFile = async (path: string) => {
    const response = await fetch(path);
    return { response, bytes: new Uint8Array(await response.arrayBuffer()) };
  };

  it('serves real PNG bytes for a thumbnail', async () => {
    const { response, bytes } = await fetchFile('/mock-files/1/thumb.png?sig=mock');

    expect(response.headers.get('Content-Type')).toBe('image/png');
    expect([...bytes.slice(0, 8)]).toEqual(PNG_SIGNATURE);
    // IHDR is required to be first, and the reader that would reject a
    // malformed one is the browser nobody is testing in here.
    expect(String.fromCharCode(...bytes.slice(12, 16))).toBe('IHDR');
    expect(String.fromCharCode(...bytes.slice(-8, -4))).toBe('IEND');
  });

  it('declares the dimensions it actually encoded', async () => {
    // Width and height live in IHDR at a fixed offset. Getting these wrong is
    // the failure mode of a stored-DEFLATE encoder: the header says one thing,
    // the pixel data is another length, and the file decodes to garbage.
    const { bytes } = await fetchFile('/mock-files/1/thumb.png?sig=mock');
    const view = new DataView(bytes.buffer);

    expect(view.getUint32(16)).toBe(320);
    expect(view.getUint32(20)).toBe(320);
    // 3 bytes per pixel plus one filter byte per row, so the payload cannot be
    // smaller than this — a header-only file would pass every check above.
    expect(bytes.length).toBeGreaterThan(320 * (320 * 3 + 1) * 0.5);
  });

  it('serves the full-size file larger than its thumbnail', async () => {
    // So the strip visibly loads the reduction and the lightbox visibly loads
    // the original, which is the whole point of storing two objects.
    const thumbnail = await fetchFile('/mock-files/1/thumb.png?sig=mock');
    const full = await fetchFile('/mock-files/1/screenshot.png?sig=mock');

    expect(new DataView(full.bytes.buffer).getUint32(16)).toBeGreaterThan(
      new DataView(thumbnail.bytes.buffer).getUint32(16),
    );
  });

  it('gives different files different colours, and the same file the same one', async () => {
    // Identical swatches would make a navigation or ordering bug in the
    // lightbox invisible; a colour that changed per request would make a
    // caching bug look like one.
    const first = await fetchFile('/mock-files/1/thumb.png?sig=mock');
    const again = await fetchFile('/mock-files/1/thumb.png?sig=mock');
    const other = await fetchFile('/mock-files/2/thumb.png?sig=mock');

    expect([...first.bytes]).toEqual([...again.bytes]);
    expect([...first.bytes]).not.toEqual([...other.bytes]);
  });
});

/**
 * C-027 · §4B.4's attachment caps, now a resource rather than three constants.
 *
 * The upload handler enforces all three of them, and **that half cannot be
 * exercised here**: `POST …/attachments` reads `request.formData()`, and under
 * vitest no genuine multipart body reaches a handler — jsdom's `FormData` and
 * Node's `fetch` are different realms, so the body arrives as the literal
 * `[object FormData]`. `useTicketAttachments.test.tsx` documents the whole
 * problem and why it belongs in `test/setup.ts`. What is pinned here is the
 * source those caps are read from, which is the part that changed.
 */
describe('attachment limits', () => {
  /**
   * `If-Match: *` rather than a real tag, per RFC 9110.
   *
   * The mutator returns the parsed body and not the response, so a test cannot
   * read the `ETag` the `GET` emits — and the precondition is not what these
   * tests are about. That it is *required* is asserted directly below, which is
   * the half a screen can get wrong.
   */
  const put = <T,>(url: string, data: unknown) =>
    http<T>({ url, method: 'PUT', data, headers: { 'If-Match': '*' } });

  interface Limits { maxFileBytes: number; maxTicketBytes: number; maxFiles: number; ceilingBytes: number }

  it('starts at the blueprint’s own numbers, which is what the migration seeds', async () => {
    const limits = await get<Envelope<Limits>>('/attachments/limits');

    expect(limits.data).toMatchObject({
      maxFileBytes: 10 * 1024 * 1024,
      maxTicketBytes: 50 * 1024 * 1024,
      maxFiles: 20,
    });
  });

  it('requires If-Match, so a screen cannot silently erase another admin’s save', async () => {
    // One row, org-wide, replaced wholesale: two administrators editing the
    // limits are editing the same row by definition. A guard the real backend
    // has and the mock waves through is a guard the frontend never exercises.
    await expect(http({
      url: '/attachments/limits', method: 'PUT',
      data: { maxFileBytes: 1024, maxTicketBytes: 4096, maxFiles: 3 },
    })).rejects.toMatchObject({ status: 428 });
  });

  it('a saved change is what the next read returns', async () => {
    await put('/attachments/limits', { maxFileBytes: 1024, maxTicketBytes: 4096, maxFiles: 3 });

    expect((await get<Envelope<Limits>>('/attachments/limits')).data.maxFiles).toBe(3);
  });

  it('refuses a per-ticket total below the per-file cap', async () => {
    // Not tidiness. It would make the per-file cap unreachable: every file large
    // enough to test it is refused by the ticket total first, telling the user
    // to remove an attachment from a ticket that may have none.
    await expect(put('/attachments/limits', { maxFileBytes: 4096, maxTicketBytes: 1024, maxFiles: 3 }))
      .rejects.toMatchObject({ status: 422 });
  });

  it('refuses a per-file cap above what the server can accept', async () => {
    // The container refuses an oversized body during parsing, so a cap above
    // `ceilingBytes` would save successfully and change nothing.
    await expect(put('/attachments/limits', {
      maxFileBytes: 40 * 1024 * 1024, maxTicketBytes: 80 * 1024 * 1024, maxFiles: 3,
    })).rejects.toMatchObject({ status: 422 });
  });

  it('refuses zero, which is not “unlimited”', async () => {
    await expect(put('/attachments/limits', { maxFileBytes: 1024, maxTicketBytes: 4096, maxFiles: 0 }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('does not let a client raise the ceiling it does not control', async () => {
    await put('/attachments/limits', {
      maxFileBytes: 1024, maxTicketBytes: 4096, maxFiles: 3, ceilingBytes: 999 * 1024 * 1024,
    });

    expect((await get<Envelope<Limits>>('/attachments/limits')).data.ceilingBytes).toBe(10 * 1024 * 1024);
  });
});

/**
 * C-028 · §4B.4's deletion rule, as the mock enforces it.
 *
 * Worth testing here rather than only against the server, because the mock is
 * what Stream C's screens are developed against: before this task it set
 * `isDeleted` for anybody who asked, with no window, no uploader check and no
 * tombstone — so a picker built against it would have met its first 403 in
 * production. These pin the three behaviours the client actually has to render.
 */
describe('C-028 · removing an attachment', () => {
  const del = (url: string) => http<void>({ url, method: 'DELETE' });

  interface Row { id: number; fileName: string; isDeleted: boolean; deletedBy: { displayName: string } | null }

  /**
   * The seeded attachment, on a ticket the current caller can actually see.
   *
   * The scope fixing is the point. `findTicket` is scope-aware — a Developer
   * sees only tickets assigned to them — so without this every assertion below
   * would answer 404 and the whole block would pass or fail on A-035's row scope
   * rather than on §4B.4's deletion rule. Both scopes are satisfied because the
   * caller's role varies between these tests: assignee for a Developer, project
   * membership for a PM.
   */
  function seeded() {
    const db = getDb();
    const a = db.attachments[0];
    const ticket = db.tickets.find((t) => t.ticketId === a.ticketId)!;
    const me = db.users.find((u) => u.id === db.currentUserId)!;
    ticket.assigneeId = me.id;
    if (!me.projectIds.includes(ticket.projectId)) me.projectIds.push(ticket.projectId);
    return { db, a, ticket: a.ticketId };
  }

  const list = async (ticketId: string) =>
    (await get<Envelope<Row[]>>(`/tickets/${ticketId}/attachments`)).data;

  it('lets the uploader take their own file back, leaving nothing behind', async () => {
    const { db, a, ticket } = seeded();
    a.uploadedById = db.currentUserId;
    a.createdAt = new Date().toISOString();

    await del(`/tickets/${ticket}/attachments/${a.id}`);

    // Gone entirely — not a tombstone. A support agent who pastes the wrong
    // screenshot and removes it has not done something the ticket must remember.
    expect(await list(ticket)).not.toContainEqual(expect.objectContaining({ id: a.id }));
  });

  it('refuses a colleague, and says why', async () => {
    const { db, a, ticket } = seeded();
    a.uploadedById = db.currentUserId + 1;
    a.createdAt = new Date().toISOString();

    // The message matters as much as the status: the row reappears in the
    // picker, and without a reason that reads as a broken button.
    await expect(del(`/tickets/${ticket}/attachments/${a.id}`)).rejects.toMatchObject({
      status: 403,
      problem: { detail: expect.stringContaining('person who attached this file') },
    });
    expect(await list(ticket)).toContainEqual(expect.objectContaining({ id: a.id }));
  });

  it('lets a PM remove somebody else’s file, and records that they did', async () => {
    // The role is switched *before* seeded(), so the ticket lands in the PM's
    // project scope rather than in the Developer's assignee scope.
    getDb().currentUserId = getDb().users.find((u) => u.role === 'PM')!.id;
    const { db, a, ticket } = seeded();
    a.uploadedById = db.currentUserId + 1000;
    a.createdAt = new Date().toISOString();

    await del(`/tickets/${ticket}/attachments/${a.id}`);

    // Inside the window and still a tombstone — the assertion that separates
    // §4B.4's rule from a plain timer. A supervisory removal is exactly the one
    // the ticket needs to record.
    const row = (await list(ticket)).find((r) => r.id === a.id);
    expect(row).toMatchObject({ isDeleted: true });
    expect(row?.deletedBy?.displayName).toBeTruthy();
  });

  it('leaves a tombstone when the uploader removes it long afterwards', async () => {
    const { db, a, ticket } = seeded();
    a.uploadedById = db.currentUserId;
    a.createdAt = new Date(Date.now() - 60 * 60 * 1000).toISOString();

    await del(`/tickets/${ticket}/attachments/${a.id}`);

    expect((await list(ticket)).find((r) => r.id === a.id)).toMatchObject({ isDeleted: true });
  });

  it('is idempotent — a retry is not an error', async () => {
    const { db, a, ticket } = seeded();
    a.uploadedById = db.currentUserId;
    a.createdAt = new Date().toISOString();

    await del(`/tickets/${ticket}/attachments/${a.id}`);
    // The client removes optimistically and a retry after a dropped response is
    // ordinary. Refusing would also distinguish "already removed" from "never
    // existed" for anyone allowed to ask.
    // Resolving is the whole assertion — a 204 carries no body, so there is
    // nothing to be defined and the point is only that it did not reject.
    await expect(del(`/tickets/${ticket}/attachments/${a.id}`)).resolves.toBeUndefined();
  });

  it('will not remove an attachment through another ticket’s path', async () => {
    const { db, a } = seeded();
    a.uploadedById = db.currentUserId;
    const other = db.tickets.find((t) => t.ticketId !== a.ticketId)!;
    // The other ticket is put in scope too, so the 404 proves the ticket check
    // and not merely that the caller could not see the path. Without this the
    // test would pass with the check deleted.
    other.assigneeId = db.currentUserId;

    // Ids are bare integers, so without the check this would answer differently
    // depending on whose attachment it is — which enumerates them.
    await expect(del(`/tickets/${other.ticketId}/attachments/${a.id}`)).rejects.toMatchObject({ status: 404 });
  });
});

describe('B-027 · the client_contacts child grid', () => {
  interface Row {
    id: number; name: string; designation: string | null; email: string | null;
    isPrimary: boolean; notificationOptIn: boolean; portalAccess: boolean; isActive: boolean;
  }

  const contactsOf = async (clientId: number, includeInactive = false) =>
    (await get<Envelope<Row[]>>(
      `/clients/${clientId}/contacts?includeInactive=${String(includeInactive)}`,
    )).data;

  const remove = (url: string) => http<void>({ url, method: 'DELETE' });
  const edit = <T,>(url: string, data: unknown) => http<T>({ url, method: 'PATCH', data });

  /**
   * The default is the half that matters, and only a request can prove it.
   *
   * The component tests always ask for `includeInactive=true`, because that is
   * what the grid sends. Nothing else asserts the *default* — and the default is
   * what C-021's reporter dropdown reads, so getting it wrong means the ticket
   * create form goes on offering somebody who has left the client.
   */
  it('hides removed contacts by default and returns them on request', async () => {
    // Acme's Ravi Menon is seeded removed, which is why the fixture has one.
    const offered = await contactsOf(1);
    const administered = await contactsOf(1, true);

    expect(offered.map((c) => c.name)).not.toContain('Ravi Menon');
    expect(administered.map((c) => c.name)).toContain('Ravi Menon');
    expect(administered.find((c) => c.name === 'Ravi Menon')?.isActive).toBe(false);
  });

  /** Live rows before removed ones, primary first — the server's ORDER BY. */
  it('sorts live before removed, primary before the rest', async () => {
    const rows = await contactsOf(1, true);

    expect(rows[0].isPrimary).toBe(true);
    expect(rows[rows.length - 1].isActive).toBe(false);
  });

  /**
   * `tickets.client_contact_id` is a foreign key with no cascade, so removal
   * deactivates. A mock that spliced the row out would let a screen ship that
   * cannot render the reporter of a historical ticket.
   */
  it('removes by deactivating, and removing again is still 204', async () => {
    const before = getDb().contacts.length;

    await remove('/clients/1/contacts/2');
    await remove('/clients/1/contacts/2');

    expect(getDb().contacts).toHaveLength(before);
    expect(getDb().contacts.find((c) => c.id === 2)?.isActive).toBe(false);
  });

  /** Removing the primary clears the flag too — see the server's UPDATE. */
  it('clears the primary flag when the primary is removed', async () => {
    await remove('/clients/1/contacts/1');

    const contact = getDb().contacts.find((c) => c.id === 1);
    expect(contact?.isActive).toBe(false);
    expect(contact?.isPrimary).toBe(false);
  });

  it('is scoped to the client, so another client’s contact id is 404', async () => {
    await expect(remove('/clients/2/contacts/1')).rejects.toMatchObject({ status: 404 });
    await expect(
      edit('/clients/2/contacts/1', { name: 'Hijacked', email: 'x@y.example' }),
    ).rejects.toMatchObject({ status: 404 });

    expect(getDb().contacts.find((c) => c.id === 1)?.name).toBe('Sara Kapoor');
  });

  /**
   * Single-writer. MySQL has no partial unique index, so this rule exists only
   * in the service — and here, which is what the frontend develops against.
   */
  it('demotes the previous primary when another is promoted', async () => {
    await edit('/clients/1/contacts/2', {
      name: 'Dev Patel', email: 'dev@acme.example', isPrimary: true,
    });

    const primaries = getDb().contacts.filter((c) => c.clientId === 1 && c.isPrimary);
    expect(primaries.map((c) => c.id)).toEqual([2]);
  });

  /**
   * Unique within the client, case-insensitively — agreeing with
   * `utf8mb4_0900_ai_ci`. Across clients it stays legal, which is why
   * `ix_client_contacts_email` is deliberately not unique and why D-039
   * disambiguates inbound mail on `website_domain`.
   */
  it('refuses a duplicate email at the same client and allows it at another', async () => {
    await expect(
      post('/clients/1/contacts', { name: 'Impostor', email: 'SARA@ACME.EXAMPLE' }),
    ).rejects.toMatchObject({ status: 409 });

    const elsewhere = await post<Envelope<Row>>('/clients/2/contacts', {
      name: 'Sara Kapoor', email: 'sara@acme.example',
    });
    expect(elsewhere.data.id).toBeGreaterThan(0);
  });

  /** A removal frees the address — somebody leaves and their replacement inherits it. */
  it('lets a removed contact’s email be used again', async () => {
    await remove('/clients/1/contacts/2');

    const successor = await post<Envelope<Row>>('/clients/1/contacts', {
      name: 'Successor', email: 'dev@acme.example',
    });
    expect(successor.data.name).toBe('Successor');
  });

  /**
   * The body is the whole representation, and `isActive` is not part of it — an
   * edit must not resurrect a removed contact. B-017 had to pin exactly this
   * between `project_members`' two writers with a named regression test.
   */
  it('cannot resurrect a removed contact through an edit', async () => {
    await remove('/clients/1/contacts/2');

    await edit('/clients/1/contacts/2', {
      name: 'Dev Patel', email: 'dev@acme.example', isActive: true,
    });

    expect(getDb().contacts.find((c) => c.id === 2)?.isActive).toBe(false);
  });

  /** The one default that is not false — §11 names the client contact on client mail. */
  it('opts a new contact into notifications unless told otherwise', async () => {
    const added = await post<Envelope<Row>>('/clients/2/contacts', {
      name: 'Priya Nair', email: 'priya@northwind.example',
    });

    expect(added.data.notificationOptIn).toBe(true);
    expect(added.data.isPrimary).toBe(false);
    expect(added.data.portalAccess).toBe(false);
  });

  /**
   * B-028 · this mock was the loosest of the four things that answered "is
   * this a valid email?" — `email.includes('@')`. So `sara@acme` was accepted
   * under `npm run dev` and refused by `ClientContactService` against a real
   * MySQL: right in development, wrong in production, which is the worst way
   * round for a rule to be. The mock now applies `@/lib/email`, which is the
   * browser's copy of the server's `EmailFormat`.
   */
  it('refuses an address with no dotted TLD, which includes(“@”) accepted', async () => {
    await expect(
      post('/clients/2/contacts', { name: 'Priya Nair', email: 'priya@northwind' }),
    ).rejects.toMatchObject({ status: 400 });
  });
});

/**
 * B-028 · blueprint line 948 — "at least one primary contact before the client
 * can be selected on a ticket", and the filter that decides whether the client
 * reaches that dropdown in the first place.
 */
describe('B-028 · client selectability', () => {
  interface ClientRow {
    id: number; clientCode: string; status: string; isActive: boolean;
    hasPrimaryContact: boolean; primaryContact: { id: number } | null;
  }

  const clients = async (query = '') =>
    (await get<Envelope<ClientRow[]>>(`/clients${query}`)).data;

  /**
   * The filter and the projection have to agree, and on the server they did
   * not: `isActive` was reported as `status <> 'INACTIVE'` and *filtered* as
   * `status = 'ACTIVE'`, so a prospect came back from `/clients` saying it was
   * active and did not come back from `/clients?isActive=true` — the exact call
   * S-19's client dropdown makes. This mock always had it right, and its own
   * comment claimed it was "the derivation the server uses". It was not.
   */
  it('includes prospects in ?isActive=true, and only INACTIVE in ?isActive=false', async () => {
    const active = await clients('?isActive=true&limit=200');
    const inactive = await clients('?isActive=false&limit=200');

    expect(active.map((c) => c.clientCode)).toContain('KESTREL');
    expect(active.every((c) => c.status !== 'INACTIVE')).toBe(true);
    expect(inactive.every((c) => c.status === 'INACTIVE')).toBe(true);
  });

  /** The gate, on the list row — which is what the ticket form renders. */
  it('reports the gate on every row, not only on the detail', async () => {
    const rows = await clients('?limit=200');

    expect(rows.find((c) => c.clientCode === 'ACME')?.hasPrimaryContact).toBe(true);
    // Kestrel is a prospect with no contacts at all.
    expect(rows.find((c) => c.clientCode === 'KESTREL')?.hasPrimaryContact).toBe(false);
  });

  /**
   * The half a fixture of all-live contacts could never have caught. Removal
   * deactivates (B-027 — `tickets.client_contact_id` has no cascade), so a
   * client whose only primary has left must read as having none: they cannot
   * be reached, and the mock was matching on `isPrimary` alone.
   */
  it('stops counting a primary contact once they are removed', async () => {
    expect((await clients('?limit=200')).find((c) => c.clientCode === 'ACME')
      ?.hasPrimaryContact).toBe(true);

    await http<void>({ url: '/clients/1/contacts/1', method: 'DELETE' });

    const acme = (await clients('?limit=200')).find((c) => c.clientCode === 'ACME');
    expect(acme?.hasPrimaryContact).toBe(false);
    expect(acme?.primaryContact ?? null).toBeNull();
  });

  /** The three optional addresses on the client form, on the server's rule. */
  it('refuses a client whose support email has no dotted TLD', async () => {
    await expect(
      post('/clients', { clientCode: 'NEWCO', name: 'Newco Ltd', supportEmail: 'desk@newco' }),
    ).rejects.toMatchObject({ status: 400 });
  });
});
