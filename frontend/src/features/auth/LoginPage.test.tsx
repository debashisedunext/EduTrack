import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';

import { server } from '@/mocks/server';

import { LoginPage } from './LoginPage';
import { initialAuthState, useAuthStore } from './authStore';

/**
 * S-01 and the S-04 challenge — A-030.
 *
 * The mock server's login handler (Debashis's, `mocks/handlers/rest.ts`) only
 * ever answers success or `invalid-credentials`, which is the right default for
 * every other suite in the repo. The states that make this screen worth testing —
 * a locked account, a two-factor challenge — are overridden per test with
 * `server.use` rather than added to his handlers, so nothing outside this file
 * changes behaviour and Stream D's mock stays theirs.
 */

const LOGIN_URL = '*/auth/login';

function problem(status: number, type: string, title: string) {
  return HttpResponse.json(
    { type: `https://edutrack/errors/${type}`, title, status },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** Renders the login screen and reports wherever it navigates to. */
function renderLogin(initialEntry: string | { pathname: string; state: unknown } = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialEntry as string]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<LandedOn />} />
      </Routes>
    </MemoryRouter>,
  );
}

function LandedOn() {
  const location = useLocation();
  return <div data-testid="landed-on">{location.pathname}</div>;
}

const landedOn = () => screen.getByTestId('landed-on').textContent;

async function signIn(username = 'ravi.kumar', password = 'Correct-Horse-1!') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Username or email'), username);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  return user;
}

beforeEach(() => {
  useAuthStore.setState(initialAuthState);
  localStorage.clear();
});

describe('signing in', () => {
  it('lands on the route the server chose for this role', async () => {
    // A-031. The destination is `landingRoute` from the session — the frontend
    // deliberately holds no role→route map of its own.
    server.use(
      http.post(LOGIN_URL, () =>
        HttpResponse.json({
          data: {
            accessToken: 'test.token',
            expiresIn: 900,
            landingRoute: '/my-tasks',
            user: { id: 7, displayName: 'Ravi Kumar' },
          },
        }),
      ),
    );

    renderLogin();
    await signIn();

    await waitFor(() => expect(landedOn()).toBe('/my-tasks'));
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('returns to the page that bounced the user here, not the landing route', async () => {
    // Following a link to a ticket and being asked to sign in should end at that
    // ticket. Dropping the user on the dashboard makes the login feel like it
    // undid the click.
    server.use(
      http.post(LOGIN_URL, () =>
        HttpResponse.json({
          data: {
            accessToken: 'test.token',
            expiresIn: 900,
            landingRoute: '/dashboard',
            user: { id: 7, displayName: 'Ravi Kumar' },
          },
        }),
      ),
    );

    renderLogin({ pathname: '/login', state: { from: { pathname: '/tickets/PRJ-0001' } } });
    await signIn();

    await waitFor(() => expect(landedOn()).toBe('/tickets/PRJ-0001'));
  });
});

describe('the failure message never names a field', () => {
  it.each([
    ['an unknown username', 'nobody.here'],
    ['a wrong password', 'ravi.kumar'],
  ])('shows the same generic message for %s', async (_case, username) => {
    // A-020 makes these byte-identical on the wire and `AuthLoginIT` asserts it.
    // All of that is undone by a screen that renders two different sentences, so
    // this asserts the exact string rather than merely "an error appeared".
    server.use(
      http.post(LOGIN_URL, () =>
        problem(401, 'invalid-credentials', 'Username or password is incorrect'),
      ),
    );

    renderLogin();
    await signIn(username);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Username or password is incorrect.');
    expect(alert.textContent).not.toMatch(/username is|no account|user not found|password is wrong/i);
  });

  it('explains a locked account, which is safe to name', async () => {
    // A-021 only reports the lock *after* correct credentials, so it can only be
    // seen by someone who already has the password. Withholding it would leave a
    // user retrying a correct password against a 15-minute lock.
    server.use(
      http.post(LOGIN_URL, () => problem(423, 'account-locked', 'Account locked')),
    );

    renderLogin();
    await signIn();

    expect(await screen.findByRole('alert')).toHaveTextContent(/locked for 15 minutes/i);
  });

  it('does not claim wrong credentials when the server is unreachable', async () => {
    // Rendering "wrong password" for a dropped connection costs the user one of
    // their five attempts' worth of confidence and sends them to reset a
    // password that was fine.
    server.use(http.post(LOGIN_URL, () => HttpResponse.error()));

    renderLogin();
    await signIn();

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not reach the server/i);
  });
});

describe('the two-factor challenge', () => {
  /** Refuses the password alone, then accepts whatever the second call sends. */
  function twoFactorGate(onSecondCall: (body: Record<string, unknown>) => void) {
    server.use(
      http.post(LOGIN_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        if (!body.totpCode && !body.recoveryCode) {
          return problem(401, 'two-factor-required', 'Two-factor authentication required');
        }
        onSecondCall(body);
        return HttpResponse.json({
          data: {
            accessToken: 'test.token',
            expiresIn: 900,
            landingRoute: '/dashboard',
            user: { id: 7, displayName: 'Ravi Kumar' },
          },
        });
      }),
    );
  }

  it('asks for a code instead of throwing the user back to a cleared form', async () => {
    twoFactorGate(() => {});

    renderLogin();
    await signIn();

    expect(await screen.findByLabelText('Verification code')).toBeInTheDocument();
    // The password field is gone, which is the point: it was already correct,
    // and asking for it again reads as a rejection.
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('sends six digits as totpCode', async () => {
    const bodies: Record<string, unknown>[] = [];
    twoFactorGate((body) => bodies.push(body));

    renderLogin();
    const user = await signIn();

    await user.type(await screen.findByLabelText('Verification code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ totpCode: '123456' });
    expect(bodies[0].recoveryCode).toBeUndefined();
  });

  it('sends a recovery code as recoveryCode, never as totpCode', async () => {
    // `totpCode` carries `^\d{6}$` in the contract, so a recovery code posted in
    // it is refused by Bean Validation before the service is reached — one of
    // the four bugs A-029's tests caught. Putting it there from this end would
    // reintroduce the same failure from the client side.
    const bodies: Record<string, unknown>[] = [];
    twoFactorGate((body) => bodies.push(body));

    renderLogin();
    const user = await signIn();

    await user.type(await screen.findByLabelText('Verification code'), '4KDP-9TXM');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ recoveryCode: '4KDP-9TXM' });
    expect(bodies[0].totpCode).toBeUndefined();
  });

  it('reports a wrong code without ending the challenge', async () => {
    server.use(
      http.post(LOGIN_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        return body.totpCode || body.recoveryCode
          ? problem(401, 'invalid-totp-code', 'Invalid code')
          : problem(401, 'two-factor-required', 'Two-factor authentication required');
      }),
    );

    renderLogin();
    const user = await signIn();

    await user.type(await screen.findByLabelText('Verification code'), '000000');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/code is not valid/i);
    // Still on the challenge — a wrong code must not cost the password again.
    expect(screen.getByLabelText('Verification code')).toBeInTheDocument();
  });
});

