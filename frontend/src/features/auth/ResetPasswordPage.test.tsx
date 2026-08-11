import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { server } from '@/mocks/server';

import { ResetPasswordPage } from './ResetPasswordPage';

/**
 * S-02, second half, and the strength meter A-030 names — the reset screen.
 *
 * `passwordPolicy.test.ts` proves the rules themselves. What is asserted here is
 * that they reach the user: the checklist tracks what is typed, the two fields
 * must agree, and the one rule the client cannot check — no reuse of the last
 * three — is reported from the server's answer rather than guessed at.
 */

const RESET_URL = '*/auth/reset-password';
const TOKEN = 'a'.repeat(32);

function problem(status: number, type: string, title: string) {
  return HttpResponse.json(
    { type: `https://edutrack/errors/${type}`, title, status },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

function renderReset(search = `?token=${TOKEN}`) {
  return render(
    <MemoryRouter initialEntries={[`/reset-password${search}`]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/login" element={<LoginStub />} />
      </Routes>
    </MemoryRouter>,
  );
}

function LoginStub() {
  const location = useLocation();
  const reset = (location.state as { passwordReset?: boolean } | null)?.passwordReset;
  return <div data-testid="login">{reset ? 'reset confirmed' : 'plain login'}</div>;
}

const requirement = (label: string) =>
  screen.getByText(label).closest('li') as HTMLElement;

it('answers a truncated link before asking for a password', async () => {
  // Email clients break long links across lines. Filling in two password fields
  // and losing them to a 400 is a worse way to find out.
  renderReset('');

  expect(await screen.findByText(/this link is not complete/i)).toBeInTheDocument();
  expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
});

describe('the strength meter', () => {
  it('ticks each requirement as it is satisfied', async () => {
    renderReset();
    const user = userEvent.setup();
    const field = screen.getByLabelText('New password');

    expect(requirement('A digit')).toHaveTextContent('Not met:');

    await user.type(field, 'correcthorse');
    expect(requirement('A lower-case letter')).toHaveTextContent('Met:');
    expect(requirement('At least 8 characters')).toHaveTextContent('Met:');
    expect(requirement('A digit')).toHaveTextContent('Not met:');

    await user.type(field, '1A!');
    for (const label of ['A digit', 'An upper-case letter', 'A symbol']) {
      expect(requirement(label)).toHaveTextContent('Met:');
    }
  });

  it('rates a password that passes every rule but is still obvious', async () => {
    // The reason compliance and strength are two readouts rather than one bar.
    renderReset();
    await userEvent.setup().type(screen.getByLabelText('New password'), 'Password1!');

    expect(screen.getByText('Very weak')).toBeInTheDocument();
    // …and the form still submits it, because the server would accept it. A
    // meter that blocked here would be frontend policy the backend does not share.
    expect(screen.getByRole('button', { name: 'Set new password' })).toBeEnabled();
  });
});

describe('submitting', () => {
  it('refuses two passwords that do not match, without calling the server', async () => {
    let called = false;
    server.use(
      http.post(RESET_URL, () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderReset();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('New password'), 'thicket-marrow-vane-1A!');
    await user.type(screen.getByLabelText('Confirm new password'), 'thicket-marrow-vane-1B!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));

    expect(await screen.findByText('Both passwords must match')).toBeInTheDocument();
    expect(called).toBe(false);
  });

  it('sends the token from the URL and hands the user to the login screen', async () => {
    // A reset proves control of the mailbox, not of the account, so A-027 issues
    // no session and this must not sign anyone in.
    const bodies: Record<string, unknown>[] = [];
    server.use(
      http.post(RESET_URL, async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderReset();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('New password'), 'thicket-marrow-vane-1A!');
    await user.type(screen.getByLabelText('Confirm new password'), 'thicket-marrow-vane-1A!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ token: TOKEN, newPassword: 'thicket-marrow-vane-1A!' });
    // The confirmation rides along, so the login screen can say the reset
    // worked rather than appearing for no visible reason.
    expect(await screen.findByTestId('login')).toHaveTextContent('reset confirmed');
  });

  it('reports reuse of an old password, which only the server can know', async () => {
    // The checklist deliberately does not pretend to check this — the hashes are
    // the server's, and a green tick beside a rejected password teaches the user
    // that the checklist is not to be trusted.
    server.use(http.post(RESET_URL, () => problem(400, 'password-reused', 'Password reused')));

    renderReset();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('New password'), 'thicket-marrow-vane-1A!');
    await user.type(screen.getByLabelText('Confirm new password'), 'thicket-marrow-vane-1A!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/used this password before/i);
  });

  it('gives one message for an expired, spent or invented link', async () => {
    // A-027 answers all three the same way. Distinguishing them would tell an
    // attacker holding a guessed token whether it was ever real.
    // 410, the status the running backend actually returns — the resource is
    // gone rather than the request being malformed. The component branches on
    // `type` and not on status, but a mock that disagrees with the server is a
    // mock that will eventually be believed over it.
    server.use(
      http.post(RESET_URL, () => problem(410, 'invalid-reset-token', 'Reset link is no longer valid')),
    );

    renderReset();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('New password'), 'thicket-marrow-vane-1A!');
    await user.type(screen.getByLabelText('Confirm new password'), 'thicket-marrow-vane-1A!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /expired or has already been used/i,
    );
  });
});
