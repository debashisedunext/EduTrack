import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import { useListUsers } from '@/api/generated/users/users'
import {
  getListObModuleAccessQueryKey,
  useGrantObModuleAccess,
  useListObModuleAccess,
  useRevokeObModuleAccess,
} from '@/api/generated/onboarding-masters/onboarding-masters'
import type { ObModuleAccessGrant } from '@/api/generated/model/obModuleAccessGrant'
import type { ObModule } from '@/api/generated/model/obModule'
import { ObModuleRole } from '@/api/generated/model/obModuleRole'
import { ApiError } from '@/api/http'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuthStore } from '@/features/auth/authStore'

/**
 * A-117 · OB-08 — roles and module access.
 *
 * <h2>A person per row, an event per change</h2>
 *
 * The design draws a grid of people with a tick per module, and that is what
 * this is. The API underneath is not shaped that way: A-109 records access as
 * grants that are made and withdrawn, never edited, so that an access audit can
 * answer who opened this and when. Both are right — the grid is the question an
 * administrator asks ("who can reach onboarding?"), the event log is what the
 * question has to be answered from a year later.
 *
 * So a tick here is not a field being saved. Ticking issues a grant, unticking
 * revokes one, and changing a role does both in that order — which is why the
 * role control says what it will do rather than looking like a `<select>` that
 * edits a value. `POST .../{id}/revoke` rather than `DELETE` for the same
 * reason: the row survives, stamped with who withdrew it.
 *
 * <h2>Two refusals the server owns and this screen only reports</h2>
 *
 * <b>The last OB Admin cannot be demoted or revoked.</b> `ObModuleAccessService`
 * counts live admin grants and refuses at one. Re-implementing that count here
 * would be a second, laggier copy of a rule whose whole job is to be true at the
 * moment of the write — this screen disables nothing on that basis and reports
 * the refusal when it comes.
 *
 * <b>Everything here is OB Admin only, and refused with 403 rather than 404.</b>
 * That is deliberate and documented on `ObModuleAccessController`: there is no
 * row whose existence a 404 would protect, only an administration surface, and
 * the caller has already passed the module gate to arrive.
 *
 * <h2>Your own onboarding access is not yours to remove</h2>
 *
 * The one thing the server does not stop, because it is not wrong in general:
 * an administrator revoking their own grant. It is wrong *from this screen* —
 * the next render has no administration surface on it and no way back. The
 * controls on your own row are disabled, and the reason is on the row rather
 * than in a tooltip nobody opens.
 */

const OB_ROLES = [
  ObModuleRole.OB_ADMIN,
  ObModuleRole.OB_MANAGER,
  ObModuleRole.OB_SALES,
  ObModuleRole.OB_STEP_OWNER,
  ObModuleRole.OB_VIEWER,
] as const

const ROLE_LABELS: Record<string, string> = {
  OB_ADMIN: 'OB Admin',
  OB_MANAGER: 'Onboarding Manager',
  OB_SALES: 'Sales',
  OB_STEP_OWNER: 'Step Owner',
  OB_VIEWER: 'Viewer (Management)',
  TICKETING_MEMBER: 'Member',
}

/** Ticketing has one role, so the grid's tick is the whole decision. */
const TICKETING_ROLE = ObModuleRole.TICKETING_MEMBER

type LiveGrants = Map<string, ObModuleAccessGrant>

const keyOf = (userId: number, module: string) => `${userId}:${module}`

const TICKETING: ObModule = 'TICKETING'
const ONBOARDING: ObModule = 'ONBOARDING'

