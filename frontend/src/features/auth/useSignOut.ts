import { useCallback } from 'react';

import { logout as logoutRequest } from '@/api/generated/auth/auth';

import { useAuthStore } from './authStore';

/**
 * Sign out from anywhere — the avatar menu's Logout, and anything that discovers
 * the session is finished.
 *
 * A hook over the store rather than a bare `signOut` call so no caller can
 * forget the server half. Local state alone leaves the HttpOnly refresh cookie
 * alive, and the next visitor to that browser is signed straight back in by
 * `AuthProvider`'s startup refresh — which looks exactly like logout not working.
 *
 * ## The order of these two lines is load-bearing
 *
 * `POST /auth/logout` is an authenticated route: without a bearer token the real
 * server answers `401 invalid-access-token` and revokes nothing. `signOut()`
 * clears the token out of `api/http.ts`, so calling it first makes the logout
 * request unauthenticated and the refresh family survives — the exact bug this
 * hook exists to prevent, one layer further down. Verified against the running
 * backend, not reasoned about: the mock accepts an unauthenticated logout, so
 * no test in this repo would have caught it.
 *
 * The request is issued but **not awaited** before the local clear. `http()`
 * reads the access token while building the request, synchronously, so the
 * header is already fixed by the time `signOut()` runs on the next line — and
 * on a dead connection the user still ends up signed out on this machine rather
 * than waiting on a timeout. The failure is swallowed for the same reason: a
 * logout that reports an error invites a second click with nothing left to do.
 *
 * In its own file rather than beside `AuthProvider` so that module exports only
 * a component — Vite's fast refresh gives up on a module that mixes the two, and
 * the whole app would remount on every edit to it.
 */
export function useSignOut() {
  const signOut = useAuthStore((state) => state.signOut);

  return useCallback(() => {
    logoutRequest().catch(() => {
      /* best effort — the local clear below is what the user sees */
    });
    signOut();
  }, [signOut]);
}
