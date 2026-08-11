import * as React from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';

import { login } from '@/api/generated/auth/auth';
import type { LoginRequest } from '@/api/generated/model/loginRequest';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import { AuthAlert, AuthField, AuthNotice } from './AuthField';
import { AuthCard } from './AuthCard';
import { useAuthStore } from './authStore';
import {
  ACCOUNT_LOCKED,
  INVALID_CREDENTIALS,
  INVALID_TOTP_CODE,
  TWO_FACTOR_REQUIRED,
} from './problemTypes';

/**
 * S-01 Login — A-030.
 *
 * ## The generic error is the whole screen's hardest requirement
 *
 * §10.1 and A-020 make unknown username, wrong password and deactivated account
 * return byte-identical responses, and A-020's `AuthLoginIT` asserts that
 * byte-for-byte. All of that work is undone by one screen that renders three
 * different sentences, so this file has exactly one message for the whole
 * family, and the message names neither field. Anyone tempted to add "check your
 * username" for friendliness would be rebuilding the staff directory the backend
 * spent a decoy hash to prevent.
 *
 * `account-locked` is separate and that is deliberate rather than inconsistent:
 * A-021 only reports it *after* correct credentials, so it can only be seen by
 * someone who already knows the password. It leaks nothing, and withholding it
 * would leave a user retrying every 30 seconds against a 15-minute lock with no
 * idea why the right password stopped working.
 *
 * ## Two-factor is a step, not an error
 *
 * `two-factor-required` comes back with a 401, but nothing has gone wrong — the
 * password was right. Sending the user back to a cleared form (the default if
 * you treat every 401 alike) means retyping a password that was already correct
 * and looks like a rejection. The credentials are held in component state for
 * the second call and never rendered, never stored.
 */
export function LoginPage() {
  const [challenge, setChallenge] = React.useState<{ username: string; password: string } | null>(
    null,
  );

  return challenge ? (
    <TwoFactorChallenge credentials={challenge} onCancel={() => setChallenge(null)} />
  ) : (
    <CredentialsForm onTwoFactorRequired={setChallenge} />
  );
}

/**
 * Where to land after a successful sign-in.
 *
 * `from` — the protected URL that bounced the user here — wins over the role's
 * landing route. Someone who followed a link to a specific ticket wants that
 * ticket, not the dashboard, and losing it makes the login feel like it undid
 * the click. `landingRoute` is A-031's, decided server-side, and is the answer
 * when there is no such URL.
 */
function useAfterLogin() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;

  return React.useCallback(
    (landingRoute: string | undefined) => {
      // `replace`, so Back from the landing page does not return to a login
      // screen the user has already cleared.
      navigate(from ?? landingRoute ?? '/dashboard', { replace: true });
    },
    [navigate, from],
  );
}

/** The username to pre-fill, if this browser was asked to remember one. */
const REMEMBERED_USERNAME_KEY = 'edutrack.rememberedUsername';

interface CredentialsValues {
  username: string;
  password: string;
  rememberMe: boolean;
}

function CredentialsForm({
  onTwoFactorRequired,
}: {
  onTwoFactorRequired: (credentials: { username: string; password: string }) => void;
}) {
  const signIn = useAuthStore((state) => state.signIn);
  const afterLogin = useAfterLogin();
  const location = useLocation();
  const [formError, setFormError] = React.useState<string | null>(null);

  // Set by `ResetPasswordPage` on its way here. Without it the user completes a
  // reset and is dropped on a login screen with no acknowledgement that it
  // worked — which reads as the reset having failed silently.
  const justReset = Boolean((location.state as { passwordReset?: boolean } | null)?.passwordReset);

  const remembered = React.useMemo(() => localStorage.getItem(REMEMBERED_USERNAME_KEY) ?? '', []);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<CredentialsValues>({
    defaultValues: { username: remembered, password: '', rememberMe: Boolean(remembered) },
  });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);

    // Written before the request, not after a success: a user whose password is
    // wrong still wants their username back on the next attempt, and that is the
    // attempt where it saves the most typing.
    if (values.rememberMe) {
      localStorage.setItem(REMEMBERED_USERNAME_KEY, values.username);
    } else {
      localStorage.removeItem(REMEMBERED_USERNAME_KEY);
    }

    try {
      const response = await login({ username: values.username, password: values.password });
      signIn(response.data);
      afterLogin(response.data.landingRoute);
    } catch (error) {
      if (error instanceof ApiError && error.is(TWO_FACTOR_REQUIRED)) {
        onTwoFactorRequired({ username: values.username, password: values.password });
        return;
      }
      setFormError(messageFor(error));
    }
  });

  return (
    <AuthCard
      title="Sign in"
      footer={
        <span className="text-content-muted">
          Trouble signing in? Contact your administrator.
        </span>
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {justReset && !formError ? (
          <AuthNotice>Your password has been changed. Sign in with it.</AuthNotice>
        ) : null}
        {formError ? <AuthAlert>{formError}</AuthAlert> : null}

        <AuthField id="username" label="Username or email" error={errors.username?.message}>
          {(aria) => (
            <Input
              {...aria}
              {...register('username', { required: 'Enter your username or email' })}
              // The one screen where this matters most: a mistyped first
              // character costs a failed attempt against a five-attempt lockout.
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoFocus={!remembered}
            />
          )}
        </AuthField>

        <AuthField
          id="password"
          label="Password"
          error={errors.password?.message}
          labelSuffix={
            <Link
              to="/forgot-password"
              className="text-caption text-primary underline-offset-2 hover:underline"
            >
              Forgot password?
            </Link>
          }
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('password', { required: 'Enter your password' })}
              type="password"
              autoComplete="current-password"
              autoFocus={Boolean(remembered)}
            />
          )}
        </AuthField>

        <label className="flex items-center gap-2 text-sm text-content">
          <input
            type="checkbox"
            {...register('rememberMe')}
            className="h-4 w-4 rounded-[4px] border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
          />
          Remember my username
        </label>
        {/*
          "Remember my username", not "Remember me" — and the difference is not
          pedantry. A-025 fixes the session at 30 minutes idle and 12 hours
          absolute, server-side; A-024 refuses to slide the refresh window. The
          frontend therefore *cannot* keep anyone signed in for longer, so a
          checkbox labelled "Remember me" would promise something no code here
          can deliver, and the user would read being signed out next morning as
          a bug. What this can honestly do is save the typing, so that is what
          it says. Recorded as a deliberate reading of S-01's field, not an
          oversight — see this feature's README.
        */}

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </AuthCard>
  );
}

