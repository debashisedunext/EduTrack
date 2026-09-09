import { describe, expect, it } from 'vitest';

import { getDb } from '../db';

/**
 * A-118 · mock-handler tests for the OB-02/08/09/10/11/12/13 surfaces.
 *
 * Weighted towards the public sign-off routes, because those are the ones where
 * a permissive mock does real damage: a screen built against a mock that
 * distinguishes "wrong code" from "no such link", or that hands back the
 * client's name before the OTP is proved, is a screen that has to be rewritten
 * when it meets the server. The rest of the file covers the two rules that are
 * easiest to implement backwards — acceptance surviving a failed completion
 * gate, and the last administrator being irremovable.
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
const put = (p: string, b?: unknown) => send('PUT', p, b);

/** The fixture's one PENDING sign-off — Cambridge Heights' UAT step (617). */
const TOKEN = 'ob-signoff-demo-token-1';

async function verifiedSession(token = TOKEN) {
  await post('/public/onboarding/signoff/otp', { token });
  const { data } = await post('/public/onboarding/signoff/otp/verify', { token, otp: '123456' });
  return data.data.sessionToken as string;
}

describe('A-118 · the public sign-off surface', () => {
  it('says nothing about whether a link exists', async () => {
    const real = await post('/public/onboarding/signoff/otp', { token: TOKEN });
    const invented = await post('/public/onboarding/signoff/otp', { token: 'not-a-real-token' });
    // Byte-identical. Anything else is an enumeration oracle.
    expect(real.status).toBe(202);
    expect(invented.status).toBe(202);
    expect(real.data).toEqual(invented.data);
  });

  it('answers one generic body for every verification failure', async () => {
    await post('/public/onboarding/signoff/otp', { token: TOKEN });
    const wrongCode = await post('/public/onboarding/signoff/otp/verify', { token: TOKEN, otp: '000000' });
    const noSuchLink = await post('/public/onboarding/signoff/otp/verify', { token: 'nope', otp: '123456' });

    expect(wrongCode.status).toBe(401);
    expect(noSuchLink.status).toBe(401);
    // A caller who can tell these apart can enumerate links.
    expect(wrongCode.data.type).toBe(noSuchLink.data.type);
    expect(wrongCode.data.title).toBe(noSuchLink.data.title);
  });

  it('returns nothing about the client until the code is proved', async () => {
    const { status, data } = await post('/public/onboarding/signoff/otp', { token: TOKEN });
    expect(status).toBe(202);
    // A 202 with no body: the link alone proves possession of an email, not
    // identity, so it buys no information about who the client is.
    expect(data).toBeNull();

    const session = await verifiedSession();
    expect(session).toBeTruthy();
    const verified = await post('/public/onboarding/signoff/otp/verify', { token: TOKEN, otp: '123456' });
    expect(verified.data.data.obClientName).toBe('Cambridge Heights School');
  });

  it('never puts the token or the OTP on a staff response', async () => {
    const list = await get('/onboarding/signoffs');
    const serialised = JSON.stringify(list.data);
    expect(serialised).not.toContain(TOKEN);
    expect(serialised).not.toContain('otp');
    expect(serialised).not.toContain('tokenHash');

    const detail = await get('/onboarding/signoffs/1');
    expect(JSON.stringify(detail.data)).not.toContain(TOKEN);
  });

  it('locks out after five wrong codes, and the lockout survives the guesses', async () => {
    await post('/public/onboarding/signoff/otp', { token: TOKEN });
    for (let i = 0; i < 5; i += 1) {
      await post('/public/onboarding/signoff/otp/verify', { token: TOKEN, otp: '000000' });
    }
    // Even the right code is refused now — a counter that healed itself would
    // not be a lockout.
    const { status } = await post('/public/onboarding/signoff/otp/verify', { token: TOKEN, otp: '123456' });
    expect(status).toBe(401);
  });

  it('records the acceptance even when the completion gate refuses the step', async () => {
    // Trinity's blocked "Configuration & branding" (step 815) carries the
    // fixture's only Task List, with both mandatory items unanswered — the
    // completion gate's refusal case. No fixture sign-off points there, so the
    // test requests one through the staff route and reads its token from the
    // db, because the token deliberately appears on no wire response.
    await post('/onboarding/journeys/81/signoffs', {
      kind: 'STEP', stepId: 815, sentToContactId: 11,
    });
    const created = getDb().obSignoffs.find((s) => s.stepId === 815 && s.status === 'PENDING')!;

    const sessionToken = await verifiedSession(created.token);
    const { status, data } = await post('/public/onboarding/signoff/accept', {
      sessionToken, acceptedName: 'George Thomas',
    });

    expect(status).toBe(200);
    // PHASE-2-BUILD-PLAN §3 #4. The client did accept; they are not the ones
    // who left a checklist unanswered, so the signature is kept.
    expect(data.data.signoff.status).toBe('SIGNED');
    expect(data.data.signoff.signedIp).not.toBeNull();
    expect(data.data.stepCompleted).toBe(false);
    expect(data.data.gateFailures.length).toBeGreaterThan(0);

    // And the step did NOT complete.
    const step = await get('/onboarding/journey-steps/815');
    expect(step.data.data.status).not.toBe('DONE');
  });

  it('spends a session token once', async () => {
    const sessionToken = await verifiedSession();
    await post('/public/onboarding/signoff/accept', { sessionToken, acceptedName: 'Sana Qureshi' });
    const replay = await post('/public/onboarding/signoff/accept', { sessionToken, acceptedName: 'Sana Qureshi' });
    expect(replay.status).toBe(401);
  });

  it('reverts the service on an objection, and requires a reason', async () => {
    const sessionToken = await verifiedSession();
    const blank = await post('/public/onboarding/signoff/object', { sessionToken, note: '   ' });
    expect(blank.status).toBe(400);

    const { status, data } = await post('/public/onboarding/signoff/object', {
      sessionToken, note: 'The migrated ledger is missing two campuses.',
    });
    expect(status).toBe(200);
    expect(data.data.status).toBe('OBJECTED');

    const step = await get('/onboarding/journey-steps/617');
    expect(step.data.data.status).toBe('IN_PROGRESS');
  });

  it('offers CSAT only on a go-live sign-off', async () => {
    const sessionToken = await verifiedSession();
    const verified = await post('/public/onboarding/signoff/otp/verify', { token: TOKEN, otp: '123456' });
    expect(verified.data.data.csatOffered).toBe(false);

    const { status } = await post('/public/onboarding/signoff/csat', { sessionToken, score: 5 });
    expect(status).toBe(422);
  });

  // B-119 · fixture row 3 — Cambridge's go-live sign-off, unanswered.
  const GO_LIVE_TOKEN = 'ob-signoff-demo-token-3';

  it('offers CSAT on a go-live sign-off, and the same accepted session can still answer it', async () => {
    await post('/public/onboarding/signoff/otp', { token: GO_LIVE_TOKEN });
    const verified = await post('/public/onboarding/signoff/otp/verify', { token: GO_LIVE_TOKEN, otp: '123456' });
    expect(verified.data.data.csatOffered).toBe(true);

    const sessionToken = verified.data.data.sessionToken as string;
    await post('/public/onboarding/signoff/accept', { sessionToken, acceptedName: 'Sana Qureshi' });

    // The accepted session is used already — this is the fact B-119 turns on.
    const csat = await post('/public/onboarding/signoff/csat', { sessionToken, score: 4, comment: 'Smooth.' });
    expect(csat.status).toBe(200);

    const db = getDb();
    const signoff = db.obSignoffs.find((s) => s.token === GO_LIVE_TOKEN);
    expect(signoff?.csatScore).toBe(4);
    expect(signoff?.csatComment).toBe('Smooth.');
  });

  it('refuses a second survey on the same go-live', async () => {
    const sessionToken = await verifiedSession(GO_LIVE_TOKEN);
    await post('/public/onboarding/signoff/accept', { sessionToken, acceptedName: 'Sana Qureshi' });
    await post('/public/onboarding/signoff/csat', { sessionToken, score: 5 });

    const again = await post('/public/onboarding/signoff/csat', { sessionToken, score: 1 });
    expect(again.status).toBe(422);
  });
});

