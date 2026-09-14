import { describe, expect, it } from 'vitest';
import { getDb } from '../db';

/**
 * Mock-handler tests for `/onboarding/projects`.
 *
 * <h2>What is worth asserting here, and what is not</h2>
 *
 * Not the arithmetic. `delayedByDays` and `tentativeCompletion` are the
 * server's working-calendar figures and the mock only reproduces their *shape*;
 * pinning an exact number here would pin the mock's simplification rather than
 * the contract, and it would break the day somebody adds a holiday fixture.
 *
 * What is worth asserting is every place the shape carries meaning a screen
 * branches on: that a delay is `null` rather than `0` when nothing is late,
 * that a locked project reports no current stage, that the stage roll-up folds
 * two module services' Configuration into one stage rather than two, and that
 * the create writes the purchase row without which the project's own ribbon
 * would 404.
 *
 * <h2>The fixtures these lean on</h2>
 *
 * Projects are derived from the client fixtures' purchases, so every
 * (client, product) pair has one. GreenValley (client 1) has two purchases and
 * therefore two projects; Little Scholars (client 7) is the one whose gate is
 * still locked, which is the only place "no current stage, no delay" can be
 * observed.
 */

const BASE = '/api/v1';

interface ProjectRow {
  id: number;
  name: string;
  client: { id: number; name: string; clientCode: string | null; city: string | null };
  product: { id: number; code: string; name: string };
  startDate: string;
  status: string;
  gateStatus: string;
  currentStage: string | null;
  stagesComplete: number;
  stagesTotal: number;
  journeyCount: number;
  delayedByDays: number | null;
  tentativeCompletion: string | null;
  totalTatDays: number;
}

interface ProjectDetail extends ProjectRow {
  stages: {
    stageKey: number; name: string; taskCount: number; tasksOutstanding: number;
    isComplete: boolean; isCurrent: boolean;
  }[];
  moduleServices: { journeyId: number; templateId: number; serviceName: string }[];
}

async function list(query = ''): Promise<ProjectRow[]> {
  const res = await fetch(`${BASE}/onboarding/projects${query}`);
  return (await res.json()).data;
}

async function get(id: number): Promise<{ status: number; data: ProjectDetail }> {
  const res = await fetch(`${BASE}/onboarding/projects/${id}`);
  return { status: res.status, data: (await res.json().catch(() => null))?.data };
}

async function send(method: string, path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}

describe('the projects grid', () => {
  it('derives one project per purchased product', async () => {
    const rows = await list();
    const db = getDb();

    const greenValley = rows.filter((p) => p.client.id === 1);
    const purchases = db.obClients.find((c) => c.id === 1)!.applications.length;
    expect(greenValley).toHaveLength(purchases);

    // The name the migration derives for a row nobody has renamed.
    expect(greenValley[0].name).toContain('GreenValley');
    expect(greenValley[0].client.clientCode).toBe('GVI-001');
  });

  it('reports no delay and no current stage while the gate is locked', async () => {
    const locked = (await list()).find((p) => p.gateStatus === 'LOCKED');
    expect(locked).toBeDefined();

    // Null rather than zero, and null rather than "—": a locked project has no
    // clock running, so the question does not apply. Zero would be a claim that
    // it is on time today.
    expect(locked!.delayedByDays).toBeNull();
    expect(locked!.currentStage).toBeNull();
  });

  it('never reports a delay of zero', async () => {
    const rows = await list();
    expect(rows.every((p) => p.delayedByDays == null || p.delayedByDays >= 1)).toBe(true);
  });

  it('reports no delay for a project that is not running', async () => {
    const db = getDb();
    const project = db.obProjects[0];
    project.status = 'ON_HOLD';
    project.statusReason = 'Client paused pending budget approval';

    const row = (await list()).find((p) => p.id === project.id)!;
    // A held project has a clock somebody stopped on purpose. Counting days
    // against it would report a growing delay on work nobody is doing.
    expect(row.delayedByDays).toBeNull();
  });

  it('filters by client, product and status', async () => {
    expect((await list('?clientId=1')).every((p) => p.client.id === 1)).toBe(true);
    expect((await list('?productId=1')).every((p) => p.product.id === 1)).toBe(true);
    expect((await list('?status=RUNNING')).every((p) => p.status === 'RUNNING')).toBe(true);
  });

  it('searches the project name and the client name, and nothing else', async () => {
    const byClient = await list('?q=greenvalley');
    expect(byClient.length).toBeGreaterThan(0);
    expect(byClient.every((p) => p.client.name.toLowerCase().includes('greenvalley'))).toBe(true);

    // Not the code: it is short and exact, and matching it would make a
    // three-letter search return rows by a key nobody typed.
    expect(await list('?q=GVI-001')).toHaveLength(0);
  });
});

