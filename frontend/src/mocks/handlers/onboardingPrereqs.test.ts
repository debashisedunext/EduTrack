import { describe, expect, it } from 'vitest';

/**
 * A-118 · mock-handler tests for the prerequisites layer.
 *
 * The gate is the thing worth testing. Every journey in the module waits behind
 * it, plan §5.3 gives it exactly one valve, and the failure modes are all
 * silent: a gate that opens one task early looks identical to one that opened
 * correctly, and nothing downstream can tell.
 *
 * Acme (client 2) is the fixture with a locked gate — one mandatory task
 * `SUBMITTED`, one mandatory and two optional still `PENDING`. Acme owns
 * journey 3, the one seeded `gateStatus: 'LOCKED'`, so opening the gate here is
 * observable on the journey rather than only on the checklist. Which client
 * that is matters and is easy to get wrong: journey 2 belongs to Northwind
 * despite the numbering, and asserting against the wrong one produces a test
 * that fails for a reason nothing on screen would explain.
 */

const BASE = '/api/v1';

async function get(path: string) {
  const res = await fetch(`${BASE}${path}`);
  return { status: res.status, data: await res.json().catch(() => null) };
}

async function send(method: string, path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, data: await res.json().catch(() => null) };
}

const post = (p: string, b?: unknown) => send('POST', p, b);
const patch = (p: string, b?: unknown) => send('PATCH', p, b);

/** Clear every outstanding task on Acme except the one named. */
async function clearAcmeExcept(keepId: number) {
  const { data } = await get('/onboarding/clients/2/prereqs');
  for (const t of data.data.tasks) {
    if (t.id === keepId || t.status === 'VERIFIED' || t.status === 'SKIPPED') continue;
    if (t.status === 'PENDING') await post(`/onboarding/prereq-tasks/${t.id}/submit`);
    if (t.isMandatory) {
      await post(`/onboarding/prereq-tasks/${t.id}/verify`);
    } else {
      await post(`/onboarding/prereq-tasks/${t.id}/skip`, { reason: 'Not needed for this client.' });
    }
  }
}

/**
 * `clearAcmeExcept` walks the checklist one request at a time, so the two
 * tests that drive the gate all the way open cost ten-odd MSW round trips
 * before their first assertion. That sits just under vitest's 5s default
 * alone and just over it in a full run, where the workers compete — a
 * timeout, never a wrong answer. Stated here rather than per call so the
 * number is one decision instead of two.
 */
const GATE_WALK_TIMEOUT = 20_000;

describe('A-118 · the prerequisite gate', () => {
  it('reports a locked gate with its mandatory progress', async () => {
    const { status, data } = await get('/onboarding/clients/2/prereqs');
    expect(status).toBe(200);
    expect(data.data.gateStatus).toBe('LOCKED');
    expect(data.data.status).toBe('IN_PROGRESS');
    expect(data.data.mandatoryTotal).toBe(3);
    expect(data.data.mandatoryVerified).toBe(1);
    // Two optional tasks still hold the gate even once the mandatory bar is
    // full — the number a progress bar alone would not show.
    expect(data.data.optionalOutstanding).toBe(2);
  });

  it('does not open the gate while anything is outstanding', async () => {
    const { status, data } = await post('/onboarding/prereq-tasks/112/verify');
    expect(status).toBe(200);
    expect(data.data.task.status).toBe('VERIFIED');
    expect(data.data.gateOpened).toBe(false);
    expect(data.data.gateStatus).toBe('LOCKED');
    expect(data.data.openedJourneyIds).toEqual([]);
  });

  it('opens the gate on the last transition, and starts the locked journey', async () => {
    await clearAcmeExcept(113);
    await post('/onboarding/prereq-tasks/113/submit');

    const { status, data } = await post('/onboarding/prereq-tasks/113/verify');
    expect(status).toBe(200);
    expect(data.data.gateOpened).toBe(true);
    expect(data.data.gateStatus).toBe('OPEN');
    expect(data.data.openedJourneyIds).toContain(3);

    // The journey itself moved, not only the checklist's own header.
    const journey = await get('/onboarding/journeys/3');
    expect(journey.data.data.gateStatus).toBe('OPEN');
  }, GATE_WALK_TIMEOUT);

  it('reports gateOpened exactly once — a screen cannot infer it from gateStatus', async () => {
    await clearAcmeExcept(113);
    await post('/onboarding/prereq-tasks/113/submit');
    const first = await post('/onboarding/prereq-tasks/113/verify');
    expect(first.data.data.gateOpened).toBe(true);

    // A second transition on an already-open gate still reads OPEN, which is
    // why `gateOpened` exists: a screen refreshing whenever the gate reads open
    // would refresh forever. An optional ad-hoc task gives us that transition
    // without re-locking anything — only a mandatory addition does that.
    const added = await post('/onboarding/clients/2/prereq-tasks', {
      title: 'Post-gate courtesy check', tatDays: 2, isMandatory: false,
    });
    const { data } = await post(`/onboarding/prereq-tasks/${added.data.data.id}/skip`, {
      reason: 'Handled on the kickoff call.',
    });
    expect(data.data.gateStatus).toBe('OPEN');
    expect(data.data.gateOpened).toBe(false);
  }, GATE_WALK_TIMEOUT);

  it('refuses to skip a mandatory task, whoever is asking', async () => {
    const { status, data } = await post('/onboarding/prereq-tasks/113/skip', { reason: 'Client is slow.' });
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/ob-prereq-mandatory-not-skippable');
  });

  it('has no route that opens the gate directly', async () => {
    // The absence is the guarantee — plan §5.3's "no open gate anyway"
    // override. A 501 from the catch-all means nothing is mocked here, and the
    // contract declares no such operation for anything to be mocked against.
    const { status } = await post('/onboarding/clients/2/gate');
    expect(status).toBe(501);
  });
});

