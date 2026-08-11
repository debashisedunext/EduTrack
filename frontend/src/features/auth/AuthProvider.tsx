import * as React from 'react';

import { logout as logoutRequest, refreshSession } from '@/api/generated/auth/auth';

import { useAuthStore } from './authStore';

/**
 * The session's clock — A-030, enforcing A-025's two timeouts client-side.
 *
 * Wraps the whole app, above the router, and does three things nothing else can:
 * restores the session after a reload, renews the access token before it
 * expires, and lets the session end when it should.
 *
 * ## The timers, and the trap in combining them
 *
 * A-025 gives a session an idle timeout of 30 minutes and an absolute lifetime
 * of 12 hours. A background refresh every ~14 minutes would quietly defeat the
 * first one: a laptop left open on the dashboard would hold a live session
 * indefinitely, because the only thing keeping it alive is this component. So a
 * scheduled renewal **checks for real user activity first** and lets the token
 * lapse if there has been none. Idle means idle; a timer is not a user.
 *
 * The server enforces both independently and A-025's Redis blacklist is what
 * actually ends a session — this is not the security boundary. What it buys is
 * that an abandoned screen stops being a live session at the moment the server
 * thinks it did, instead of showing working UI that 401s on the next click.
 *
 * ## Why the timestamps live in the store
 *
 * There are two ways a session starts — this component's startup refresh, and
 * `LoginPage` calling `signIn` directly — and only one of them passes through
 * the effect below. Anchoring the clocks to refs here would leave a freshly
 * logged-in session with an unset start time, and the first renewal would read
 * that as twelve hours elapsed and sign the user out. One clock, in the store,
 * written by whichever path establishes the session.
 */

/** Renew this far ahead of expiry, so a slow request cannot land after the token dies. */
const RENEW_MARGIN_MS = 60_000;

/** A-025 — 30 minutes with no interaction ends the session. */
const IDLE_LIMIT_MS = 30 * 60_000;

/** A-025 — 12 hours from sign-in, regardless of how busy the user is. */
const ABSOLUTE_LIMIT_MS = 12 * 60 * 60_000;

/** Never sit on a zero or negative delay — that spins into a refresh loop. */
const MIN_RENEW_DELAY_MS = 5_000;

/**
 * Passive, so scrolling a long ticket list is never blocked by this listener,
 * and capturing, so a component calling `stopPropagation` cannot make the app
 * look idle while someone is using it.
 */
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;
const LISTENER_OPTIONS: AddEventListenerOptions = { passive: true, capture: true };

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const signIn = useAuthStore((state) => state.signIn);
  const signOut = useAuthStore((state) => state.signOut);
  const status = useAuthStore((state) => state.status);
  const expiresAt = useAuthStore((state) => state.expiresAt);
  const sessionStartedAt = useAuthStore((state) => state.sessionStartedAt);

  const lastActivity = React.useRef(Date.now());

  /**
   * Ends the session locally and tells the server to drop the refresh cookie.
   *
   * **The request goes first, and that ordering is load-bearing.**
   * `POST /auth/logout` is an authenticated route — the real server answers
   * `401 invalid-access-token` and revokes nothing without a bearer token, and
   * `signOut()` is what clears that token out of `api/http.ts`. Calling it first
   * would leave the refresh family alive, so the next page load would sign the
   * user straight back in. `http()` fixes the header while building the request,
   * synchronously, so issuing it on the line above is enough.
   *
   * It is not awaited: if the network is gone the user must still end up signed
   * out on this machine rather than waiting on a timeout, and a logout that
   * reports an error invites a second click with nothing left to do.
   */
  const endSession = React.useCallback(
    (notifyServer = true) => {
      if (notifyServer) {
        logoutRequest().catch(() => {
          /* best effort — the local clear below is what the user sees */
        });
      }
      signOut();
    },
    [signOut],
  );

  /**
   * Startup: trade the HttpOnly refresh cookie for an access token.
   *
   * Runs exactly once and unconditionally — there is no way to ask whether the
   * cookie exists, since not being readable from JavaScript is the whole point
   * of it. A 401 here is the normal answer for a first-time visitor, not an
   * error worth surfacing. Re-running it would consume a second token from the
   * family, which under A-024's rotation looks like reuse.
   */
  React.useEffect(() => {
    let cancelled = false;

    refreshSession()
      .then((response) => {
        if (!cancelled) signIn(response.data);
      })
      .catch(() => {
        if (!cancelled) signOut();
      });

    return () => {
      cancelled = true;
    };
    // `signIn` and `signOut` are zustand actions created once in the store
    // initialiser, so their identity never changes and this effect runs exactly
    // once despite the dependency list. Listed rather than suppressed so the
    // rule keeps working if either is ever replaced by something unstable.
  }, [signIn, signOut]);

  /** Renewal, rescheduled every time a new token lands. */
  React.useEffect(() => {
    if (status !== 'authenticated' || expiresAt === null) return;

    const delay = Math.max(MIN_RENEW_DELAY_MS, expiresAt - Date.now() - RENEW_MARGIN_MS);
    const timer = setTimeout(() => {
      const now = Date.now();

      if (sessionStartedAt !== null && now - sessionStartedAt >= ABSOLUTE_LIMIT_MS) {
        endSession();
        return;
      }
      // The idle check belongs here rather than on its own interval: renewal is
      // the only thing that extends a session, so declining to renew *is* the
      // timeout. A separate 30-minute timer would race with this one.
      if (now - lastActivity.current >= IDLE_LIMIT_MS) {
        endSession();
        return;
      }

      refreshSession()
        .then((response) => signIn(response.data))
        // A refused refresh means the family was revoked (A-024) or the cookie
        // expired. Either way the session is over and the server already knows,
        // so telling it again would be noise.
        .catch(() => endSession(false));
    }, delay);

    return () => clearTimeout(timer);
  }, [status, expiresAt, sessionStartedAt, endSession, signIn]);

  /** Activity tracking, only while there is a session to keep alive. */
  React.useEffect(() => {
    if (status !== 'authenticated') return;

    const note = () => {
      lastActivity.current = Date.now();
    };
    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, note, LISTENER_OPTIONS);
    }
    return () => {
      for (const event of ACTIVITY_EVENTS) {
        window.removeEventListener(event, note, LISTENER_OPTIONS);
      }
    };
  }, [status]);

  return <>{children}</>;
}
