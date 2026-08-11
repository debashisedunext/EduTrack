import { create } from 'zustand';

import { setAccessToken } from '@/api/http';
import type { Me } from '@/api/generated/model/me';
import type { Session } from '@/api/generated/model/session';

/**
 * Who is signed in — A-030.
 *
 * `api/http.ts` has exported `setAccessToken` since D-003 with the comment
 * "called by the auth store on login, refresh and logout". This is that store.
 *
 * ## The access token is not in this state, on purpose
 *
 * It stays in `http.ts`'s module closure. Three reasons, in order of how much
 * they matter:
 *
 * 1. **Nothing renders it, so nothing should subscribe to it.** A value in
 *    zustand state is one `useAuthStore((s) => s.accessToken)` away from being
 *    read by a component, and from there one careless prop away from a DOM
 *    attribute.
 * 2. **State attracts persistence.** The moment a token is a store field,
 *    someone adds zustand's `persist` middleware to "fix" the reload, and a
 *    15-minute bearer token is in `localStorage` — readable by any injected
 *    script, surviving the browser being closed. The reload case is already
 *    solved below, and solved better.
 * 3. **It would be duplicated.** `http.ts` needs its own copy to build the
 *    header regardless, and two copies of one secret drift.
 *
 * ## Why a reload does not sign you out
 *
 * The access token dies with the tab, which is the point of holding it in
 * memory. The refresh token is an HttpOnly cookie the JavaScript cannot read
 * and the browser sends anyway, so `AuthProvider` spends one `POST /auth/refresh`
 * at startup to trade it for a new access token. That is why `status` starts at
 * `'unknown'` rather than `'anonymous'`: rendering the login screen for the
 * ~100 ms before that call answers would flash the login form at every already
 * signed-in user on every reload, and `RequireAuth` would redirect away from the
 * URL they actually asked for.
 */

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous';

interface AuthState {
  /**
   * `'unknown'` until the startup refresh settles. Treat it as "not yet
   * answered", never as a synonym for signed out.
   */
  status: AuthStatus;
  user: Me | null;
  /**
   * Where this role lands after signing in — A-031, decided server-side.
   *
   * The frontend deliberately does not own a role→route map. The server already
   * knows the role and the blueprint's mapping, and a second copy here would be
   * the one nobody updates when a role is added.
   */
  landingRoute: string | null;
  /**
   * A-026. The session is fully valid; every route except the change-password
   * screen is closed until this clears.
   */
  mustChangePassword: boolean;

  /**
   * When the current access token dies, as an epoch millisecond. `AuthProvider`
   * schedules renewal from this rather than from a duration, because a duration
   * has to be re-derived at every hop and one missed subtraction renews late.
   */
  expiresAt: number | null;

  /**
   * When this session began — the anchor for A-025's absolute 12-hour lifetime.
   *
   * **A refresh does not move it.** A-024 settled this for the server ("expiry
   * does not slide": the successor inherits `expiresAt` rather than getting a
   * fresh window) and the client has to agree, or a user who keeps a tab active
   * would be renewed past the deadline locally and then be cut off mid-action by
   * a server that had been counting properly all along.
   *
   * On a reload the true start is unknowable — it lives in Redis with the token
   * family — so bootstrap anchors it to now. That is generous rather than
   * strict, which is the safe direction for a *client-side* copy of a limit the
   * server independently enforces.
   */
  sessionStartedAt: number | null;

  signIn: (session: Session) => void;
  signOut: () => void;
  /** Called after `PATCH /me/password` succeeds — reopens the app (A-026). */
  clearPasswordChangeRequirement: () => void;
}

/** The blueprint's default landing spot when the server sends none. */
const FALLBACK_LANDING = '/dashboard';

/**
 * The state of a page that has just loaded and not yet asked who is signed in.
 *
 * Exported because a test needs to put the store back here between cases, and
 * `signOut()` is not the same thing: it means "this session ended", which sends
 * `RequireAuth` to the login screen immediately. Restoring `'anonymous'` before
 * a test's startup refresh has answered would redirect away from the route under
 * test and produce a failure that looks like a broken guard.
 */
export const initialAuthState = {
  status: 'unknown' as AuthStatus,
  user: null,
  landingRoute: null,
  mustChangePassword: false,
  expiresAt: null,
  sessionStartedAt: null,
};

export const useAuthStore = create<AuthState>((set) => ({
  ...initialAuthState,

  signIn: (session) =>
    set((current) => {
      setAccessToken(session.accessToken);
      const now = Date.now();
      return {
        status: 'authenticated' as const,
        user: session.user,
        landingRoute: session.landingRoute ?? FALLBACK_LANDING,
        mustChangePassword: session.mustChangePassword ?? false,
        expiresAt: now + session.expiresIn * 1000,
        // Only a *new* session starts the absolute clock. Reaching here while
        // already authenticated means this was a renewal, and a renewal that
        // reset the anchor would make the 12-hour limit unreachable for exactly
        // the users it exists for — the ones who never stop working.
        sessionStartedAt: current.status === 'authenticated' ? current.sessionStartedAt : now,
      };
    }),

  signOut: () => {
    // Clear the token first. If this ran after `set`, React would re-render
    // against the new anonymous state while `http` still held a live token, and
    // any query that refetched in that window would go out authenticated —
    // which is the one thing signing out is supposed to prevent.
    setAccessToken(null);
    set({
      status: 'anonymous',
      user: null,
      landingRoute: null,
      mustChangePassword: false,
      expiresAt: null,
      sessionStartedAt: null,
    });
  },

  clearPasswordChangeRequirement: () => set({ mustChangePassword: false }),
}));
