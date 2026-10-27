import { beforeEach, describe, expect, it } from 'vitest';

import type { Db, ObSignoffRow } from '../db';
import { getDb } from '../db';
import { portalAccount, portalObClientId } from './portalOnboarding';

/**
 * C-121 · the portal's onboarding side, CP-01 to CP-04.
 *
 * As with `portal.test.ts`, these assert the scope and the transitions, not
 * the payloads field by field. The risk on this surface is a row that should
 * never have been served — another client's step, another client's sign-off —
 * and a write attributed to a member of staff when a SPOC made it.
 *
 * Each test builds the state it needs rather than trusting the fixture to
 * hold it, so a test cannot pass by finding nothing to check.
 */

let db: Db;
let clientId: number;

beforeEach(() => {
  db = getDb();
  clientId = portalObClientId(db)!;
  expect(clientId).not.toBeNull();
});

const api = (path: string, init?: RequestInit) => fetch(`/api/v1/portal${path}`, init);
const post = (path: string, body: unknown) =>
  api(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
const dataOf = async <T,>(res: Response, status = 200): Promise<T> => {
  expect(res.status).toBe(status);
  return ((await res.json()) as { data: T }).data;
};

/** A PENDING sign-off that belongs to this client, made from the seeded one. */
function myPendingSignoff(): ObSignoffRow {
  const s = db.obSignoffs.find((x) => x.status === 'PENDING')!;
  const client = db.obClients.find((c) => c.id === clientId)!;
  const journey = client.journeys[0];
  const step = journey.steps.find((x) => x.status !== 'DONE' && x.status !== 'SKIPPED') ?? journey.steps[0];
  s.obClientId = clientId;
  s.journeyId = journey.id;
  s.stepId = step.id;
  s.sentToContactId = client.contacts[0].id;
  return s;
}

describe('CP-03 · home', () => {
  it('serves this client’s prerequisites and journeys, and nobody else’s', async () => {
    const home = await dataOf<{ obClientId: number; journeys: { id: number }[]; prereqs: { obClientId: number } }>(
      await api('/onboarding/home'),
    );
    const client = db.obClients.find((c) => c.id === clientId)!;
    expect(home.obClientId).toBe(clientId);
    expect(home.prereqs.obClientId).toBe(clientId);
    expect(home.journeys.map((j) => j.id).sort()).toEqual(client.journeys.map((j) => j.id).sort());
  });

  it('answers 401 with no active account, rather than somebody else’s data', async () => {
    portalAccount(db)!.isActive = false;
    expect((await api('/onboarding/home')).status).toBe(401);
  });
});

describe('CP-03 · escalations', () => {
  it('raises once on a running service and returns the same one after that', async () => {
    const client = db.obClients.find((c) => c.id === clientId)!;
    const step = client.journeys[0].steps[0];
    step.status = 'IN_PROGRESS';

    const first = await dataOf<{ id: number; isNew: boolean }>(
      await post(`/onboarding/steps/${step.id}/escalate`, { comment: 'Please expedite.' }), 201,
    );
    expect(first.isNew).toBe(true);

    const again = await dataOf<{ id: number; isNew: boolean }>(
      await post(`/onboarding/steps/${step.id}/escalate`, { comment: 'Still waiting.' }),
    );
    expect(again).toMatchObject({ id: first.id, isNew: false });
  });

  it('refuses a service that is not running, and 404s another client’s step', async () => {
    const client = db.obClients.find((c) => c.id === clientId)!;
    const pending = client.journeys[0].steps[0];
    pending.status = 'PENDING';
    expect((await post(`/onboarding/steps/${pending.id}/escalate`, { comment: 'x' })).status).toBe(422);

    const theirs = db.obClients.find((c) => c.id !== clientId)!.journeys[0].steps[0];
    expect((await post(`/onboarding/steps/${theirs.id}/escalate`, { comment: 'x' })).status).toBe(404);
  });
});

describe('CP-02 · sign-offs', () => {
  it('lists only this client’s sign-offs and 404s another client’s by id', async () => {
    const mine = myPendingSignoff();
    const theirs = db.obSignoffs.find((s) => s.id !== mine.id)!;
    theirs.obClientId = clientId + 1000;

    const rows = await dataOf<{ id: number }[]>(await api('/onboarding/signoffs'));
    expect(rows.map((r) => r.id)).toContain(mine.id);
    expect(rows.map((r) => r.id)).not.toContain(theirs.id);
    expect((await api(`/onboarding/signoffs/${theirs.id}`)).status).toBe(404);
  });

  it('accepts once, records who signed, and refuses a second decision', async () => {
    const s = myPendingSignoff();
    const decision = await dataOf<{ status: string; signedName: string; hasCertificate: boolean }>(
      await post(`/onboarding/signoffs/${s.id}/accept`, { acceptedName: 'Deepa Kulkarni', note: 'All good.' }),
    );
    expect(decision).toMatchObject({ status: 'SIGNED', signedName: 'Deepa Kulkarni', hasCertificate: true });
    expect(s.acceptanceNote).toBe('All good.');

    expect((await post(`/onboarding/signoffs/${s.id}/object`, { note: 'Changed my mind.' })).status).toBe(422);
  });

  it('offers the survey only after a signed go-live', async () => {
    const s = myPendingSignoff();
    expect((await post(`/onboarding/signoffs/${s.id}/csat`, { score: 5 })).status).toBe(422);

    s.kind = 'GO_LIVE';
    await dataOf(await post(`/onboarding/signoffs/${s.id}/accept`, { acceptedName: 'Deepa' }));
    expect((await post(`/onboarding/signoffs/${s.id}/csat`, { score: 5, comment: 'Smooth.' })).status).toBe(204);
    expect(s.csatScore).toBe(5);
    expect((await post(`/onboarding/signoffs/${s.id}/csat`, { score: 4 })).status).toBe(422);
  });
});

describe('CP-04 · prerequisites', () => {
  function myTask() {
    const task = db.obClientPrereqTasks.find((t) => t.obClientId === clientId)!;
    task.status = 'PENDING';
    return task;
  }

  it('submits through the portal and attributes it to the SPOC', async () => {
    const task = myTask();
    const dto = await dataOf<{ status: string; submittedVia: string }>(
      await post(`/onboarding/prereq-tasks/${task.id}/submit`, { note: 'Sent.' }),
    );
    expect(dto).toMatchObject({ status: 'SUBMITTED', submittedVia: 'PORTAL' });
    const history = db.obPrereqHistory.find((h) => h.prereqTaskId === task.id && h.toStatus === 'SUBMITTED')!;
    expect(history.actorType).toBe('CLIENT');
    expect(history.clientActorId).not.toBeNull();
  });

  it('posts a comment as the client and lists it back', async () => {
    const task = myTask();
    const posted = await dataOf<{ id: number; authorType: string; clientAuthor: { id: number } | null }>(
      await post(`/onboarding/prereq-tasks/${task.id}/comments`, { body: 'Uploaded the PAN scan.' }), 201,
    );
    expect(posted.authorType).toBe('CLIENT');
    expect(posted.clientAuthor).not.toBeNull();

    const rows = await dataOf<{ id: number }[]>(await api(`/onboarding/prereq-tasks/${task.id}/comments`));
    expect(rows.map((r) => r.id)).toContain(posted.id);
  });

  /**
   * The upload's body is not exercised here, and that is vitest's limitation
   * rather than a choice: under jsdom `FormData` and `File` are jsdom's while
   * `fetch` is Node's, and no genuine multipart body ever reaches a handler —
   * `onboarding.test.ts` (B-107) and `handlers/rest.ts` record the same gap.
   * What is asserted is the part that runs before the body is read: the
   * route answers 404 for another client's task, like every other route here.
   */
  it('404s another client’s task on every route, the upload included', async () => {
    const theirs = db.obClientPrereqTasks.find((t) => t.obClientId !== clientId)!;
    expect((await api(`/onboarding/prereq-tasks/${theirs.id}`)).status).toBe(404);
    expect((await api(`/onboarding/prereq-tasks/${theirs.id}/comments`)).status).toBe(404);
    expect((await post(`/onboarding/prereq-tasks/${theirs.id}/submit`, {})).status).toBe(404);
    expect((await post(`/onboarding/prereq-tasks/${theirs.id}/comments`, { body: 'x' })).status).toBe(404);
    expect((await api(`/onboarding/prereq-tasks/${theirs.id}/attachments`, { method: 'POST' })).status).toBe(404);
  });
});

describe('CP-01 · credentials', () => {
  it('signs in with the dev password and nothing else', async () => {
    const account = portalAccount(db)!;
    expect((await post('/auth/login', { username: account.username, password: 'wrong' })).status).toBe(401);
    expect((await post('/auth/login', { username: 'nobody', password: 'Demo-Passw0rd!' })).status).toBe(401);

    const result = await dataOf<{ accessToken: string; client: { hasOnboarding: boolean } }>(
      await post('/auth/login', { username: account.username, password: 'Demo-Passw0rd!' }),
    );
    expect(result.accessToken).toBeTruthy();
    expect(result.client.hasOnboarding).toBe(true);
  });

  it('reports a locked account only once the password is right', async () => {
    const account = portalAccount(db)!;
    account.lockedUntil = new Date(Date.now() + 3_600_000).toISOString();
    expect((await post('/auth/login', { username: account.username, password: 'Demo-Passw0rd!' })).status).toBe(423);
  });

  it('describes a live credential link, spends it on redeem, and 410s it afterwards', async () => {
    const account = portalAccount(db)!;
    const token = `credential-${account.id}`;
    // Seeded as already signed in after the credential was sent — spent.
    expect((await api(`/auth/credential/${token}`)).status).toBe(410);

    account.lastLoginAt = null;
    const link = await dataOf<{ username: string }>(await api(`/auth/credential/${token}`));
    expect(link.username).toBe(account.username);

    expect((await post(`/auth/credential/${token}`, { password: 'short' })).status).toBe(400);
    expect((await post(`/auth/credential/${token}`, { password: 'Str0ng-enough!' })).status).toBe(204);
    expect((await api(`/auth/credential/${token}`)).status).toBe(410);
    expect((await api('/auth/credential/credential-999')).status).toBe(410);
  });
});
