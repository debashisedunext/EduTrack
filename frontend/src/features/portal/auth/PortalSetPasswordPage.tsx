import * as React from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'

import { describePortalCredentialLink, redeemPortalCredentialLink } from '@/api/generated/portal/portal'
import type { PortalCredentialLink } from '@/api/generated/model/portalCredentialLink'
import { ApiError, setAccessToken } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

import { PortalAuthAlert, PortalAuthCard, PortalAuthField } from './PortalAuthUi'
import { PORTAL_INVALID_CREDENTIAL_LINK } from './portalProblemTypes'

const PASSWORD_MIN_LENGTH = 12
const PASSWORD_COMPLEXITY = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).+$/

/**
 * A-130 · the credential link's landing page (CP-01) — redemption only.
 *
 * C-121's original version of this page also handled a "forced password
 * change after login" branch, reached when `PortalRequireAuth` redirected an
 * already-authenticated session here. That case does not exist in A-130's
 * shipped scope: a login only ever succeeds once a real password has already
 * been chosen through this exact redemption flow, so there is no
 * must-change state left to police afterwards. That branch — and the
 * `?token=`-less "already signed in" path it depended on — is removed rather
 * than left dead against a `portalSetPassword` endpoint that no longer
 * exists.
 *
 * The flow is now the two calls A-130 exposes: `describePortalCredentialLink`
 * (a `GET`, safe to run on load or even prefetch — it spends nothing) to
 * confirm the link is live and say whose it is, then
 * `redeemPortalCredentialLink` (a `POST`) to spend it and set the password.
 * Redemption returns `204`, not a session — the client signs in separately
 * with the password just chosen.
 */
export function PortalSetPasswordPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')

  const [link, setLink] = React.useState<PortalCredentialLink | null>(null)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [loading, setLoading] = React.useState(Boolean(token))

  React.useEffect(() => {
    if (!token) return
    let cancelled = false
    setLoading(true)
    // Both credential-link calls are anonymous by definition, and `http.ts`
    // has one token slot shared with the staff shell — a staff session open
    // in this tab would attach its bearer token and A-111's guard would
    // answer 404, reporting the client's link as invalid when it is fine.
    // Same reason `PortalLoginPage` clears it before signing in.
    setAccessToken(null)
    describePortalCredentialLink(token)
      .then((response) => {
        if (!cancelled) setLink(response.data)
      })
      .catch((error) => {
        if (!cancelled) setLoadError(messageForLink(error))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [token])

  if (!token) {
    // Nothing this page can do without a link — CP-01's login screen is the
    // only other way in.
    navigate('/portal/login', { replace: true })
    return null
  }

  if (loading) {
    return (
      <PortalAuthCard title="Checking your link…">
        <p className="text-sm text-content-muted" role="status">
          One moment.
        </p>
      </PortalAuthCard>
    )
  }

  if (loadError || !link) {
    return (
      <PortalAuthCard title="Link no longer valid">
        <PortalAuthAlert>{loadError ?? 'This link could not be verified.'}</PortalAuthAlert>
        <p className="mt-4 text-sm text-content-muted">
          Ask your account manager to send a new link.
        </p>
      </PortalAuthCard>
    )
  }

  return (
    <RedeemForm
      token={token}
      link={link}
      onDone={() =>
        navigate('/portal/login', {
          replace: true,
          state: { justRedeemed: true },
        })
      }
    />
  )
}

interface Values {
  password: string
  confirmPassword: string
}

function RedeemForm({
  token,
  link,
  onDone,
}: {
  token: string
  link: PortalCredentialLink
  onDone: () => void
}) {
  const [formError, setFormError] = React.useState<string | null>(null)
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ defaultValues: { password: '', confirmPassword: '' } })

  const password = watch('password')

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      setAccessToken(null)
      await redeemPortalCredentialLink(token, { password: values.password })
      onDone()
    } catch (error) {
      setFormError(messageForRedeem(error))
    }
  })

  return (
    <PortalAuthCard
      title="Set your password"
      description={`Choose a password for ${link.username}. You'll use it to sign in from now on.`}
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {formError ? <PortalAuthAlert>{formError}</PortalAuthAlert> : null}

        <PortalAuthField id="new-password" label="New password" error={errors.password?.message}>
          {(aria) => (
            <Input
              {...aria}
              {...register('password', {
                required: 'Choose a password',
                minLength: {
                  value: PASSWORD_MIN_LENGTH,
                  message: `At least ${PASSWORD_MIN_LENGTH} characters`,
                },
                pattern: {
                  value: PASSWORD_COMPLEXITY,
                  message: 'Needs upper case, lower case, a digit and a symbol',
                },
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
                validate: (value) => value === password || 'Passwords do not match',
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

function messageForLink(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.'
  }
  if (error.is(PORTAL_INVALID_CREDENTIAL_LINK)) {
    return 'This link has expired or has already been used.'
  }
  return 'This link could not be verified.'
}

function messageForRedeem(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.'
  }
  if (error.is(PORTAL_INVALID_CREDENTIAL_LINK)) {
    return 'This link has expired or has already been used. Ask your account manager to send a new one.'
  }
  // Weak-password (400) names the failed rule in `detail` — show it directly
  // rather than a generic message, exactly as PortalAuthExceptionHandler
  // intends for this one refusal.
  return error.problem.detail ?? 'Could not set your password. Try again.'
}