describe('remembering the username', () => {
  it('pre-fills the username but never the password', async () => {
    server.use(
      http.post(LOGIN_URL, () =>
        problem(401, 'invalid-credentials', 'Username or password is incorrect'),
      ),
    );

    renderLogin();
    const user = userEvent.setup();
    // Opt-in, not opt-out: the box starts unchecked, so a shared machine does
    // not quietly accumulate the usernames of everyone who has signed in on it.
    await user.click(screen.getByRole('checkbox', { name: /remember my username/i }));
    await signIn('ravi.kumar');
    await screen.findByRole('alert');

    // A full teardown, not a second `render` into the same container — the
    // point is that the username survives the page being gone, and three login
    // forms stacked in one DOM would prove nothing about any of them.
    cleanup();
    renderLogin();

    // Written on submit rather than on success, so a failed attempt still saves
    // the retyping on the retry — which is the attempt where it matters most.
    expect(await screen.findByLabelText('Username or email')).toHaveValue('ravi.kumar');
    // The password is never persisted, and `autoComplete="current-password"`
    // leaves that to the browser's own credential store where it belongs.
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('forgets the username when the box is cleared', async () => {
    localStorage.setItem('edutrack.rememberedUsername', 'ravi.kumar');
    server.use(
      http.post(LOGIN_URL, () =>
        problem(401, 'invalid-credentials', 'Username or password is incorrect'),
      ),
    );

    renderLogin();
    const user = userEvent.setup();
    await user.click(screen.getByRole('checkbox', { name: /remember my username/i }));
    await user.type(screen.getByLabelText('Password'), 'Correct-Horse-1!');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('alert');
    expect(localStorage.getItem('edutrack.rememberedUsername')).toBeNull();
  });
});