describe('the stage roll-up', () => {
  it('folds two module services onto one implementation stage', async () => {
    const db = getDb();
    // A project whose product publishes more than one service — the case a
    // per-group count would report as twice as many stages as the master has.
    const project = db.obProjects.find(
      (p) =>
        db.obClients
          .find((c) => c.id === p.obClientId)!
          .journeys.filter((j) => j.productId === p.productId && j.archivedAt == null).length > 1,
    );
    if (!project) return; // no such fixture; the assertion below covers the general case

    const { data } = await get(project.id);
    const keys = data.stages.map((s) => s.stageKey);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('counts every task, including one whose template row it cannot resolve', async () => {
    const db = getDb();
    const project = db.obProjects.find((p) => p.obClientId === 1)!;
    const { data } = await get(project.id);

    const journeys = db.obClients
      .find((c) => c.id === 1)!
      .journeys.filter((j) => j.productId === project.productId && j.archivedAt == null);
    const steps = journeys.reduce((total, j) => total + j.steps.length, 0);

    // The numerator and the denominator have to be computed over the same set.
    // Dropping an unresolvable task would leave a plausible-looking percentage
    // over the wrong total, which is worse than an obviously wrong one.
    expect(data.stages.reduce((total, s) => total + s.taskCount, 0)).toBe(steps);
  });

  it('marks at most one stage current', async () => {
    const db = getDb();
    for (const project of db.obProjects) {
      const { data } = await get(project.id);
      expect(data.stages.filter((s) => s.isCurrent).length).toBeLessThanOrEqual(1);
    }
  });
});

describe('creating a project', () => {
  /** A client with nothing bought, so each create starts from a clean pair. */
  async function freshClient(code: string) {
    const { body } = await send('POST', '/onboarding/clients', {
      name: `Fixture ${code}`,
      clientCode: code,
      acknowledgeSimilarNames: true,
    });
    return body.data.id as number;
  }

  it('writes the purchase row, the project and one journey per checked service', async () => {
    const db = getDb();
    const clientId = await freshClient('NEW-101');
    const product = db.obProducts.find((p) => p.hasActiveTemplate)!;
    const services = db.obJourneyTemplates.filter((t) => t.productId === product.id && t.isActive);

    const { status, body } = await send('POST', '/onboarding/projects', {
      name: 'Fixture ERP Rollout',
      clientId,
      productId: product.id,
      startDate: '2026-09-15',
      moduleServiceIds: services.map((s) => s.id),
    });

    expect(status).toBe(201);
    expect(body.data.journeyCount).toBe(services.length);
    expect(body.data.moduleServices).toHaveLength(services.length);

    // The purchase row is not optional: journey reads are guarded on "did this
    // client buy this product", so a project without it would 404 its own
    // ribbon from the moment it was made.
    const client = db.obClients.find((c) => c.id === clientId)!;
    expect(client.applications.some((a) => a.productId === product.id)).toBe(true);

    // Locked, because this client's prerequisites have not cleared. Visible
    // plan, dead clock.
    expect(body.data.gateStatus).toBe('LOCKED');
    expect(body.data.delayedByDays).toBeNull();
  });

  it('creates only the services that were left checked', async () => {
    const db = getDb();
    const clientId = await freshClient('NEW-102');
    const product = db.obProducts.find(
      (p) => db.obJourneyTemplates.filter((t) => t.productId === p.id && t.isActive).length > 1,
    );
    if (!product) return;
    const services = db.obJourneyTemplates.filter((t) => t.productId === product.id && t.isActive);

    const { body } = await send('POST', '/onboarding/projects', {
      name: 'Half a rollout',
      clientId,
      productId: product.id,
      startDate: '2026-09-15',
      moduleServiceIds: [services[0].id],
    });

    expect(body.data.journeyCount).toBe(1);
    expect(body.data.moduleServices[0].templateId).toBe(services[0].id);
  });

  it('refuses a second project for the same client and product, and names the first', async () => {
    const db = getDb();
    const existing = db.obProjects[0];

    const { status, body } = await send('POST', '/onboarding/projects', {
      name: 'A second go',
      clientId: existing.obClientId,
      productId: existing.productId,
      startDate: '2026-09-15',
      moduleServiceIds: [1],
    });

    expect(status).toBe(409);
    expect(body.type).toContain('ob-project-duplicate');
    // The id rides along so the form can offer to open it, which is what
    // somebody who hit this almost always wanted.
    expect(body.existingProjectId).toBe(existing.id);
  });

  it('refuses a module service that is not an active service of the product', async () => {
    const db = getDb();
    const clientId = await freshClient('NEW-103');
    const product = db.obProducts.find((p) => p.hasActiveTemplate)!;

    const { status, body } = await send('POST', '/onboarding/projects', {
      name: 'Stale form',
      clientId,
      productId: product.id,
      startDate: '2026-09-15',
      moduleServiceIds: [999_999],
    });

    expect(status).toBe(409);
    expect(body.type).toContain('ob-project-unknown-module-service');
    expect(body.templateId).toBe(999_999);
  });

  it('refuses an empty selection', async () => {
    const clientId = await freshClient('NEW-104');
    const { status } = await send('POST', '/onboarding/projects', {
      name: 'Nothing to run',
      clientId,
      productId: 1,
      startDate: '2026-09-15',
      moduleServiceIds: [],
    });
    expect(status).toBe(400);
  });

  it('refuses to create a project whose journeys could never start', async () => {
    const db = getDb();
    for (const version of db.obPrereqVersions) version.isActive = false;
    const clientId = await freshClient('NEW-105');
    const product = db.obProducts.find((p) => p.hasActiveTemplate)!;
    const services = db.obJourneyTemplates.filter((t) => t.productId === product.id && t.isActive);

    const { status, body } = await send('POST', '/onboarding/projects', {
      name: 'Permanently locked',
      clientId,
      productId: product.id,
      startDate: '2026-09-15',
      moduleServiceIds: services.map((s) => s.id),
    });

    // Journeys instantiate LOCKED and only the checklist opens them, so this
    // project would hold journeys nothing could ever start.
    expect(status).toBe(422);
    expect(body.type).toContain('ob-client-no-prereq-master');
  });
});

describe('editing a project', () => {
  it('refuses COMPLETED', async () => {
    const db = getDb();
    const { status, body } = await send('PATCH', `/onboarding/projects/${db.obProjects[0].id}`, {
      name: db.obProjects[0].name,
      startDate: db.obProjects[0].startDate,
      status: 'COMPLETED',
    });
    expect(status).toBe(422);
    expect(body.type).toContain('ob-project-status-not-earned');
  });

  it('requires a reason for on hold and dropped', async () => {
    const db = getDb();
    const { status } = await send('PATCH', `/onboarding/projects/${db.obProjects[0].id}`, {
      name: db.obProjects[0].name,
      startDate: db.obProjects[0].startDate,
      status: 'ON_HOLD',
    });
    expect(status).toBe(400);
  });

  it('clears an implementor that is left out of the body', async () => {
    const db = getDb();
    const project = db.obProjects.find((p) => p.implementorUserId != null);
    if (!project) return;

    const { status } = await send('PATCH', `/onboarding/projects/${project.id}`, {
      name: project.name,
      startDate: project.startDate,
    });
    expect(status).toBe(200);
    // The whole representation, not a sparse patch — which is what makes
    // unassigning somebody possible at all.
    expect(project.implementorUserId).toBeNull();
  });

  it('404s an unknown project', async () => {
    expect((await get(999_999)).status).toBe(404);
  });
});
