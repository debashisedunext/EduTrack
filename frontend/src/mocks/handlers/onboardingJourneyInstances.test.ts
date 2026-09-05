import { describe, expect, it } from 'vitest';
import { getDb } from '../db';

/**
 * C-104 · mock-handler tests for the five step-lifecycle routes.
 *
 * Chains calls against `db.ts`'s own fixture steps rather than pre-seeding an
 * `IN_PROGRESS` row — adding a sixth step to the Northwind ERP journey to get
 * one broke `onboarding.test.ts`'s exact `totalTatDays`/`utilizedHours`
 * assertions, which is precisely the drift `onboardingJourneyInstances.ts`'s
 * own header warns a second copy invites. Starting step 5 first and
 * completing/blocking/resuming it afterwards needs nothing pre-set.
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

describe('C-104 · step lifecycle', () => {
  it('starts a pending step the caller owns', async () => {
    const { status, data } = await post('/onboarding/journey-steps/5/start');
    expect(status).toBe(200);
    expect(data.data.status).toBe('IN_PROGRESS');
    expect(data.data.startedAt).not.toBeNull();
  });

  it('refuses to start a step twice', async () => {
    await post('/onboarding/journey-steps/5/start');
    const { status, data } = await post('/onboarding/journey-steps/5/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/invalid-step-transition');
  });

  it('refuses to start while the journey is held', async () => {
    const { status, data } = await post('/onboarding/journey-steps/6/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/journey-not-open');
    expect(data.detail).toContain('held by journey');
  });

  it('refuses to start while the journey gate is locked', async () => {
    const { status, data } = await post('/onboarding/journey-steps/8/start');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/journey-not-open');
    expect(data.detail).toContain('gate LOCKED');
  });

  it('completes a step the caller started', async () => {
    await post('/onboarding/journey-steps/5/start');
    const { status, data } = await post('/onboarding/journey-steps/5/complete');
    expect(status).toBe(200);
    expect(data.data.status).toBe('DONE');
    expect(data.data.finishedAt).not.toBeNull();
  });

  it('refuses to complete a step that has not started', async () => {
    const { status, data } = await post('/onboarding/journey-steps/5/complete');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/invalid-step-transition');
  });

  it('blocks a step with its mandatory reason', async () => {
    await post('/onboarding/journey-steps/5/start');
    const { status, data } = await post('/onboarding/journey-steps/5/block', {
      reasonCode: 'client-unresponsive', note: 'Awaiting sign-off',
    });
    expect(status).toBe(200);
    expect(data.data.status).toBe('BLOCKED');
    expect(data.data.blockedReasonCode).toBe('client-unresponsive');
    expect(data.data.blockedNote).toBe('Awaiting sign-off');
  });

  it('refuses to block with no reason', async () => {
    await post('/onboarding/journey-steps/5/start');
    const { status, data } = await post('/onboarding/journey-steps/5/block', {});
    expect(status).toBe(400);
    expect(data.errors.reasonCode).toBeDefined();
  });

  it('marks a step waiting on the client', async () => {
    await post('/onboarding/journey-steps/5/start');
    const { status, data } = await post('/onboarding/journey-steps/5/waiting-on-client');
    expect(status).toBe(200);
    expect(data.data.status).toBe('WAITING_ON_CLIENT');
  });

  it('resumes a blocked step and clears the reason', async () => {
    const { status, data } = await post('/onboarding/journey-steps/3/resume');
    expect(status).toBe(200);
    expect(data.data.status).toBe('IN_PROGRESS');
    expect(data.data.blockedReasonCode).toBeNull();
    expect(data.data.blockedNote).toBeNull();
  });

  it('resumes a waiting-on-client step', async () => {
    const { status, data } = await post('/onboarding/journey-steps/4/resume');
    expect(status).toBe(200);
    expect(data.data.status).toBe('IN_PROGRESS');
  });

  it('refuses to resume a step that is neither blocked nor waiting-on-client', async () => {
    const { status, data } = await post('/onboarding/journey-steps/5/resume');
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/invalid-step-transition');
  });

  it('refuses every action for a caller who is neither owner nor backup owner', async () => {
    // Step 7 (Attendance Policy Mapping) names no owner in the fixture.
    const { status, data } = await post('/onboarding/journey-steps/7/start');
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
    const { status, data } = await get('/onboarding/journey-steps/5');
    expect(status).toBe(200);
    expect(data.data.ownerUserId).toBe(3);
    expect(data.data.effectiveOwnerUserId).toBe(3);
  });

  it('reassigns owner, backup owner, TAT and due date', async () => {
    const { status, data } = await patch('/onboarding/journey-steps/5', {
      backupOwnerUserId: 4, tatDays: 6, dueAt: '2026-11-01T00:00:00.000Z',
    });
    expect(status).toBe(200);
    expect(data.data.ownerUserId).toBe(3);
    expect(data.data.backupOwnerUserId).toBe(4);
    expect(data.data.tatDays).toBe(6);
    expect(data.data.dueAt).toBe('2026-11-01T00:00:00.000Z');
  });

  it('leaves fields the request omits unchanged', async () => {
    await patch('/onboarding/journey-steps/5', { backupOwnerUserId: 4 });
    const { data } = await patch('/onboarding/journey-steps/5', { tatDays: 7 });
    expect(data.data.backupOwnerUserId).toBe(4);
    expect(data.data.tatDays).toBe(7);
  });

  it('clears the backup owner on an explicit null', async () => {
    await patch('/onboarding/journey-steps/5', { backupOwnerUserId: 4 });
    const { data } = await patch('/onboarding/journey-steps/5', { backupOwnerUserId: null });
    expect(data.data.backupOwnerUserId).toBeNull();
  });

  it('refuses to re-plan a step that is already closed', async () => {
    const { status, data } = await patch('/onboarding/journey-steps/1', { tatDays: 1 });
    expect(status).toBe(422);
    expect(data.type).toBe('https://edutrack/errors/ob-step-terminal');
  });

  it('the backup owner becomes effective when the owner is on approved leave today', async () => {
    await patch('/onboarding/journey-steps/5', { backupOwnerUserId: 4 });
    const today = new Date().toISOString().slice(0, 10);
    getDb().calendar.leaves.push({
      id: 9001, userId: 3, startDate: today, endDate: today,
      leaveType: 'CASUAL', isHalfDay: false, status: 'APPROVED', reason: null,
    });

    const { data } = await get('/onboarding/journey-steps/5');

    expect(data.data.ownerUserId).toBe(3);
    expect(data.data.effectiveOwnerUserId).toBe(4);
  });
});
