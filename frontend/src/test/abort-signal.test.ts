import { describe, expect, it } from 'vitest';
import { getMe } from '../api/generated/auth/auth';

/**
 * Guards the cross-realm `AbortSignal` shim in `setup.ts`.
 *
 * Without it, Node 24 rejects jsdom's `AbortSignal` on every `RequestInit` and
 * *every* TanStack Query request fails — the app renders permanently empty
 * while CI, pinned to Node 22, stays green. That combination is why it went
 * unnoticed: the suite is red only on a developer's machine.
 *
 * These tests fail on the exact regression rather than on a symptom of it, so
 * removing the shim breaks them here instead of somewhere in a component test.
 */
describe('AbortSignal across the jsdom/Node boundary', () => {
  it('accepts a signal from jsdom on a real request', async () => {
    const controller = new AbortController();
    const result = await getMe(controller.signal);
    expect(result.data.displayName).toBe('Ravi Kumar');
  });

  it('still rejects when the signal is already aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(getMe(controller.signal)).rejects.toThrow(/abort/i);
  });

  it('rejects when the signal aborts while the request is in flight', async () => {
    const controller = new AbortController();
    const inFlight = getMe(controller.signal);
    controller.abort();
    await expect(inFlight).rejects.toThrow(/abort/i);
  });
});
