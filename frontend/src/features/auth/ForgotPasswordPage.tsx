import * as React from 'react';
import { Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';

import { forgotPassword } from '@/api/generated/auth/auth';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import { AuthAlert, AuthField } from './AuthField';
import { AuthCard } from './AuthCard';

/**
 * S-02, first half — request a reset link. A-030.
 *
 * ## The confirmation is the same whether or not the address exists
 *
 * A-027 makes `POST /auth/forgot-password` return 202 unconditionally, for the
 * reason its javadoc gives: a different answer for unknown addresses is a
 * user-enumeration oracle, and this endpoint is unauthenticated, so anyone on
 * the internet could walk a list of addresses through it and learn who works
 * here. The screen has to hold the same line — a "no account with that email"
 * message would hand back the oracle the endpoint refused to be, and it is a
 * tempting message to add because it is genuinely more helpful to the one user
 * who mistyped.
 *
 * The wording therefore describes what was done ("if that address belongs to an
 * account, a link is on its way") rather than asserting an email was sent, which
 * would be a lie half the time.
 */
export function ForgotPasswordPage() {
  const [sent, setSent] = React.useState(false);
  const [formError, setFormError] = React.useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<{ email: string }>({ defaultValues: { email: '' } });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await forgotPassword({ email: values.email });
      setSent(true);
    } catch (error) {
      // 429 is the only failure a caller can meaningfully act on: A-027 rate
      // limits requests per address, and the limiter is what stops this
      // endpoint being used to mailbomb someone.
      if (error instanceof ApiError && error.status === 429) {
        setFormError(
          'A link was requested for this address recently. Check your inbox, including spam, before trying again.',
        );
        return;
      }
      setFormError('Could not send the link. Try again in a moment.');
    }
  });

  if (sent) {
    return (
      <AuthCard
        title="Check your email"
        description="If that address belongs to an EduTrack account, a reset link is on its way. The link works once and expires in 30 minutes."
        footer={
          <Link to="/login" className="text-primary underline-offset-2 hover:underline">
            Back to sign in
          </Link>
        }
      >
        <p className="text-sm text-content-muted">
          Nothing after a few minutes? Check your spam folder, then ask your administrator to
          confirm the address on your account.
        </p>
      </AuthCard>
    );
  }

  return (
    <AuthCard
      title="Reset your password"
      description="We'll email you a link to set a new one."
      footer={
        <Link to="/login" className="text-primary underline-offset-2 hover:underline">
          Back to sign in
        </Link>
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {formError ? <AuthAlert>{formError}</AuthAlert> : null}

        <AuthField id="email" label="Email address" error={errors.email?.message}>
          {(aria) => (
            <Input
              {...aria}
              {...register('email', { required: 'Enter the email address on your account' })}
              type="email"
              autoComplete="email"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoFocus
            />
          )}
        </AuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Sending…' : 'Send reset link'}
        </Button>
      </form>
    </AuthCard>
  );
}
