import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/http';
import { TwoFactorPanel } from './TwoFactorPanel';

vi.mock('@/api/generated/auth/auth', () => ({
  beginTwoFactorEnrolment: vi.fn(),
  confirmTwoFactorEnrolment: vi.fn(),
  disableTwoFactor: vi.fn(),
}));

const { beginTwoFactorEnrolment, confirmTwoFactorEnrolment, disableTwoFactor } = await import(
  '@/api/generated/auth/auth'
);

/**
 * A-029's endpoints, given a screen — so these cases are about the two things
 * the screen decides that the API does not.
 *
 * The first is that **the panel never asserts a state it cannot read.** `Me`
 * carries no `twoFactorEnabled`, so an opening claim of "off" would be a
 * security statement made on no evidence. The 409 path is how the truth
 * arrives, and it has to read as state rather than as a failure.
 *
 * The second is that **recovery codes cannot be walked past.** They are shown
 * once and stored hashed; if the acknowledgement were skippable the panel would
 * be handing someone a locked account for the day they lose a phone.
 */

/** `is()` matches on the tail of the problem `type`, so a bare code is enough. */
function problem(status: number, type: string): ApiError {
  return new ApiError(status, { status, type: `https://edutrack/${type}`, title: type }, new Response());
}

beforeEach(() => vi.resetAllMocks());

it('claims neither on nor off before it has asked', () => {
  render(<TwoFactorPanel />);

  // Both doors, no verdict. The absent text is the point of the case.
  expect(screen.getByRole('button', { name: /set up two-factor/i })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /already using it/i })).toBeInTheDocument();
  expect(screen.queryByText(/is on\./i)).not.toBeInTheDocument();
  expect(screen.queryByText(/is off/i)).not.toBeInTheDocument();
});

describe('finding out that it is already on', () => {
  it("reports a 409 as state, not as an error the user caused", async () => {
    vi.mocked(beginTwoFactorEnrolment).mockRejectedValue(
      problem(409, 'errors/two-factor-already-enabled'),
    );
    render(<TwoFactorPanel />);

    await userEvent.click(screen.getByRole('button', { name: /set up two-factor/i }));

    expect(await screen.findByText('Two-factor authentication is on.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Turn off' })).toBeInTheDocument();
    // Nothing in red — the user asked a question and got an answer.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('enrolment', () => {
  const setup = { data: { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x' } };

  it('shows the key, then the codes, and will not let the codes be skipped', async () => {
    vi.mocked(beginTwoFactorEnrolment).mockResolvedValue(setup);
    vi.mocked(confirmTwoFactorEnrolment).mockResolvedValue({
      data: { recoveryCodes: ['aaaa-1111', 'bbbb-2222'] },
    });
    render(<TwoFactorPanel />);

    await userEvent.click(screen.getByRole('button', { name: /set up two-factor/i }));
    expect(await screen.findByLabelText('Setup key')).toHaveTextContent('JBSWY3DPEHPK3PXP');

    const submit = screen.getByRole('button', { name: /turn on two-factor/i });
    // Five digits is not a code — the server's own pattern is ^\d{6}$, and
    // failing that round trip is a worse way to learn it.
    await userEvent.type(screen.getByLabelText('Six-digit code'), '12345');
    expect(submit).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Six-digit code'), '6');
    await userEvent.click(submit);

    expect(await screen.findByText('aaaa-1111')).toBeInTheDocument();
    expect(confirmTwoFactorEnrolment).toHaveBeenCalledWith({ code: '123456' });

    const done = screen.getByRole('button', { name: 'Done' });
    expect(done).toBeDisabled();

    await userEvent.click(screen.getByRole('checkbox'));
    expect(done).toBeEnabled();
    await userEvent.click(done);

    expect(screen.getByText('Two-factor authentication is on.')).toBeInTheDocument();
  });

  it('says what a rejected code means rather than repeating the failure', async () => {
    vi.mocked(beginTwoFactorEnrolment).mockResolvedValue(setup);
    vi.mocked(confirmTwoFactorEnrolment).mockRejectedValue(
      problem(400, 'errors/invalid-totp-code'),
    );
    render(<TwoFactorPanel />);

    await userEvent.click(screen.getByRole('button', { name: /set up two-factor/i }));
    await userEvent.type(await screen.findByLabelText('Six-digit code'), '000000');
    await userEvent.click(screen.getByRole('button', { name: /turn on two-factor/i }));

    // The 30-second window is the actual cause most of the time, and it is not
    // something the user can guess from "invalid".
    expect(await screen.findByRole('alert')).toHaveTextContent(/expire every 30 seconds/i);
    // Still on the step, with the key in view — not thrown back to the start.
    expect(screen.getByLabelText('Setup key')).toBeInTheDocument();
  });
});

describe('turning it off', () => {
  it('sends the password and refuses to submit without one', async () => {
    vi.mocked(disableTwoFactor).mockResolvedValue(undefined);
    render(<TwoFactorPanel />);

    await userEvent.click(screen.getByRole('button', { name: /already using it/i }));

    const turnOff = screen.getByRole('button', { name: 'Turn off' });
    expect(turnOff).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Your password'), 'hunter2');
    await userEvent.click(turnOff);

    await waitFor(() => expect(disableTwoFactor).toHaveBeenCalledWith({ password: 'hunter2' }));
  });

  it('names the wrong password, because the caller is already authenticated', async () => {
    vi.mocked(disableTwoFactor).mockRejectedValue(problem(401, 'errors/invalid-credentials'));
    render(<TwoFactorPanel />);

    await userEvent.click(screen.getByRole('button', { name: /already using it/i }));
    await userEvent.type(screen.getByLabelText('Your password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Turn off' }));

    // No account to enumerate here — vagueness would only send them to
    // re-check something they typed correctly.
    expect(await screen.findByRole('alert')).toHaveTextContent(/password is not correct/i);
  });

  it('does not report a failure when it was already off', async () => {
    vi.mocked(disableTwoFactor).mockRejectedValue(problem(400, 'errors/two-factor-not-enrolled'));
    render(<TwoFactorPanel />);

    await userEvent.click(screen.getByRole('button', { name: /already using it/i }));
    await userEvent.type(screen.getByLabelText('Your password'), 'hunter2');
    await userEvent.click(screen.getByRole('button', { name: 'Turn off' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/not currently on/i);
    // Back to the resting state, which is the state they are already in.
    expect(screen.getByRole('button', { name: /set up two-factor/i })).toBeInTheDocument();
  });
});
