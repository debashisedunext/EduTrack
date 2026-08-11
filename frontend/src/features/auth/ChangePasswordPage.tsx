import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';

import { changeOwnPassword } from '@/api/generated/auth/auth';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toast } from '@/components/ui/use-toast';

import { AuthAlert, AuthField } from './AuthField';
import { AuthCard } from './AuthCard';
import { PasswordStrengthMeter } from './PasswordStrengthMeter';
import { useAuthStore } from './authStore';
import {
  INVALID_CREDENTIALS,
  PASSWORD_REUSED,
  PASSWORD_UNCHANGED,
  VALIDATION,
} from './problemTypes';
import { MAX_LENGTH, meetsPolicy, MIN_LENGTH } from './passwordPolicy';

/**
 * S-03 Change Password — A-030, serving both of A-026's cases.
 *
 * A forced first-login change and a voluntary change from the profile menu are
 * the same three fields and the same endpoint. What differs is whether there is
 * a way out: the forced variant has no cancel, because `RequireAuth` would
 * redirect straight back and a button that visibly does nothing is worse than no
 * button. The heading changes with it, so a user who did not choose to be here
 * is told why.
 *
 * ## Why `mustChangePassword` is not read as a prop
 *
 * It comes from the store, so both routes render the same component and the
 * gate cannot disagree with the screen. Passing it in would let a caller mount
 * the dismissible variant for a user the server has locked into a change, and
 * the mismatch would only show up as a redirect loop.
 */
export function ChangePasswordPage() {
  const navigate = useNavigate();
  const forced = useAuthStore((state) => state.mustChangePassword);
  const landingRoute = useAuthStore((state) => state.landingRoute);
  const clearRequirement = useAuthStore((state) => state.clearPasswordChangeRequirement);

  const [formError, setFormError] = React.useState<string | null>(null);

  const {
    register,
    handleSubmit,
    watch,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<{ currentPassword: string; newPassword: string; confirmPassword: string }>({
    mode: 'onChange',
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  const newPassword = watch('newPassword');

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await changeOwnPassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      // The server clears `must_change_password` as part of this call; the store
      // is told so `RequireAuth` stops redirecting. Not re-fetching `/me` for
      // it — one field is known to have changed and a round trip to relearn it
      // would leave the guard blocking for the duration.
      clearRequirement();
      toast({ title: 'Password changed', variant: 'success' });
      navigate(landingRoute ?? '/dashboard', { replace: true });
    } catch (error) {
      setFormError(messageFor(error));
    }
  });

  return (
    <AuthCard
      title={forced ? 'Set your own password' : 'Change your password'}
      description={
        forced
          ? 'Your account was created with a temporary password. Choose your own before continuing.'
          : undefined
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {formError ? <AuthAlert>{formError}</AuthAlert> : null}

        <AuthField
          id="current-password"
          label={forced ? 'Temporary password' : 'Current password'}
          error={errors.currentPassword?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('currentPassword', { required: 'Enter your current password' })}
              type="password"
              autoComplete="current-password"
              autoFocus
            />
          )}
        </AuthField>

        <AuthField id="new-password" label="New password" error={errors.newPassword?.message}>
          {(aria) => (
            <Input
              {...aria}
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
                validate: (value) =>
                  value === getValues('newPassword') || 'Both passwords must match',
              })}
              type="password"
              autoComplete="new-password"
            />
          )}
        </AuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Saving…' : 'Change password'}
        </Button>

        {/* No cancel on the forced variant — see the note at the top. */}
        {!forced ? (
          <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
            Cancel
          </Button>
        ) : null}
      </form>
    </AuthCard>
  );
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.';
  }
  if (error.is(INVALID_CREDENTIALS)) {
    // Naming the field is safe here and nowhere else on the login path: the
    // caller is already authenticated, so there is no account to enumerate and
    // "something was wrong" would send them to re-check the new password they
    // typed correctly.
    return 'Your current password is not correct.';
  }
  if (error.is(PASSWORD_UNCHANGED)) {
    return 'The new password is the same as your current one.';
  }
  if (error.is(PASSWORD_REUSED)) {
    return 'You have used this password before. Blueprint §10.3 blocks reuse of your last three.';
  }
  if (error.is(VALIDATION)) {
    const first = Object.values(error.fieldErrors)[0]?.[0];
    return first ?? 'That password does not meet the policy.';
  }
  return 'Could not change your password. Try again.';
}
