import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'

import { changePortalPassword } from '@/api/generated/portal/portal'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

import { PortalAuthAlert, PortalAuthCard, PortalAuthField, PortalAuthNotice } from './PortalAuthUi'
import { usePortalAuthStore } from './portalAuthStore'
import {
  PORTAL_INVALID_CREDENTIALS,
  PORTAL_PASSWORD_UNCHANGED,
  PORTAL_WEAK_PASSWORD,
} from './portalProblemTypes'

/**
 * The forced password change, and the only screen a client who still holds
 * their issued temporary password can reach.
 *
 * ## Why this screen exists at all
 *
 * A portal login is created with a temporary password that a staff member
 * reads off the add-client dialog and hands over. The whole case for showing
 * a credential on a staff screen is that it buys exactly one session, and
 * that session can do nothing but replace it — so this form is not a
 * convenience, it is the other half of that bargain.
 *
 * **The enforcement is not here.** `PortalPasswordChangeGate` refuses every
 * portal route but `PATCH /portal/me/password` while the flag is set, so a
 * client who skips this screen — a stale tab, curl, a generated client — gets
 * a 403 rather than a portal. This component exists so an honest client sees
 * a form instead of an error page, which is the same division of labour
 * `RequireAuth` states for staff.
 *
 * ## No "skip for now"
 *
 * There is deliberately no way past this that is not a new password. Staff's
 * S-03 has the same property. Offering a dismissal would leave a credential
 * an operator read aloud sitting on a live account for as long as the client
 * kept dismissing it.
 *
 * ## The response replaces the session
 *
 * The portal has no refresh route, so the token in hand still carries the
 * must-change claim after the change is written — the gate would go on
 * refusing it for up to fifteen minutes. `changePortalPassword` answers with
 * a fresh session for exactly that reason, and this page signs in with it
 * rather than sending the client back to the login card.
 */
interface Values {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export function PortalChangePasswordPage() {
  const signIn = usePortalAuthStore((state) => state.signIn)
  const client = usePortalAuthStore((state) => state.client)
  const navigate = useNavigate()
  const [formError, setFormError] = React.useState<string | null>(null)

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<Values>({
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const response = await changePortalPassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      })
      // The successor session, minted after the write — see the docstring.
      // `signIn` puts it in `http.ts`'s token slot, so the very next call
      // carries a token with no must-change claim.
      signIn(response.data)
      navigate('/portal/choose', { replace: true })
    } catch (error) {
      setFormError(messageFor(error))
    }
  })

  return (
    <PortalAuthCard
      title="Choose your password"
      footer={
        <span className="text-content-muted">
          Signed in as {client?.username ?? 'your account'}.
        </span>
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <PortalAuthNotice>
          You are signed in with a temporary password. Choose your own to continue.
        </PortalAuthNotice>

        {formError ? <PortalAuthAlert>{formError}</PortalAuthAlert> : null}

        <PortalAuthField
          id="portal-current-password"
          label="Temporary password"
          error={errors.currentPassword?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('currentPassword', { required: 'Enter the password you were given' })}
              type="password"
              autoComplete="current-password"
              autoFocus
            />
          )}
        </PortalAuthField>

        <PortalAuthField
          id="portal-new-password"
          label="New password"
          error={errors.newPassword?.message}
          hint="At least 12 characters, with upper and lower case, a digit and a symbol."
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('newPassword', {
                required: 'Choose a new password',
                // Mirrors PortalPasswordRules so the client is told before a
                // round trip. The server enforces the same policy and is the
                // authority; this only saves them a refusal they can predict.
                minLength: { value: 12, message: 'Use at least 12 characters.' },
                validate: {
                  differs: (value) =>
                    value !== getValues('currentPassword') ||
                    'Choose a password different from the one you were given.',
                  upperAndLower: (value) =>
                    (/[a-z]/.test(value) && /[A-Z]/.test(value)) ||
                    'Use both upper and lower case letters.',
                  digit: (value) => /\d/.test(value) || 'Include a digit.',
                  symbol: (value) =>
                    /[^A-Za-z0-9]/.test(value) || 'Include a symbol, such as ! ? # or -.',
                },
              })}
              type="password"
              autoComplete="new-password"
            />
          )}
        </PortalAuthField>

        <PortalAuthField
          id="portal-confirm-password"
          label="Confirm new password"
          error={errors.confirmPassword?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('confirmPassword', {
                required: 'Type the new password again',
                validate: (value) =>
                  value === getValues('newPassword') || 'Both passwords must match.',
              })}
              type="password"
              autoComplete="new-password"
            />
          )}
        </PortalAuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Saving…' : 'Save and continue'}
        </Button>
      </form>
    </PortalAuthCard>
  )
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.'
  }
  if (error.is(PORTAL_INVALID_CREDENTIALS)) {
    // The server answers this for a wrong current password AND for an account
    // deactivated since sign-in, and deliberately does not say which. The
    // first is overwhelmingly the likelier, so the message names it without
    // asserting it is the only cause.
    return 'That temporary password is not right. Check it with whoever set up your account.'
  }
  if (error.is(PORTAL_PASSWORD_UNCHANGED)) {
    return 'Choose a password different from the one you were given.'
  }
  if (error.is(PORTAL_WEAK_PASSWORD)) {
    // `detail` names the one rule that failed, which is why the server sends
    // it and why rendering it beats restating the whole policy.
    return error.problem.detail ?? 'That password does not meet the policy.'
  }
  return 'Something went wrong saving your password. Try again.'
}
