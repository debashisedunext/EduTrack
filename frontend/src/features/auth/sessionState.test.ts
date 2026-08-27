import { beforeEach, describe, expect, it } from 'vitest';

import { getGetMeQueryKey } from '@/api/generated/auth/auth';
import type { Project } from '@/api/generated/model/project';
import type { Session } from '@/api/generated/model/session';
import { useCurrentProjectStore } from '@/app/currentProjectStore';
import { queryClient } from '@/app/queryClient';

import { initialAuthState, useAuthStore } from './authStore';

/**
 * One user's cached data must not survive into the next user's session.
 *
 * The bug, reported against My Tasks: sign in as Karthik, sign out, sign in as
 * Nikhil, and My Tasks lists Karthik's tickets. Nothing was fetching the wrong
 * rows — `signOut` left the query cache untouched, so `useGetMe()` answered out
 * of Karthik's entry (keyed on the route alone, and still inside its 30s
 * `staleTime`), and every viewer-scoped query downstream was keyed by the id it
 * returned.
 *
 * Asserted here, at the store, rather than through a screen, because eight
 * screens read identity through `useGetMe` and the guarantee is one they all
 * depend on rather than one any of them owns. The cache under test is the real
 * `app/queryClient` singleton — a fresh `QueryClient` per case would pass
 * whatever the code did.
 */

const session = (id: number, displayName: string): Session =>
  ({
    accessToken: `token.${id}`,
    expiresIn: 900,
    mustChangePassword: false,
    landingRoute: '/my-tasks',
    user: {
      id,
      displayName,
      username: displayName.toLowerCase().replace(' ', '.'),
      email: `${id}@edunext.test`,
      role: 'DEVELOPER',
      permissions: [],
      projectIds: [],
      reporteeIds: [],
      timezone: 'Asia/Kolkata',
    },
  }) as Session;

const KARTHIK = session(7, 'Karthik Rao');
const NIKHIL = session(8, 'Nikhil Bansal');

/** Whatever the previous session left behind — `/me` is the one that matters. */
function seedCache(): void {
  queryClient.setQueryData(getGetMeQueryKey(), { data: KARTHIK.user });
  queryClient.setQueryData(['tickets', { assigneeId: 7 }], { data: [{ id: 1 }] });
}

const cacheIsEmpty = () => queryClient.getQueryCache().getAll().length === 0;

beforeEach(() => {
  queryClient.clear();
  useAuthStore.setState(initialAuthState);
  useCurrentProjectStore.setState({ project: null });
});

describe('ending a session', () => {
  it('empties the query cache, so the next user cannot be served this one', () => {
    useAuthStore.getState().signIn(KARTHIK);
    seedCache();

    useAuthStore.getState().signOut();

    expect(queryClient.getQueryData(getGetMeQueryKey())).toBeUndefined();
    expect(cacheIsEmpty()).toBe(true);
  });

  it('drops the project switcher selection, which may not be the next user\'s project', () => {
    useAuthStore.getState().signIn(KARTHIK);
    useCurrentProjectStore.setState({ project: { id: 3, name: 'Aurora' } as Project });

    useAuthStore.getState().signOut();

    expect(useCurrentProjectStore.getState().project).toBeNull();
  });
});

describe('signing in on top of another session', () => {
  it('clears the cache when it is somebody else — /login is reachable while signed in', () => {
    // No `signOut` in this sequence, and that is the point: `LoginPage` sits
    // outside `RequireAuth`, so this is a path a user can actually take.
    useAuthStore.getState().signIn(KARTHIK);
    seedCache();

    useAuthStore.getState().signIn(NIKHIL);

    expect(cacheIsEmpty()).toBe(true);
    expect(useAuthStore.getState().user?.id).toBe(8);
  });

  it('keeps the cache on a renewal, which is the same user arriving every ~14 minutes', () => {
    // The guard has to be on identity, not on "signIn was called twice".
    // Clearing here would blank whatever screen the user is reading, four times
    // an hour, for ever.
    useAuthStore.getState().signIn(KARTHIK);
    seedCache();

    useAuthStore.getState().signIn(KARTHIK);

    expect(queryClient.getQueryData(getGetMeQueryKey())).toEqual({ data: KARTHIK.user });
  });
});
