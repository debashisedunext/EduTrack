import { act, renderHook } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { beforeEach, expect, it } from 'vitest';

import { getAccessToken, setAccessToken } from '@/api/http';
import { server } from '@/mocks/server';

import { initialAuthState, useAuthStore } from './authStore';
import { useSignOut } from './useSignOut';

/**
 * Signing out has to reach the server, authenticated — A-030.
 *
 * This exists because the real backend caught what the mock could not. The mock
 * `POST /auth/logout` answers 204 to anyone, so the ordering bug below was
 * invisible in every test in this repo: `signOut()` clears the bearer token out
 * of `api/http.ts`, so calling it before the request makes the request
 * anonymous, and the real server replies `401 invalid-access-token` and revokes
 * nothing. The refresh cookie then survives a logout and `AuthProvider`'s
 * startup refresh signs the user straight back in on the next load.
 *
 * The assertion is therefore on the Authorization header, not on the call
 * happening — "logout was called" was already true while the bug was live.
 */

beforeEach(() => {
  useAuthStore.setState(initialAuthState);
  setAccessToken(null);
});

it('sends the access token with the logout request, then clears the session', async () => {
  const authorization: (string | null)[] = [];
  server.use(
    http.post('*/auth/logout', ({ request }) => {
      authorization.push(request.headers.get('authorization'));
      return new HttpResponse(null, { status: 204 });
    }),
  );

  useAuthStore.getState().signIn({
    accessToken: 'test.token',
    expiresIn: 900,
    user: { id: 7, displayName: 'Ravi Kumar' },
  });

  const { result } = renderHook(() => useSignOut());
  await act(async () => {
    result.current();
  });

  expect(authorization).toEqual(['Bearer test.token']);
  expect(useAuthStore.getState().status).toBe('anonymous');
  // The local half must still have happened — an authenticated logout that
  // leaves the token in place is the other half of the same bug.
  expect(getAccessToken()).toBeNull();
});

it('still signs the user out locally when the server cannot be reached', async () => {
  // A failed logout must not strand someone signed in on a shared machine.
  server.use(http.post('*/auth/logout', () => HttpResponse.error()));

  useAuthStore.getState().signIn({
    accessToken: 'test.token',
    expiresIn: 900,
    user: { id: 7, displayName: 'Ravi Kumar' },
  });

  const { result } = renderHook(() => useSignOut());
  await act(async () => {
    result.current();
  });

  expect(useAuthStore.getState().status).toBe('anonymous');
  expect(getAccessToken()).toBeNull();
});
