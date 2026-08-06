import { describe, expect, it, beforeEach } from 'vitest';
import { getDb, resetDb } from './db';
import { http, ApiError } from '../api/http';

/**
 * The mock API is what Streams B and C build against for the next four weeks.
 * If it is wrong they build the wrong thing, and nobody finds out until the
 * real backend lands. So it gets tested like a real service.
 */

const get = <T,>(url: string) => http<T>({ url, method: 'GET' });
const post = <T,>(url: string, data?: unknown) => http<T>({ url, method: 'POST', data });

interface Envelope<T> { data: T; meta?: Record<string, unknown> }

beforeEach(() => resetDb());

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
    expect(created.data.ticketId).toMatch(/^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5}$/);
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