describe('A-118 · sign-off, staff side', () => {
  it('mints a new token on resend, killing the old link', async () => {
    const before = await verifiedSession();
    expect(before).toBeTruthy();

    await post('/onboarding/signoffs/1/resend');
    // The old token is dead — two live links to one decision would leave
    // nothing to say which was used.
    const { status } = await post('/public/onboarding/signoff/otp/verify', { token: TOKEN, otp: '123456' });
    expect(status).toBe(401);
  });

  it('refuses a second live request for the same service', async () => {
    const { status, data } = await post('/onboarding/journeys/61/signoffs', {
      kind: 'STEP', stepId: 617, sentToContactId: 9,
    });
    expect(status).toBe(409);
    expect(data.type).toBe('https://edutrack/errors/ob-signoff-already-pending');
  });

  it('refuses a go-live while a service is unfinished', async () => {
    const { status, data } = await post('/onboarding/journeys/61/signoffs', {
      kind: 'GO_LIVE', sentToContactId: 9,
    });
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/ob-signoff-journey-incomplete');
  });

  it('holds kind and stepId to the database’s own pairing rule', async () => {
    const stepless = await post('/onboarding/journeys/61/signoffs', { kind: 'STEP', sentToContactId: 9 });
    expect(stepless.status).toBe(400);
    const stepped = await post('/onboarding/journeys/61/signoffs', { kind: 'GO_LIVE', stepId: 617, sentToContactId: 9 });
    expect(stepped.status).toBe(400);
  });

  it('has no certificate until somebody has signed', async () => {
    expect((await get('/onboarding/signoffs/1/certificate')).status).toBe(404);
    const res = await fetch(`${BASE}/onboarding/signoffs/2/certificate`);
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('application/pdf');
  });
});

