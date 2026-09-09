import * as React from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'

import { portalRedeemCredential, portalSetPassword } from '@/api/generated/portal/portal'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

import { PortalAuthAlert, PortalAuthCard, PortalAuthField } from './PortalAuthUi'
import { usePortalAuthStore } from './portalAuthStore'
import { PORTAL_INVALID_CREDENTIAL_TOKEN, PORTAL_TOO_MANY_ATTEMPTS } from './portalProblemTypes'

/**
 * CP-01 · the credential link's landing page, and the forced-change screen
 * `PortalRequireAuth` sends every account here for until it sets its own
 * password. One page rather than two, because the two paths converge on the
 * exact same form and differ only in how the caller arrived signed in.
 *
 * ## Two ways to reach here, resolved by {@link PortalAuthStatus}
 *
 * - **`?token=…`, not yet signed in.** `ClientCredentialTokens`' own
 *   javadoc: a newly created or reset account's password is 32 random bytes
 *   nobody knows — there is nothing to type on a first sign-in. Redemption
 *   authenticates in its own right, so the effect below calls it once on
 *   mount and treats the result as a login.
 * - **Already signed in, `mustChangePassword` still true.** `PortalRequireAuth`
 *   redirects here without a token. The form below is reachable directly,
 *   asking only for the new password — see `PortalSetPasswordRequest`'s own
 *   note on why there is no "current password" field to fill in either way.
 */
export function PortalSetPasswordPage() {
  const status = usePortalAuthStore((state) => state.status)
  const signIn = usePortalAuthStore((state) => state.signIn)
  const clearRequirement = usePortalAuthStore((state) => state.clearPasswordChangeRequirement)
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')

  const [redeeming, setRedeeming] = React.useState(Boolean(token) && status !== 'authenticated')
  const [redeemError, setRedeemError] = React.useState<string | null>(null)

  React.useEffect(() => {
    if (!token || status === 'authenticated') return
    let cancelled = false
    setRedeeming(true)
    portalRedeemCredential({ token })
      .then((response) => {
        if (!cancelled) signIn(response.data)
      })
      .catch((error) => {
        if (!cancelled) setRedeemError(messageForRedeem(error))
      })
      .finally(() => {
        if (!cancelled) setRedeeming(false)
      })
    return () => {
      cancelled = true
    }
    // Runs once per token — a link is single-use, and re-redeeming it on a
    // re-render would burn the second attempt against the same guard the
    // server uses to stop two tabs racing one link.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  if (redeeming) {
    return (
      <PortalAuthCard title="Signing you in…">
        <p className="text-sm text-content-muted" role="status">
          Verifying your link.
        </p>
      </PortalAuthCard>
    )
  }

  if (redeemError) {
    return (
      <PortalAuthCard title="Link no longer valid">
        <PortalAuthAlert>{redeemError}</PortalAuthAlert>
        <p className="mt-4 text-sm text-content-muted">
          Ask your account manager to send a new link.
        </p>
      </PortalAuthCard>
    )
  }

  if (status !== 'authenticated') {
    // No token and not signed in — nothing this page can do.
    navigate('/portal/login', { replace: true })
    return null
  }

  return (
    <SetPasswordForm
      onDone={() => {
        clearRequirement()
        navigate('/portal/onboarding', { replace: true })
      }}
    />
  )
}

interface Values {
  newPassword: string
  confirmPassword: string
}

function SetPasswordForm({ onDone }: { onDone: () => void }) {
  const [formError, setFormError] = React.useState<string | null>(null)
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ defaultValues: { newPassword: '', confirmPassword: '' } })

  const newPassword = watch('newPassword')

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await portalSetPassword({ newPassword: values.newPassword })
      onDone()
    } catch (error) {
      setFormError(
        error instanceof ApiError
          ? error.problem.detail ?? 'Could not set your password. Try again.'
          : 'Could not reach the server. Check your connection and try again.',
      )
    }
  })

  return (
    <PortalAuthCard
      title="Set your password"
      description="Choose a password for your portal account. You'll use it to sign in from now on."
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {formError ? <PortalAuthAlert>{formError}</PortalAuthAlert> : null}

        <PortalAuthField id="new-password" label="New password" error={errors.newPassword?.message}>
          {(aria) => (
            <Input
              {...aria}
              {...register('newPassword', {
                required: 'Choose a password',
                minLength: { value: 8, message: 'At least 8 characters' },
                maxLength: { value: 128, message: 'At most 128 characters' },
              })}
              type="password"
              autoComplete="new-password"
              autoFocus
            />
          )}
        </PortalAuthField>

        <PortalAuthField
          id="confirm-password"
          label="Confirm password"
          error={errors.confirmPassword?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('confirmPassword', {
                required: 'Re-enter your password',
                validate: (value) => value === newPassword || 'Passwords do not match',
              })}
              type="password"
              autoComplete="new-password"
            />
          )}
        </PortalAuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Saving…' : 'Set password'}
        </Button>
      </form>
    </PortalAuthCard>
  )
}

function messageForRedeem(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.'
  }
  if (error.is(PORTAL_INVALID_CREDENTIAL_TOKEN)) {
    return 'This link has expired or has already been used.'
  }
  if (error.is(PORTAL_TOO_MANY_ATTEMPTS) || error.status === 429) {
    return 'Too many attempts. Wait a minute and try again.'
  }
  return 'This link could not be verified.'
}
