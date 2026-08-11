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
