import { Navigate, Outlet, useLocation } from 'react-router-dom';

import { useAuthStore } from './authStore';

/**
 * The gate in front of every authenticated route — A-030.
 *
 * **This is convenience, not security.** Row scoping and permissions are decided
 * server-side (A-033 to A-036); hiding a route here stops an honest user landing
 * on a screen that would only 401, and stops nothing else. Anyone editing this
 * component to gate on *role* is writing frontend authorisation, which
 * CLAUDE.md names as the top risk in blueprint §17.
 */
export function RequireAuth() {
  const status = useAuthStore((state) => state.status);
  const mustChangePassword = useAuthStore((state) => state.mustChangePassword);
  const location = useLocation();

  // The startup refresh has not answered yet. Rendering the login form here
  // would flash it at every signed-in user on every reload, and the redirect
  // below would then throw away the URL they actually asked for.
  if (status === 'unknown') return <SessionSplash />;

  if (status === 'anonymous') {
    // `state.from` is what sends the user back where they were going after they
    // sign in. `replace` keeps the protected URL out of history, so Back from
    // the login screen does not bounce between the two.
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  // A-026 — the session is valid, and every route except the change-password
  // screen is closed until the password is changed. Enforced here rather than
  // per-page: a check on the dashboard alone leaves `/tickets` open to anyone
  // who types the URL, and the backend's `PasswordChangeGate` would then be the
  // only thing stopping them, which shows up as an unexplained error page.
  if (mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }

  return <Outlet />;
}

/**
 * Deliberately almost nothing.
 *
 * This is on screen for the length of one refresh call. A spinner that appears
 * and vanishes inside ~100 ms reads as a flicker, so the page holds its
 * background and says nothing until there is something true to say.
 */
function SessionSplash() {
  return (
    <div
      className="flex min-h-screen items-center justify-center bg-app"
      role="status"
      aria-live="polite"
    >
      <span className="sr-only">Restoring your session</span>
    </div>
  );
}
