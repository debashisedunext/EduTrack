import { create } from 'zustand';

import { setAccessToken } from '@/api/http';
import type { Me } from '@/api/generated/model/me';
import type { Session } from '@/api/generated/model/session';

import { discardSessionState } from './sessionState';

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
 * A-031 · routes this build can actually render.
 *
 * **This is not a role→route map, and must not become one.** Where a role
 * belongs is the server's decision (`LandingRoutes`), for the reason A-030
 * recorded: a second copy of that mapping in TypeScript is the one nobody
 * updates when a role is added. The question asked here is a different one that
 * the frontend alone can answer — *does this build have a screen at that path?*
 *
 * It exists because the server correctly maps QA and Deployment to
 * `/stages/queue`, which is C-062 and does not exist in the router yet. Without
 * this, those two roles sign in and land on the not-found placeholder — the
 * server would be right and the experience broken.
 *
 * The list is deliberately of *landing* destinations only, not every route. It
 * shrinks to nothing the day every blueprint destination is built, and adding
 * C-062 to the router is the only change needed to make `/stages/queue` start
 * working — no server change, no edit here.
 */
const RENDERABLE_LANDINGS: readonly string[] = ['/dashboard', '/my-tasks', '/tickets'];

/**
 * Falls back when the server names a destination this build cannot serve.
 *
 * Warns rather than failing silently: a landing route quietly rewritten is
 * indistinguishable from one that was never sent, and the whole point of A-031
 * is that the destination is decided somewhere visible.
 *
 * Exported because `LoginPage` navigates using the value straight off the login
 * response rather than the copy this store keeps — guarding only the store would
 * miss the most common path through it, an ordinary sign-in.
 */
export function renderableLanding(route: string | null | undefined): string {
  if (!route) return FALLBACK_LANDING;
  if (RENDERABLE_LANDINGS.includes(route)) return route;
  console.warn(
    `auth: server landing route "${route}" has no screen in this build - using ${FALLBACK_LANDING}. ` +
      'Remove this fallback once the route exists.',
  );
  return FALLBACK_LANDING;
}

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

export const useAuthStore = create<AuthState>((set, get) => ({
  ...initialAuthState,

  signIn: (session) => {
    const current = get();

    // A different person arriving at a browser the last one never signed out
    // of. `LoginPage` sits outside `RequireAuth`, so anyone can navigate to
    // /login while still authenticated and sign in as somebody else — a path
    // that never passes through `signOut`, and would otherwise hand the new
    // user the old one's cache.
    //
    // Guarded on the id rather than run unconditionally because renewal comes
    // through here too, every ~14 minutes: clearing the cache on every renewal
    // would blank the screen the user is in the middle of reading.
    if (current.user !== null && current.user.id !== session.user.id) {
      discardSessionState();
    }

    setAccessToken(session.accessToken);
    const now = Date.now();

    set({
      status: 'authenticated',
      user: session.user,
      landingRoute: renderableLanding(session.landingRoute),
      mustChangePassword: session.mustChangePassword ?? false,
      expiresAt: now + session.expiresIn * 1000,
      // Only a *new* session starts the absolute clock. Reaching here while
      // already authenticated means this was a renewal, and a renewal that
      // reset the anchor would make the 12-hour limit unreachable for exactly
      // the users it exists for — the ones who never stop working.
      //
      // Read off `current`, captured above rather than through `set`'s updater
      // form, because the clear above has to happen outside it: a zustand
      // updater is expected to be a pure function of the state it is handed.
      sessionStartedAt: current.status === 'authenticated' ? current.sessionStartedAt : now,
    });
  },

  signOut: () => {
    // Clear the token first. If this ran after `set`, React would re-render
    // against the new anonymous state while `http` still held a live token, and
    // any query that refetched in that window would go out authenticated —
    // which is the one thing signing out is supposed to prevent.
    setAccessToken(null);

    // Then everything cached *about* that user — before `set`, for the same
    // reason and one step further. React batches both updates into a single
    // render, and by the time that render runs the store must not be able to
    // hand the anonymous tree, or the next user's tree, a page of the previous
    // user's data. Here rather than in `useSignOut` because this is the funnel:
    // the avatar menu, the idle timeout, the 12-hour limit and a refused
    // refresh all end up on this line, and only one of them is a Logout click.
    discardSessionState();

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