describe('A-118 · the submission loop', () => {
  it('refuses to verify a task that was never submitted', async () => {
    const { status, data } = await post('/onboarding/prereq-tasks/113/verify');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/ob-prereq-not-verifiable');
  });

  it('returns a submission to PENDING with the reason on the thread', async () => {
    const { status, data } = await post('/onboarding/prereq-tasks/112/return', {
      comment: 'The named contact is not on the agreement.',
    });
    expect(status).toBe(200);
    expect(data.data.status).toBe('PENDING');

    const comments = await get('/onboarding/prereq-tasks/112/comments');
    expect(comments.data.data).toHaveLength(1);
    expect(comments.data.data[0].isSystem).toBe(true);
    expect(comments.data.data[0].body).toContain('not on the agreement');
  });

  it('requires a reason to return', async () => {
    const { status } = await post('/onboarding/prereq-tasks/112/return', { comment: '  ' });
    expect(status).toBe(400);
  });

  it('does not reset the clock on a return — a bad submission buys no extension', async () => {
    const before = await get('/onboarding/prereq-tasks/112');
    await post('/onboarding/prereq-tasks/112/return', { comment: 'Wrong format.' });
    const after = await get('/onboarding/prereq-tasks/112');
    expect(after.data.data.dueAt).toBe(before.data.data.dueAt);
  });

  it('records every transition in an append-only history', async () => {
    await post('/onboarding/prereq-tasks/113/submit');
    await post('/onboarding/prereq-tasks/113/verify');
    const { data } = await get('/onboarding/prereq-tasks/113/history');
    expect(data.data.map((h: { toStatus: string }) => h.toStatus)).toEqual(['SUBMITTED', 'VERIFIED']);
    expect(data.data.every((h: { isCorrection: boolean }) => h.isCorrection === false)).toBe(true);

    // No mutation verb exists on the path — CONVENTIONS §8.
    const res = await fetch(`${BASE}/onboarding/prereq-tasks/113/history`, { method: 'DELETE' });
    expect(res.status).toBe(501);
  });

  it('will not reword a settled task', async () => {
    const { status } = await patch('/onboarding/prereq-tasks/111', { title: 'Rewritten after the fact' });
    expect(status).toBe(422);
  });
});