describe('A-118 · escalations', () => {
  it('keeps an acknowledged rung open', async () => {
    const { data } = await post('/onboarding/escalations/1/acknowledge');
    expect(data.data.acknowledgedAt).not.toBeNull();
    expect(data.data.resolvedAt).toBeNull();

    // Acknowledging says somebody has seen it, not that anything is fixed.
    const open = await get('/onboarding/escalations?state=OPEN');
    expect(open.data.data.map((e: { id: number }) => e.id)).toContain(1);
  });

  it('does not move the timestamp on a second acknowledgement', async () => {
    const first = await post('/onboarding/escalations/1/acknowledge');
    const second = await post('/onboarding/escalations/1/acknowledge');
    expect(second.data.data.acknowledgedAt).toBe(first.data.data.acknowledgedAt);
  });

  it('resolves one rung without resolving the others', async () => {
    await post('/onboarding/escalations/1/resolve', { note: 'Migration unblocked.' });
    const open = await get('/onboarding/escalations?state=OPEN');
    const ids = open.data.data.map((e: { id: number }) => e.id);
    // L2 went to a different person; closing L1 would tell them it was handled
    // when nobody told them it was.
    expect(ids).not.toContain(1);
    expect(ids).toContain(2);
  });

  it('shows a rung the matrix resolved nobody for', async () => {
    const { data } = await get('/onboarding/escalations?state=OPEN');
    const orphan = data.data.find((e: { id: number }) => e.id === 2);
    // Suppressing it would make a misconfigured matrix look like a working one.
    expect(orphan.escalatedTo).toBeNull();
  });

  it('requires a note to resolve, on both kinds', async () => {
    expect((await post('/onboarding/escalations/1/resolve', { note: ' ' })).status).toBe(400);
    expect((await post('/onboarding/client-escalations/1/resolve', { note: ' ' })).status).toBe(400);
  });

  it('names an external contact, not a user, as the raiser', async () => {
    const { data } = await get('/onboarding/client-escalations');
    expect(data.data[0].raisedByContact.name).toBe('Arjun Shetty');
    expect(data.data[0].raisedByContact.email).toContain('@');
  });
});