interface TwoFactorValues {
  code: string;
}

/**
 * S-04 — the second factor, reached only after the password was accepted.
 *
 * One field takes both a six-digit TOTP code and a recovery code, because from
 * the user's side they answer the same question. They are sent as *different*
 * request fields: `totpCode` carries `^\d{6}$` in the contract, so a recovery
 * code posted in it is refused by Bean Validation before the service sees it —
 * that was one of the four bugs A-029's tests caught, and putting a recovery
 * code in that field here would reintroduce it from the other end.
 */
function TwoFactorChallenge({
  credentials,
  onCancel,
}: {
  credentials: { username: string; password: string };
  onCancel: () => void;
}) {
  const signIn = useAuthStore((state) => state.signIn);
  const afterLogin = useAfterLogin();
  const [formError, setFormError] = React.useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<TwoFactorValues>({ defaultValues: { code: '' } });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const entered = values.code.trim();

    // Six digits is a TOTP code; anything else is treated as a recovery code.
    // Deciding here rather than making the user pick a mode keeps one field on
    // screen — and the server would reject a wrong guess either way, so the
    // classification costs nothing if it is wrong.
    const request: LoginRequest = /^\d{6}$/.test(entered)
      ? { ...credentials, totpCode: entered }
      : { ...credentials, recoveryCode: entered };

    try {
      const response = await login(request);
      signIn(response.data);
      afterLogin(response.data.landingRoute);
    } catch (error) {
      setFormError(messageFor(error));
    }
  });

  return (
    <AuthCard
      title="Two-step verification"
      description="Enter the 6-digit code from your authenticator app."
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {formError ? <AuthAlert>{formError}</AuthAlert> : null}

        <AuthField
          id="totp-code"
          label="Verification code"
          hint="Lost your phone? Enter one of your recovery codes instead."
          error={errors.code?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('code', { required: 'Enter the code from your authenticator' })}
              // `one-time-code` is what lets iOS and Android offer the code from
              // the notification. `inputMode` rather than `type="number"`: a
              // recovery code is not numeric, and a number input silently
              // strips its letters and drops leading zeros from a TOTP code.
              autoComplete="one-time-code"
              inputMode="numeric"
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              autoFocus
              maxLength={32}
            />
          )}
        </AuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Verifying…' : 'Verify'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Back to sign in
        </Button>
      </form>
    </AuthCard>
  );
}

/**
 * One problem type in, one sentence out.
 *
 * Centralised so the set of things a user can be told is auditable in one place
 * — the moment this logic is inline in two handlers, the two drift and one of
 * them starts naming a field.
 */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    // A network failure, a proxy returning HTML, a CORS refusal. Saying "wrong
    // password" here would be a lie that costs the user a lockout attempt.
    return 'Could not reach the server. Check your connection and try again.';
  }

  if (error.is(ACCOUNT_LOCKED)) {
    return 'This account is locked for 15 minutes after five failed attempts. Your administrator has been notified.';
  }
  if (error.is(INVALID_TOTP_CODE)) {
    return 'That code is not valid. Codes change every 30 seconds — check your authenticator for the current one.';
  }
  if (error.is(INVALID_CREDENTIALS)) {
    // The one message for the entire family. Never name the field.
    return 'Username or password is incorrect.';
  }
  if (error.status === 429) {
    return 'Too many attempts. Wait a minute and try again.';
  }
  // Anything unmapped, including a 500. The problem's own `detail` is written
  // for humans, but it is the server's wording and may name internals, so it is
  // deliberately not rendered here.
  return 'Something went wrong signing you in. Try again.';
}
