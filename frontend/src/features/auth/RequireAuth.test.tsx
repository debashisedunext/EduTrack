import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';

import { RequireAuth } from './RequireAuth';
import { initialAuthState, useAuthStore } from './authStore';
import type { Session } from '@/api/generated/model/session';

/**
 * The route guard — A-030.
 *
 * This is convenience rather than security: A-033 to A-036 decide what a request
 * may do, server-side. What these cases pin down is that the *client* never
 * leaks a protected screen, never throws away where the user was going, and
 * never flashes the login form at someone who is signed in.
 */

const session = (overrides: Partial<Session> = {}): Session => ({
  accessToken: 'test.token',
  expiresIn: 900,
  landingRoute: '/dashboard',
  user: { id: 7, displayName: 'Ravi Kumar' },
  ...overrides,
});

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route element={<RequireAuth />}>
          <Route path="/change-password" element={<div>change password screen</div>} />
          <Route path="/tickets/:id" element={<div>protected screen</div>} />
        </Route>
        <Route path="/login" element={<WhereFrom />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** The login screen, reduced to the one thing the guard has to hand it. */
function WhereFrom() {
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
  return <div data-testid="login">from:{from ?? 'none'}</div>;
}

beforeEach(() => useAuthStore.setState(initialAuthState));

it('shows neither the screen nor the login form before the session is known', () => {
  // `status: 'unknown'` is the startup refresh still being in flight. Treating
  // it as signed out would flash the login form at every returning user on
  // every reload — and the redirect would discard the URL they asked for.
  renderAt('/tickets/PRJ-0001');

  expect(screen.queryByText('protected screen')).not.toBeInTheDocument();
  expect(screen.queryByTestId('login')).not.toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Restoring your session');
});

it('redirects an anonymous visitor to the login screen, carrying where they were going', () => {
  useAuthStore.getState().signOut();

  renderAt('/tickets/PRJ-0001');

  // Without `from`, signing in lands on the dashboard and the user has to find
  // their way back to the link they followed.
  expect(screen.getByTestId('login')).toHaveTextContent('from:/tickets/PRJ-0001');
});

it('renders the screen once a session exists', () => {
  useAuthStore.getState().signIn(session());

  renderAt('/tickets/PRJ-0001');

  expect(screen.getByText('protected screen')).toBeInTheDocument();
});

describe('the forced password change', () => {
  it('closes every other route, not just the dashboard', () => {
    // A-026. Gating on one screen leaves `/tickets` open to anyone who types
    // the URL, and the backend's `PasswordChangeGate` becomes the only thing
    // stopping them — which surfaces as an unexplained error page.
    useAuthStore.getState().signIn(session({ mustChangePassword: true }));

    renderAt('/tickets/PRJ-0001');

    expect(screen.getByText('change password screen')).toBeInTheDocument();
    expect(screen.queryByText('protected screen')).not.toBeInTheDocument();
  });

  it('does not redirect the change-password screen to itself', () => {
    // The loop this avoids renders nothing at all and looks like a blank page.
    useAuthStore.getState().signIn(session({ mustChangePassword: true }));

    renderAt('/change-password');

    expect(screen.getByText('change password screen')).toBeInTheDocument();
  });
});