describe('A-118 · module access (OB-08)', () => {
  it('hides revoked grants by default and returns them on request', async () => {
    const live = await get('/onboarding/module-access');
    expect(live.data.data.every((g: { isLive: boolean }) => g.isLive)).toBe(true);

    const all = await get('/onboarding/module-access?includeRevoked=true');
    expect(all.data.data.length).toBeGreaterThan(live.data.data.length);
    expect(all.data.data.some((g: { revokedAt: string | null }) => g.revokedAt != null)).toBe(true);
  });

  it('refuses a second live grant for the same user and module', async () => {
    const { status, data } = await post('/onboarding/module-access', {
      userId: 2, module: 'ONBOARDING', moduleRole: 'OB_VIEWER',
    });
    expect(status).toBe(409);
    expect(data.type).toBe('https://edutrack/errors/ob-module-access-exists');
  });

  it('revokes without deleting, so an audit can still answer', async () => {
    const { status, data } = await post('/onboarding/module-access/2/revoke');
    expect(status).toBe(200);
    expect(data.data.isLive).toBe(false);
    expect(data.data.revokedBy).not.toBeNull();

    const all = await get('/onboarding/module-access?includeRevoked=true');
    expect(all.data.data.some((g: { id: number }) => g.id === 2)).toBe(true);
  });

  it('says how long a revoke takes to bite', async () => {
    const { data } = await post('/onboarding/module-access/2/revoke');
    // The entitlement rides in the access token, so OB-08 has to be able to
    // offer ending the session rather than let an admin think it failed.
    expect(data.data.tokenLagSeconds).toBeGreaterThan(0);
  });

  it('will not remove the last administrator', async () => {
    const { status } = await post('/onboarding/module-access/1/revoke');
    expect(status).toBe(422);
  });
});

