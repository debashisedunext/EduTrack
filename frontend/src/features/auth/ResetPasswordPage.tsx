import * as React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';

import { resetPassword } from '@/api/generated/auth/auth';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import { AuthAlert, AuthField } from './AuthField';
import { AuthCard } from './AuthCard';
import { PasswordStrengthMeter } from './PasswordStrengthMeter';
import { INVALID_RESET_TOKEN, PASSWORD_REUSED, VALIDATION } from './problemTypes';
import { MAX_LENGTH, meetsPolicy, MIN_LENGTH } from './passwordPolicy';

/**
 * S-02, second half — set the new password from an emailed link. A-030.
 *
 * ## The token comes from the URL and is never shown
 *
 * A-027 makes it single-use with a 30-minute TTL, stored hashed. It arrives as
 * `?token=…`, is held in a hidden value, and is not rendered — a token echoed
 * into a visible field ends up in screenshots and support tickets, which is
 * where single-use tokens go to be used by someone else.
 *
 * ## A missing token is answered before the form, not after
 *
 * Someone who clicks a mangled link gets told so immediately rather than filling
 * in two password fields and losing them to a 400. The three ways a link fails —
 * expired, already used, never existed — are one message, matching A-027's
 * single `invalid-reset-token` problem: distinguishing them would tell an
 * attacker holding a guessed token whether it was ever real.
 */
export function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';

  if (!token) {
    return (
      <AuthCard
        title="This link is not complete"
        description="The reset link appears to have been cut short — email clients sometimes break long links across lines."
        footer={
          <Link to="/forgot-password" className="text-primary underline-offset-2 hover:underline">
            Request a new link
          </Link>
        }
      >
        <p className="text-sm text-content-muted">
          Copy the whole link from the email, or request a new one. Links expire 30 minutes
          after they are sent.
        </p>
      </AuthCard>
    );
  }

  return <ResetForm token={token} />;
}

interface ResetValues {
  newPassword: string;
  confirmPassword: string;
}

function ResetForm({ token }: { token: string }) {
  const navigate = useNavigate();
  const [formError, setFormError] = React.useState<string | null>(null);

  const {
    register,
    handleSubmit,
    watch,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<ResetValues>({
    // Validate on change once a field has been touched, so the checklist and the
    // "must match" error keep step with the meter. `onSubmit` alone would leave
    // a green checklist next to a form that refuses to submit.
    mode: 'onChange',
    defaultValues: { newPassword: '', confirmPassword: '' },
  });

  const newPassword = watch('newPassword');

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await resetPassword({ token, newPassword: values.newPassword });
      // Straight to the login screen rather than signing the user in: a reset
      // proves control of the mailbox, not of the account, and A-027 issues no
      // session for that reason. `state` carries the confirmation so the login
      // screen can say the reset worked instead of appearing for no reason.
      navigate('/login', { replace: true, state: { passwordReset: true } });
    } catch (error) {
      setFormError(messageFor(error));
    }
  });

  return (
    <AuthCard
      title="Choose a new password"
      footer={
        <Link to="/login" className="text-primary underline-offset-2 hover:underline">
          Back to sign in
        </Link>
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {formError ? <AuthAlert>{formError}</AuthAlert> : null}

        <AuthField id="new-password" label="New password" error={errors.newPassword?.message}>
          {(aria) => (
            <Input
              {...aria}
              // The meter is the field's description, so a screen-reader user
              // hears the rules with the input rather than having to hunt for
              // them after failing one.
              aria-describedby={[aria['aria-describedby'], 'password-requirements']
                .filter(Boolean)
                .join(' ')}
              {...register('newPassword', {
                required: 'Choose a password',
                maxLength: { value: MAX_LENGTH, message: `Use ${MAX_LENGTH} characters or fewer` },
                validate: (value) =>
                  meetsPolicy(value) || `Meet all ${MIN_LENGTH}-character rules listed below`,
              })}
              type="password"
              autoComplete="new-password"
              autoFocus
            />
          )}
        </AuthField>

        <PasswordStrengthMeter id="password-requirements" password={newPassword} />

        <AuthField
          id="confirm-password"
          label="Confirm new password"
          error={errors.confirmPassword?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('confirmPassword', {
                required: 'Type the password again',
                // Read through `getValues` rather than closing over `newPassword`:
                // the closure captures the value at render time, so a fast typist
                // gets "passwords do not match" against a password that does.
                validate: (value) =>
                  value === getValues('newPassword') || 'Both passwords must match',
              })}
              type="password"
              autoComplete="new-password"
            />
          )}
        </AuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Saving…' : 'Set new password'}
        </Button>
      </form>
    </AuthCard>
  );
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.';
  }
  if (error.is(INVALID_RESET_TOKEN)) {
    // Expired, already used, or never valid — one message, matching A-027.
    return 'This link has expired or has already been used. Request a new one.';
  }
  if (error.is(PASSWORD_REUSED)) {
    // The one rule the client cannot check: only the server holds the hashes,
    // and the checklist deliberately does not pretend otherwise.
    return 'You have used this password before. Blueprint §10.3 blocks reuse of your last three.';
  }
  if (error.is(VALIDATION)) {
    // The server's field messages are more specific than the checklist can be —
    // it names which class is missing.
    const first = Object.values(error.fieldErrors)[0]?.[0];
    return first ?? 'That password does not meet the policy.';
  }
  return 'Could not set your password. Try again.';
}