describe('A-118 · ad-hoc tasks and the gate they can re-lock', () => {
  it('adds a task to one client only, marked as ad-hoc', async () => {
    const { status, data } = await post('/onboarding/clients/2/prereq-tasks', {
      title: 'Campus network survey', tatDays: 4, isMandatory: false,
    });
    expect(status).toBe(201);
    expect(data.data.isAdHoc).toBe(true);
    expect(data.data.templateTaskId).toBeNull();
    expect(data.data.status).toBe('PENDING');

    // The master is untouched — an ad-hoc task exists on this client alone.
    const master = await get('/onboarding/prereq-template');
    expect(master.data.data.tasks.map((t: { title: string }) => t.title))
      .not.toContain('Campus network survey');
  });

  it('re-locks a cleared gate when the new task is mandatory', async () => {
    const before = await get('/onboarding/clients/1/prereqs');
    expect(before.data.data.gateStatus).toBe('OPEN');

    await post('/onboarding/clients/1/prereq-tasks', {
      title: 'Signed data-processing addendum', tatDays: 3, isMandatory: true,
    });

    const after = await get('/onboarding/clients/1/prereqs');
    expect(after.data.data.gateStatus).toBe('LOCKED');
    expect(after.data.data.clearedAt).toBeNull();
  });

  it('leaves a cleared gate alone when the new task is optional', async () => {
    await post('/onboarding/clients/1/prereq-tasks', {
      title: 'Optional branding refresh', tatDays: 3, isMandatory: false,
    });
    const after = await get('/onboarding/clients/1/prereqs');
    expect(after.data.data.gateStatus).toBe('OPEN');
  });
});

describe('A-118 · the OB-14 master', () => {
  it('serves the active version with its mandatory count', async () => {
    const { status, data } = await get('/onboarding/prereq-template');
    expect(status).toBe(200);
    expect(data.data.version).toBe(1);
    expect(data.data.isActive).toBe(true);
    expect(data.data.isDraft).toBe(false);
    expect(data.data.mandatoryCount).toBe(3);
    expect(data.data.tasks).toHaveLength(5);
  });

  it('refuses to edit a published version', async () => {
    const { status, data } = await patch('/onboarding/prereq-template-tasks/1', { tatDays: 9 });
    expect(status).toBe(409);
    expect(data.type).toBe('https://edutrack/errors/ob-prereq-published');
  });

  it('clones into a draft without touching the source version', async () => {
    const { status, data } = await post('/onboarding/prereq-template/revisions');
    expect(status).toBe(201);
    expect(data.data.version).toBe(2);
    expect(data.data.isDraft).toBe(true);
    expect(data.data.tasks).toHaveLength(5);

    const source = await get('/onboarding/prereq-template?version=1');
    expect(source.data.data.isActive).toBe(true);
    expect(source.data.data.tasks[0].tatDays).toBe(3);
  });

  it('allows only one draft at a time', async () => {
    await post('/onboarding/prereq-template/revisions');
    const { status, data } = await post('/onboarding/prereq-template/revisions');
    expect(status).toBe(409);
    expect(data.type).toBe('https://edutrack/errors/ob-prereq-draft-exists');
  });

  it('refuses to publish a draft with nothing mandatory', async () => {
    await post('/onboarding/prereq-template/revisions');
    const draft = await get('/onboarding/prereq-template?version=2');
    for (const t of draft.data.data.tasks.filter((x: { isMandatory: boolean }) => x.isMandatory)) {
      await send('DELETE', `/onboarding/prereq-template-tasks/${t.id}`);
    }
    const { status } = await post('/onboarding/prereq-template/publish');
    // A checklist with nothing mandatory clears its own gate at boarding, so
    // the gate would look present while doing nothing.
    expect(status).toBe(422);
  });

  it('publishes a draft and retires the version it supersedes', async () => {
    await post('/onboarding/prereq-template/revisions');
    const { status, data } = await post('/onboarding/prereq-template/publish');
    expect(status).toBe(200);
    expect(data.data.version).toBe(2);
    expect(data.data.isActive).toBe(true);
    expect(data.data.isDraft).toBe(false);

    const old = await get('/onboarding/prereq-template?version=1');
    expect(old.data.data.isActive).toBe(false);
  });

  it('leaves boarded clients on the version they were given', async () => {
    await post('/onboarding/prereq-template/revisions');
    await post('/onboarding/prereq-template/publish');
    const { data } = await get('/onboarding/clients/2/prereqs');
    expect(data.data.templateVersion).toBe(1);
  });

  it('refuses a partial reorder', async () => {
    await post('/onboarding/prereq-template/revisions');
    const draft = await get('/onboarding/prereq-template?version=2');
    const ids = draft.data.data.tasks.map((t: { id: number }) => t.id);
    const { status } = await send('PUT', '/onboarding/prereq-template/tasks/order', {
      taskIds: ids.slice(0, 2),
    });
    expect(status).toBe(400);
  });
});