describe('A-118 · settings and templates', () => {
  it('seeds the values PHASE-2-BUILD-PLAN §2 locked', async () => {
    const { data } = await get('/onboarding/settings');
    expect(data.data.amberThresholdPercent).toBe(75);
    expect(data.data.scannerIntervalMinutes).toBe(5);
    expect(data.data.ladder.map((r: { afterWorkingHours: number }) => r.afterWorkingHours)).toEqual([0, 4, 8]);
  });

  it('refuses a ladder whose rungs do not ascend', async () => {
    const { status } = await put('/onboarding/settings', {
      amberThresholdPercent: 75,
      scannerIntervalMinutes: 5,
      ladder: [
        { level: 'L1', afterWorkingHours: 0, recipient: 'STEP_OWNER' },
        { level: 'L2', afterWorkingHours: 8, recipient: 'ONBOARDING_MANAGER' },
        { level: 'L3', afterWorkingHours: 4, recipient: 'OB_ADMIN' },
      ],
    });
    // An L3 that fires before its L2 is not a ladder.
    expect(status).toBe(400);
  });

  it('refuses an amber threshold of 100', async () => {
    const { status } = await put('/onboarding/settings', {
      amberThresholdPercent: 100,
      scannerIntervalMinutes: 5,
      ladder: [
        { level: 'L1', afterWorkingHours: 0, recipient: 'STEP_OWNER' },
        { level: 'L2', afterWorkingHours: 4, recipient: 'ONBOARDING_MANAGER' },
        { level: 'L3', afterWorkingHours: 8, recipient: 'OB_ADMIN' },
      ],
    });
    // A warning that fires at the breach is a warning with no warning in it.
    expect(status).toBe(400);
  });

  it('marks WhatsApp templates undeliverable rather than hiding them', async () => {
    const { data } = await get('/onboarding/notification-templates');
    const whatsapp = data.data.find((t: { channel: string }) => t.channel === 'WHATSAPP');
    expect(whatsapp).toBeTruthy();
    // Authored and stored; nothing will dispatch it (PHASE-2-BUILD-PLAN §6.1).
    expect(whatsapp.isDeliverable).toBe(false);
    expect(data.data.filter((t: { channel: string }) => t.channel === 'EMAIL')
      .every((t: { isDeliverable: boolean }) => t.isDeliverable)).toBe(true);
  });

  it('cannot silence escalation or sign-off mail', async () => {
    const { data } = await get('/onboarding/notification-templates?category=ESCALATION');
    const mandatory = data.data.find((t: { channel: string }) => t.channel === 'EMAIL');
    expect(mandatory.isMandatory).toBe(true);
    const { status } = await patch(`/onboarding/notification-templates/${mandatory.id}`, { isActive: false });
    expect(status).toBe(409);
  });

  it('refuses a merge tag that is not in the vocabulary', async () => {
    const { status, data } = await patch('/onboarding/notification-templates/1', {
      bodyTemplate: 'Hello {{client_name}}, about {{invented_tag}}.',
    });
    expect(status).toBe(400);
    expect(JSON.stringify(data.errors)).toContain('invented_tag');
  });
});

