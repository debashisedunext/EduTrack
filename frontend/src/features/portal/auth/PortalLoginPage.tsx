import * as React from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'

import { portalLogin } from '@/api/generated/portal/portal'
import { ApiError, setAccessToken } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

import { PortalAuthAlert, PortalAuthCard, PortalAuthField, PortalAuthNotice } from './PortalAuthUi'
import { usePortalAuthStore } from './portalAuthStore'
import { PORTAL_ACCOUNT_LOCKED, PORTAL_INVALID_CREDENTIALS } from './portalProblemTypes'

/**
 * CP-01 · portal login. `LoginPage`'s shape, one principal type over — the
 * same generic-failure requirement applies with more force here: this
 * screen is reachable by anyone on the internet who can guess a client's
 * username, and a message that names which part was wrong would turn it
 * into a directory of client organisations.
 */
interface Values {
  username: string
  password: string
}

export function PortalLoginPage() {
  const signIn = usePortalAuthStore((state) => state.signIn)
  const navigate = useNavigate()
  const location = useLocation()
  const [formError, setFormError] = React.useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ defaultValues: { username: '', password: '' } })

  const state = location.state as { from?: { pathname?: string }; justRedeemed?: boolean } | null
  const from = state?.from?.pathname
  const justRedeemed = state?.justRedeemed ?? false

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      /*
        Sign in anonymously, always.

        `api/http.ts` holds ONE module-level access token and attaches it to
        every generated call, staff or portal — see `portalAuthStore`'s note
        on the shared slot. It is module state, so it outlives client-side
        navigation: a staff user who opens /portal/login in the same tab
        still has their own bearer token sitting in that slot, and the login
        POST goes out carrying it.

        The server then answers **404, not 401** — A-111's guard hides the
        portal route tree from a non-client principal rather than confirming
        it exists, which is the same no-existence-leak rule §2 applies to
        out-of-scope ids. So the screen reports a failure that has nothing to
        do with the credentials typed into it, and the client's own password
        looks wrong. Verified against the running API: the identical request
        is 200 with no Authorization header and 404 with a staff one.

        Clearing the slot first costs nothing — a login is by definition an
        anonymous call — and `signIn` fills it back in a line later.
      */
      setAccessToken(null)
      const response = await portalLogin(values)
      signIn(response.data)
      navigate(from ?? '/portal/choose', { replace: true })
    } catch (error) {
      setFormError(messageFor(error))
    }
  })

  return (
    <PortalAuthCard
      title="Sign in"
      footer={
        <span className="text-content-muted">
          Trouble signing in?{' '}
          <Link to="/portal/set-password" className="text-primary underline-offset-2 hover:underline">
            Use your credential link
          </Link>{' '}
          or contact your account manager.
        </span>
      }
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        {justRedeemed && !formError ? (
          <PortalAuthNotice>Password set. Sign in with your new password.</PortalAuthNotice>
        ) : null}
        {formError ? <PortalAuthAlert>{formError}</PortalAuthAlert> : null}

        <PortalAuthField id="portal-username" label="Username" error={errors.username?.message}>
          {(aria) => (
            <Input
              {...aria}
              {...register('username', { required: 'Enter your username' })}
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoFocus
            />
          )}
        </PortalAuthField>

        <PortalAuthField id="portal-password" label="Password" error={errors.password?.message}>
          {(aria) => (
            <Input
              {...aria}
              {...register('password', { required: 'Enter your password' })}
              type="password"
              autoComplete="current-password"
            />
          )}
        </PortalAuthField>

        <Button type="submit" size="lg" disabled={isSubmitting}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </PortalAuthCard>
  )
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.'
  }
  if (error.is(PORTAL_ACCOUNT_LOCKED)) {
    return 'This account is locked for 15 minutes after five failed attempts.'
  }
  if (error.is(PORTAL_INVALID_CREDENTIALS)) {
    return 'Username or password is incorrect.'
  }
  // No PORTAL_TOO_MANY_ATTEMPTS type — A-130 ships no rate limiter on this
  // route yet (named there as deliberately deferred). Still handle a bare
  // 429 as a generic fallback in case something upstream ever throttles it.
  if (error.status === 429) {
    return 'Too many attempts. Wait a minute and try again.'
  }
  return 'Something went wrong signing you in. Try again.'
}