export function ObModuleAccessPage() {
  const queryClient = useQueryClient()
  const [error, setError] = React.useState<string | null>(null)
  const [pendingUserId, setPendingUserId] = React.useState<number | null>(null)

  const signedInUserId = useAuthStore((s) => s.user?.id)

  /*
    `isActive` only. A deactivated employee cannot sign in, so their grant
    cannot be used, and listing them here would pad the grid with rows whose
    ticks change nothing. Their grants remain in the audit either way — this is
    a filter on the roster, not on the history.
  */
  const usersQuery = useListUsers({ isActive: true, limit: 200 })
  const grantsQuery = useListObModuleAccess({ limit: 200 })

  const users = React.useMemo(() => usersQuery.data?.data ?? [], [usersQuery.data])
  const grants = React.useMemo(() => grantsQuery.data?.data ?? [], [grantsQuery.data])

  const live: LiveGrants = React.useMemo(() => {
    const index: LiveGrants = new Map()
    for (const grant of grants) {
      if (grant.isLive) index.set(keyOf(grant.user.id, grant.module), grant)
    }
    return index
  }, [grants])

  const refresh = React.useCallback(
    () => queryClient.invalidateQueries({ queryKey: getListObModuleAccessQueryKey() }),
    [queryClient],
  )

  const grantAccess = useGrantObModuleAccess()
  const revokeAccess = useRevokeObModuleAccess()

  /*
    Sequential on purpose where a role change needs both calls. The unique
    index allows one live grant per (user, module), so issuing the replacement
    before withdrawing the old one is refused with a 409 — and firing them
    together would make which arrives first the deciding factor.
  */
  const apply = React.useCallback(
    async (userId: number, work: () => Promise<unknown>) => {
      setError(null)
      setPendingUserId(userId)
      try {
        await work()
        await refresh()
      } catch (caught) {
        setError(messageFor(caught))
      } finally {
        setPendingUserId(null)
      }
    },
    [refresh],
  )

  /** `nextRole` of `null` means withdraw and do not replace. */
  const setModule = (
    userId: number,
    module: ObModule,
    nextRole: ObModuleRole | null,
    existing?: ObModuleAccessGrant,
  ) =>
    apply(userId, async () => {
      if (existing) await revokeAccess.mutateAsync({ grantId: existing.id })
      if (nextRole) await grantAccess.mutateAsync({ data: { userId, module, moduleRole: nextRole } })
    })

  if (usersQuery.isPending || grantsQuery.isPending) {
    return (
      <div className="p-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="mt-4 h-64 w-full" />
      </div>
    )
  }

  if (grantsQuery.isError) {
    return (
      <p role="alert" className="m-6 rounded-card border border-danger p-4 text-sm text-danger-text">
        {messageFor(grantsQuery.error)}
      </p>
    )
  }

  return (
    <div className="p-6">
      <h1 className="text-lg font-semibold text-content">Roles &amp; module access</h1>
      <p className="mt-1 max-w-2xl text-caption text-content-muted">
        A person&apos;s onboarding role is independent of their ticketing role. Every grant and
        withdrawal is recorded with who did it and when.
      </p>

      {error && (
        <p role="alert" className="mt-4 rounded-card border border-danger p-3 text-sm text-danger-text">
          {error}
        </p>
      )}

      <div className="mt-5 overflow-x-auto rounded-card border border-border bg-surface shadow-rest">
        <table className="w-full text-sm">
          <caption className="sr-only">
            Every active user, the modules they may reach, and their onboarding role
          </caption>
          <thead>
            <tr className="border-b border-border bg-subtle text-left text-caption text-content-muted">
              <th scope="col" className="px-4 py-2 font-medium">User</th>
              <th scope="col" className="px-4 py-2 font-medium">Ticketing</th>
              <th scope="col" className="px-4 py-2 font-medium">Onboarding</th>
              <th scope="col" className="px-4 py-2 font-medium">Onboarding role</th>
              <th scope="col" className="px-4 py-2 font-medium">Last change</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => {
              const ticketing = live.get(keyOf(user.id, TICKETING))
              const onboarding = live.get(keyOf(user.id, ONBOARDING))
              const isSelf = user.id === signedInUserId
              const busy = pendingUserId === user.id

              return (
                <tr key={user.id} className="border-b border-border last:border-0">
                  <th scope="row" className="px-4 py-2 text-left font-normal text-content">
                    {user.displayName}
                  </th>

                  <td className="px-4 py-2">
                    <label className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={Boolean(ticketing)}
                        disabled={busy}
                        aria-label={`Ticketing access for ${user.displayName}`}
                        onChange={() =>
                          setModule(
                            user.id,
                            TICKETING,
                            ticketing ? null : TICKETING_ROLE,
                            ticketing,
                          )
                        }
                      />
                    </label>
                  </td>

                  <td className="px-4 py-2">
                    <label className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={Boolean(onboarding)}
                        disabled={busy || isSelf}
                        aria-label={`Onboarding access for ${user.displayName}`}
                        onChange={() =>
                          setModule(
                            user.id,
                            ONBOARDING,
                            onboarding ? null : ObModuleRole.OB_VIEWER,
                            onboarding,
                          )
                        }
                      />
                    </label>
                  </td>

                  <td className="px-4 py-2">
                    <select
                      className="w-full max-w-56 rounded-control border border-border bg-surface px-2 py-1 text-sm text-content disabled:text-content-muted"
                      value={onboarding?.moduleRole ?? ''}
                      disabled={!onboarding || busy || isSelf}
                      aria-label={`Onboarding role for ${user.displayName}`}
                      onChange={(event) =>
                        setModule(user.id, ONBOARDING, event.target.value as ObModuleRole, onboarding)
                      }
                    >
                      {!onboarding && <option value="">— no access —</option>}
                      {OB_ROLES.map((role) => (
                        <option key={role} value={role}>
                          {ROLE_LABELS[role]}
                        </option>
                      ))}
                    </select>
                  </td>

                  <td className="px-4 py-2 text-caption text-content-muted">
                    {isSelf ? (
                      <span>Your own access — change it from another administrator&apos;s account</span>
                    ) : (
                      lastChange(onboarding ?? ticketing)
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <p className="mt-3 text-caption text-content-muted">
        A change reaches somebody already signed in when their access token next refreshes — up to{' '}
        {tokenLag(grants)} seconds.
      </p>
    </div>
  )
}

/** Who last opened or closed this person's access, from the grant itself. */
function lastChange(grant?: ObModuleAccessGrant): string {
  if (!grant) return 'No access'
  const who = grant.grantedBy?.displayName
  const when = new Date(grant.grantedAt).toLocaleDateString()
  return who ? `Granted by ${who} · ${when}` : `Granted ${when}`
}

/*
  Read off a grant rather than hardcoded, because it is the server's access-token
  lifetime and a number repeated here would be wrong the day that changes.
*/
function tokenLag(grants: ObModuleAccessGrant[]): number {
  return grants[0]?.tokenLagSeconds ?? 900
}

function messageFor(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.status === 403) {
      return 'Managing module access is OB Admin only.'
    }
    if (caught.status === 409) {
      return 'That person already has access to this module. Reload the page — somebody else may have just granted it.'
    }
    if (caught.status === 422) {
      return 'Refused: an organisation must keep at least one OB Admin, and this is the last one.'
    }
  }
  return 'That change did not go through. Please try again.'
}

export default ObModuleAccessPage
