import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { initialAuthState, useAuthStore } from './authStore';
import type { Session } from '@/api/generated/model';

/**
 * A-031 · the landing-route guard.
 *
 * The mapping itself is the server's (`LandingRoutes`), and is tested there.
 * What is asserted here is the one question the frontend alone can answer:
 * whether this build has a screen at the path the server named.
 */

const session = (landingRoute?: string | null): Session =>
  ({
    accessToken: 'token',
    expiresIn: 900,
    mustChangePassword: false,
    landingRoute: landingRoute ?? undefined,
    user: {
      id: 1,
      displayName: 'Asha Rao',
      username: 'asha.rao',
      email: 'asha.rao@edunext.test',
      role: 'DEVELOPER',
      permissions: [],
      projectIds: [],
      reporteeIds: [],
      timezone: 'Asia/Kolkata',
    },
  }) as Session;

describe('the landing route the store keeps', () => {
  beforeEach(() => {
    useAuthStore.setState(initialAuthState);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each(['/dashboard', '/my-tasks', '/tickets'])('keeps %s, which this build can render', (route) => {
    useAuthStore.getState().signIn(session(route));
    expect(useAuthStore.getState().landingRoute).toBe(route);
  });

  it('falls back when the server names a route with no screen yet', () => {
    // The server correctly maps QA and Deployment to /stages/queue per the
    // blueprint. That screen is C-062 and is not in the router, so without this
    // guard those two roles sign in and land on the not-found placeholder —
    // the server right, the experience broken.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    useAuthStore.getState().signIn(session('/stages/queue'));

    expect(useAuthStore.getState().landingRoute).toBe('/dashboard');
    expect(warn).toHaveBeenCalledOnce();
  });

  it('warns rather than rewriting silently', () => {
    // A route quietly rewritten is indistinguishable from one never sent, and
    // the whole point of A-031 is that the destination is decided somewhere
    // visible. The warning is what makes the gap findable.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    useAuthStore.getState().signIn(session('/stages/queue'));

    expect(warn.mock.calls[0]?.[0]).toContain('/stages/queue');
  });

  it('falls back without warning when the server sends nothing', () => {
    // A server too old to send the field is not a misconfiguration to shout
    // about — it is the pre-A-031 behaviour, and it degrades to the blueprint's
    // default exactly as before.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    useAuthStore.getState().signIn(session(null));

    expect(useAuthStore.getState().landingRoute).toBe('/dashboard');
    expect(warn).not.toHaveBeenCalled();
  });

  it('does not treat a route prefix as renderable', () => {
    // '/tickets/new' exists in the router but is not a landing destination, and
    // matching by prefix would let '/stages/queue/waiting' through as well.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    useAuthStore.getState().signIn(session('/tickets/new'));

    expect(useAuthStore.getState().landingRoute).toBe('/dashboard');
    expect(warn).toHaveBeenCalledOnce();
  });
});
