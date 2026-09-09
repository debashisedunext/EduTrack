import { describe, expect, it } from 'vitest';
import { getDb } from '../db';

/**
 * C-104 · mock-handler tests for the five step-lifecycle routes.
 *
 * Runs against `db.ts`'s own fixture steps rather than pre-seeding rows —
 * adding steps to a fixture journey to get one broke `onboarding.test.ts`'s
 * exact `totalTatDays`/`utilizedHours` assertions, which is precisely the
 * drift `onboardingJourneyInstances.ts`'s own header warns a second copy
 * invites.
 *
 * The signed-in user is Ravi (user 3). In the eight-client world he is
 * **backup owner** on exactly two steps — Bluebell's waiting 412 and
 * Trinity's blocked 815 — and owns no PENDING step anywhere. So the
 * success paths first *claim* a pending step through the C-108 re-plan
 * route (`PATCH /onboarding/journey-steps/:id`, which the lifecycle
 * handlers re-read ownership from) and then act on it: reassignment-then-act
 * is a legitimate flow, and it keeps the fixture untouched.
 *
 * The cast:
 *  - 816 'Admin & user training' — Trinity, PENDING, owned by user 6, no
 *    backup. Claimed where a startable step is needed; left alone where a
 *    stranger's step is.
 *  - 815 — Trinity, BLOCKED, backup owner 3 (resumable as-is).
 *  - 412 — Bluebell, WAITING_ON_CLIENT, backup owner 3 (resumable as-is).
 *  - 321 — Horizon's biometric journey 32, gate OPEN but held by journey 31.
 *  - 711 — Little Scholars, journey 71, gate LOCKED.
 *  - 811 — Trinity's kickoff, DONE: the closed step.
 */

const BASE = '/api/v1';

async function post(path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  return { status: res.status, data };
}

async function get(path: string) {
  const res = await fetch(`${BASE}${path}`);
  const data = await res.json().catch(() => null);
  return { status: res.status, data };
}

async function patch(path: string, body: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  return { status: res.status, data };
}

/** C-108's re-plan route, used to make Ravi the owner before he acts. */
const claim = (stepId: number) =>
  patch(`/onboarding/journey-steps/${stepId}`, { ownerUserId: 3 });

describe('C-104 · step lifecycle', () => {
  it('starts a pending step the caller owns', async () => {
    await claim(816);
    const { status, data } = await post('/onboarding/journey-steps/816/start');
    expect(status).toBe(200);
    expect(data.data.status).toBe('IN_PROGRESS');
    expect(data.data.startedAt).not.toBeNull();
  });

  it('refuses to start a step twice', async () => {
    await claim(816);
    await post('/onboarding/journey-steps/816/start');
    const { status, data } = await post('/onboarding/journey-steps/816/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/invalid-step-transition');
  });

  it('refuses to start while the journey is held', async () => {
    // Horizon's biometric journey 32: gate OPEN, held behind journey 31.
    await claim(321);
    const { status, data } = await post('/onboarding/journey-steps/321/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/journey-not-open');
    expect(data.detail).toContain('held by journey');
  });

  it('refuses to start while the journey gate is locked', async () => {
    // Little Scholars' journey 71 is still behind its prerequisite gate.
    await claim(711);
    const { status, data } = await post('/onboarding/journey-steps/711/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/journey-not-open');
    expect(data.detail).toContain('gate LOCKED');
  });

  it('completes a step the caller started', async () => {
    await claim(816);
    await post('/onboarding/journey-steps/816/start');
    const { status, data } = await post('/onboarding/journey-steps/816/complete');
    expect(status).toBe(200);
    expect(data.data.status).toBe('DONE');
    expect(data.data.finishedAt).not.toBeNull();
  });

  it('refuses to complete a step that has not started', async () => {
    await claim(816);
    const { status, data } = await post('/onboarding/journey-steps/816/complete');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/invalid-step-transition');
  });

  it('blocks a step with its mandatory reason', async () => {
    await claim(816);
    await post('/onboarding/journey-steps/816/start');
    const { status, data } = await post('/onboarding/journey-steps/816/block', {
      reasonCode: 'client-unresponsive', note: 'Awaiting sign-off',
    });
    expect(status).toBe(200);
    expect(data.data.status).toBe('BLOCKED');
    expect(data.data.blockedReasonCode).toBe('client-unresponsive');
    expect(data.data.blockedNote).toBe('Awaiting sign-off');
  });

  it('refuses to block with no reason', async () => {
    await claim(816);
    await post('/onboarding/journey-steps/816/start');
    const { status, data } = await post('/onboarding/journey-steps/816/block', {});
    expect(status).toBe(400);
    expect(data.errors.reasonCode).toBeDefined();
  });

  it('marks a step waiting on the client', async () => {
    await claim(816);
    await post('/onboarding/journey-steps/816/start');
    const { status, data } = await post('/onboarding/journey-steps/816/waiting-on-client');
    expect(status).toBe(200);
    expect(data.data.status).toBe('WAITING_ON_CLIENT');
  });

  it('resumes a blocked step and clears the reason', async () => {
    // Trinity's 815 — Ravi is its fixture backup owner, so no claim needed.
    const { status, data } = await post('/onboarding/journey-steps/815/resume');
    expect(status).toBe(200);
    expect(data.data.status).toBe('IN_PROGRESS');
    expect(data.data.blockedReasonCode).toBeNull();
    expect(data.data.blockedNote).toBeNull();
  });

  it('resumes a waiting-on-client step', async () => {
    // Bluebell's 412 — Ravi is its fixture backup owner.
    const { status, data } = await post('/onboarding/journey-steps/412/resume');
    expect(status).toBe(200);
    expect(data.data.status).toBe('IN_PROGRESS');
  });

  it('refuses to resume a step that is neither blocked nor waiting-on-client', async () => {
    await claim(816);
    const { status, data } = await post('/onboarding/journey-steps/816/resume');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/invalid-step-transition');
  });

  it('refuses every action for a caller who is neither owner nor backup owner', async () => {
    // Unclaimed, 816 belongs to user 6 with no backup — Ravi is a stranger.
    const { status, data } = await post('/onboarding/journey-steps/816/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/step-owner-required');
  });

  it('404s an unknown step', async () => {
    const { status } = await post('/onboarding/journey-steps/999999/start');
    expect(status).toBe(404);
  });
});

