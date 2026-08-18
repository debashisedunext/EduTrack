import { useSearchParams } from 'react-router-dom'
import { useListUsers } from '@/api/generated/users/users'
import { FilterDropdown } from '@/components/ui/filter-dropdown'

import { AUDIT_MODULES, type AuditModule } from './auditVocabulary'

/**
 * A-071 · S-16's "filter by user, module, date".
 *
 * <h2>Three of the four controls are the blueprint's; the fourth is Action</h2>
 *
 * <p>§7.4 names user, module and date. Action is here as well because the
 * vocabulary is finer than the module — "show me every failed sign-in" is the
 * question this screen is opened for, and filtering to the `users` module
 * answers it with every successful login mixed in.
 *
 * <p>It is a free-text box rather than a dropdown, and that is honest rather
 * than lazy: the terms are derived from the route table, so the complete list
 * is not knowable without a catalogue endpoint the contract does not declare.
 * A dropdown of the dozen terms somebody thought of would quietly imply the
 * others do not exist. The datalist offers the common ones and typing anything
 * else works.
 *
 * <h2>State lives in the URL</h2>
 *
 * <p>Same decision as the report viewer and the ticket list. An audit query
 * narrowed to one person and one afternoon is the thing somebody pastes into a
 * ticket or an email, and holding it in React breaks both that and the back
 * button.
 */

/** The terms offered as suggestions. Typing any other term still works. */
const COMMON_ACTIONS = [
  'LOGIN_SUCCESS',
  'LOGIN_FAILED',
  'LOGIN_THROTTLED',
  'LOGIN_LOCKED_OUT',
  'LOGIN_2FA_FAILED',
  'LOGOUT',
  'ACCESS_DENIED',
  'PERMISSIONS_UPDATED',
]

const CONTROL_CLASS =
  'rounded-control border border-border bg-surface px-2 py-1.5 text-sm text-content ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary'

export function AuditFilterBar() {
  const [params, setParams] = useSearchParams()

  // Every role can be an actor, so this is the unfiltered list — unlike the
  // report viewer's Resource control, which is bounded by scope. The audit log
  // has no row scope by design (see AuditService), and a dropdown narrower than
  // the data would make some rows unfilterable.
  const users = useListUsers()
  const userList = users.data?.data ?? []

  const actorId = params.get('actorId')
  const selectedUser = userList.find((u) => String(u.id) === actorId) ?? null
  const selectedModule = AUDIT_MODULES.find((m) => m.value === params.get('entityType')) ?? null

  function set(key: string, value: string | undefined) {
    const next = new URLSearchParams(params)
    if (value === undefined || value === '') {
      next.delete(key)
    } else {
      next.set(key, value)
    }
    setParams(next, { replace: true })
  }

  return (
    <div className="mb-4 flex flex-wrap items-end gap-3">
      <FilterDropdown
        label="User"
        options={userList}
        value={selectedUser}
        onChange={(user) => set('actorId', user ? String(user.id) : undefined)}
        getKey={(user) => String(user.id)}
        getLabel={(user) => user.displayName}
      />

      <FilterDropdown<AuditModule>
        label="Module"
        options={AUDIT_MODULES}
        value={selectedModule}
        onChange={(module) => set('entityType', module?.value)}
        getKey={(module) => module.value}
        getLabel={(module) => module.label}
        searchable={false}
      />

      <label className="flex flex-col gap-1">
        <span className="text-caption font-medium text-content-muted">Action</span>
        <input
          type="text"
          list="audit-actions"
          placeholder="Any action"
          defaultValue={params.get('action') ?? ''}
          // On blur and on Enter rather than on every keystroke: each change is
          // a request over a table with no upper bound on size, and a
          // half-typed term matches nothing, so live filtering would show an
          // empty audit log for most of the time somebody is typing into it.
          onBlur={(e) => set('action', e.target.value.trim().toUpperCase())}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              set('action', e.currentTarget.value.trim().toUpperCase())
            }
          }}
          className={CONTROL_CLASS}
        />
        <datalist id="audit-actions">
          {COMMON_ACTIONS.map((action) => (
            <option key={action} value={action} />
          ))}
        </datalist>
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-caption font-medium text-content-muted">From</span>
        <input
          type="date"
          aria-label="From date"
          value={params.get('from') ?? ''}
          onChange={(e) => set('from', e.target.value)}
          className={CONTROL_CLASS}
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-caption font-medium text-content-muted">To</span>
        <input
          type="date"
          aria-label="To date"
          value={params.get('to') ?? ''}
          onChange={(e) => set('to', e.target.value)}
          className={CONTROL_CLASS}
        />
      </label>

      {params.toString() !== '' && (
        <button
          type="button"
          onClick={() => setParams(new URLSearchParams(), { replace: true })}
          className="rounded-control px-2 py-1.5 text-caption font-medium text-primary hover:bg-primary-soft focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          Reset
        </button>
      )}
    </div>
  )
}