describe('A-118 · the OB-02 board and OB-10 hub', () => {
  it('draws all seven cards, in the mockup’s order, with the fixture’s numbers', async () => {
    const { data } = await get('/onboarding/dashboard/summary');
    // An absent card and a card reading nought are different claims — and the
    // eight-client world pins every count, so a board that miscounted any tile
    // fails here by name rather than by length.
    expect(data.data.cards.map((c: { key: string }) => c.key)).toEqual([
      'ongoing-projects', 'this-weeks-deadlines', 'todays-delivery',
      'overdue-clients', 'client-escalations', 'live', 'at-risk',
    ]);
    expect(data.data.cards.map((c: { count: number }) => c.count))
      .toEqual([6, 10, 2, 2, 1, 1, 1]);
    expect(data.data.computedAt).toBeTruthy();
  });

  it('mixes services and prerequisites in one slide-over', async () => {
    // Little Scholars' checklist is overdue — which is what a stalled gate
    // looks like, and the only reason a prerequisite appears on a delivery
    // board at all. With Sunrise's breached migration that makes five overdue
    // items across the tile's two clients.
    const { data } = await get('/onboarding/dashboard/cards/overdue-clients/items?limit=200');
    expect(data.data).toHaveLength(5);
    expect(data.data.map((i: { itemId: number }) => i.itemId).sort((a: number, b: number) => a - b))
      .toEqual([161, 163, 164, 165, 214]);
    expect(new Set(data.data.map((i: { obClientId: number }) => i.obClientId)).size).toBe(2);
    const types = new Set(data.data.map((i: { itemType: string }) => i.itemType));
    // Plan §9's "all client tasks". No existing list can answer this.
    expect(types.has('PREREQUISITE')).toBe(true);
    const prereq = data.data.find((i: { itemType: string }) => i.itemType === 'PREREQUISITE');
    expect(prereq.owner).toBeNull();
    expect(prereq.journeyId).toBeNull();
    // Nothing overdue is BLOCKED, so no row on this card carries a reason.
    expect(data.data.every((i: { blockedReason: string | null }) => i.blockedReason === null))
      .toBe(true);
  });

  it('carries the block reason on the stuck row and on nothing else', async () => {
    // One row per running client; the stuck table filters these client-side.
    const { data } = await get('/onboarding/dashboard/cards/ongoing-projects/items?limit=200');
    expect(data.data).toHaveLength(6);

    const trinity = data.data.find(
      (i: { obClientName: string }) => i.obClientName.startsWith('Trinity'),
    );
    expect(trinity.status).toBe('BLOCKED');
    expect(trinity.blockedReason).toBe("Branding assets pending from client's design vendor");

    // A client-attributed pause has a counterparty, not a culprit.
    const bluebell = data.data.find(
      (i: { obClientName: string }) => i.obClientName.startsWith('Bluebell'),
    );
    expect(bluebell.status).toBe('WAITING_ON_CLIENT');
    expect(bluebell.blockedReason).toBeNull();
  });

  it('refuses an unknown card key', async () => {
    const { status } = await get('/onboarding/dashboard/cards/invented/items');
    expect(status).toBe(400);
  });

  it('returns a row for an implementor with no clients', async () => {
    const { data } = await get('/onboarding/dashboard/implementor-workload');
    // Exactly the five OB_STEP_OWNER grants, in grant order — Nikhil is the
    // bench row, present with nothing to his name.
    expect(data.data.map((r: { user: { displayName: string } }) => r.user.displayName)).toEqual([
      'Priya Nair', 'Kavya Sharma', 'Rohan Gupta', 'Vikram Mehta', 'Nikhil Joshi',
    ]);
    // 96 − 38·delayed − 15·blockedWaiting − 6·atRisk, and null for the bench.
    expect(data.data.map((r: { performanceScore: number | null }) => r.performanceScore))
      .toEqual([60, 58, 96, 96, null]);
    for (const row of data.data) {
      // The six columns partition clientsOpen — A-108's arithmetic contract.
      const parts = row.onTrack + row.notStarted + row.delayed + row.atRisk
        + row.blockedWaiting + row.aheadOfSchedule;
      expect(parts).toBe(row.clientsOpen);
    }
  });

  it('scores nobody who has completed nothing', async () => {
    const { data } = await get('/onboarding/dashboard/implementor-workload');
    for (const row of data.data) {
      if (row.completedOnTime === 0) expect(row.performanceScore).toBeNull();
    }
  });

  it('lists unbuilt reports with a reason rather than hiding them', async () => {
    const { data } = await get('/onboarding/reports');
    expect(data.data.reports).toHaveLength(12);
    // B-122 wrote six held. B-119 moved csat-summary out of that group and
    // into the built set — see its own comment on OB_REPORTS — leaving five.
    const held = data.data.reports.filter((r: { available: boolean }) => !r.available);
    expect(held).toHaveLength(5);
    expect(held.every((r: { unavailableReason: string }) => r.unavailableReason?.length > 0)).toBe(true);
  });

  // B-122 · the two unbuilt groups are waiting on different things, and the
  // cards say which. "Held pending the reports decision" on a report whose
  // tables do not exist sends somebody to ask a question nobody can answer.
  it('says a report waiting on tables is not waiting on the OB4b decision', async () => {
    const { data } = await get('/onboarding/reports');
    const prereqAging = data.data.reports.find((r: { key: string }) => r.key === 'prereq-aging');
    expect(prereqAging.available).toBe(false);
    expect(prereqAging.unavailableReason).toContain('B-124');
    expect(prereqAging.unavailableReason).not.toContain('OB4b');
  });

  it('404s a report that is declared but not built', async () => {
    expect((await get('/onboarding/reports/journey-funnel')).status).toBe(200);
    // By the time somebody is running it there are no rows to describe.
    // csat-summary is available as of B-119 — breach-log is still held.
    expect((await get('/onboarding/reports/breach-log')).status).toBe(404);
    expect((await get('/onboarding/reports/invented')).status).toBe(404);
  });

  it('runs the go-live survey summary now that B-119 has built it', async () => {
    expect((await get('/onboarding/reports/csat-summary')).status).toBe(200);
  });
});