describe('C-108 · backup owner — assignment and leave-aware inheritance', () => {
  it('effectiveOwnerUserId is the owner when nobody is on leave', async () => {
    // 815: owner Priya (6), backup Ravi (3) — the owner holds it today.
    const { status, data } = await get('/onboarding/journey-steps/815');
    expect(status).toBe(200);
    expect(data.data.ownerUserId).toBe(6);
    expect(data.data.effectiveOwnerUserId).toBe(6);
  });

  it('reassigns owner, backup owner, TAT and due date', async () => {
    const { status, data } = await patch('/onboarding/journey-steps/815', {
      backupOwnerUserId: 4, tatDays: 6, dueAt: '2026-11-01T00:00:00.000Z',
    });
    expect(status).toBe(200);
    expect(data.data.ownerUserId).toBe(6);
    expect(data.data.backupOwnerUserId).toBe(4);
    expect(data.data.tatDays).toBe(6);
    expect(data.data.dueAt).toBe('2026-11-01T00:00:00.000Z');
  });

  it('leaves fields the request omits unchanged', async () => {
    await patch('/onboarding/journey-steps/815', { backupOwnerUserId: 4 });
    const { data } = await patch('/onboarding/journey-steps/815', { tatDays: 7 });
    expect(data.data.backupOwnerUserId).toBe(4);
    expect(data.data.tatDays).toBe(7);
  });

  it('clears the backup owner on an explicit null', async () => {
    await patch('/onboarding/journey-steps/815', { backupOwnerUserId: 4 });
    const { data } = await patch('/onboarding/journey-steps/815', { backupOwnerUserId: null });
    expect(data.data.backupOwnerUserId).toBeNull();
  });

  it('refuses to re-plan a step that is already closed', async () => {
    // Trinity's kickoff (811) is DONE.
    const { status, data } = await patch('/onboarding/journey-steps/811', { tatDays: 1 });
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/ob-step-terminal');
  });

  it('the backup owner becomes effective when the owner is on approved leave today', async () => {
    // 815's fixture pair: owner Priya (6), backup Ravi (3).
    const today = new Date().toISOString().slice(0, 10);
    getDb().calendar.leaves.push({
      id: 9001, userId: 6, startDate: today, endDate: today,
      leaveType: 'CASUAL', isHalfDay: false, status: 'APPROVED', reason: null,
    });

    const { data } = await get('/onboarding/journey-steps/815');

    expect(data.data.ownerUserId).toBe(6);
    expect(data.data.effectiveOwnerUserId).toBe(3);
  });
});
