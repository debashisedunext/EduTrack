import { useSearchParams } from 'react-router-dom'
import { useListClients } from '@/api/generated/clients/clients'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListUsers } from '@/api/generated/users/users'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
import type { ReportFilterKind } from '@/api/generated/model'

/**
 * A-063 · the viewer's controls, drawn from what the report declares.
 *
 * <h2>Only the filters the runner honours</h2>
 *
 * <p>A bar showing all six on every report would put a Resource control on the
 * email delivery log, where there is no resource to filter by. The user sets
 * it, nothing changes, and the only conclusion available to them is that the
 * screen is broken — which is worse than the control being absent, because a
 * missing control asks no question.
 *
 * <p>B-060 adds the fourth control, CLIENT, for the client report. **TASK_TYPE
 * and LEVEL are still undrawn** — declared by `sla-breach` and `effort-summary`
 * and honoured by their runners since B-060 threaded `ReportFilters`, but with
 * no control here yet. That is a capability reachable by URL and export and not
 * from the bar, which is a gap rather than the failure above: nothing on screen
 * claims to filter and then does not. One branch each, and they belong to those
 * two reports' owner.
 *
 * <h2>The lists are only fetched when they are drawn</h2>
 *
 * <p>A report with no Project filter issues no `/projects` request. The
 * dashboard learned this one screen over: A-062 removed two dropdowns from the
 * developer variant and with them two master-list fetches that had been made on
 * every load to populate controls nobody could use.
 */
export function ReportFilterBar({ filters }: { filters: ReportFilterKind[] }) {
  const [params, setParams] = useSearchParams()

  const wantsProject = filters.includes('PROJECT')
  const wantsResource = filters.includes('RESOURCE')
  const wantsDates = filters.includes('DATE_RANGE')
  const wantsClient = filters.includes('CLIENT')

  // `enabled` rather than a conditional hook — hooks cannot be called
  // conditionally, and the query simply does not run when the control is absent.
  const projects = useListProjects(undefined, { query: { enabled: wantsProject } })
  const users = useListUsers(undefined, { query: { enabled: wantsResource } })
  /*
    B-060 · the client report is the only report declaring CLIENT, so this
    request is made on exactly one screen. Active clients only: the list is a
    control for narrowing a report, and a deactivated client with historical
    tickets is still worth picking — which is why `status` is not sent and the
    server's default ordering stands. B-029's rule is that deactivating blocks
    *new* tickets and never hides historical ones, and a report is history.
  */
  const clients = useListClients(undefined, { query: { enabled: wantsClient } })

  const projectList = projects.data?.data ?? []
  const userList = users.data?.data ?? []
  const clientList = clients.data?.data ?? []

  function set(key: string, value: string | undefined) {
    const next = new URLSearchParams(params)
    if (value === undefined || value === '') {
      next.delete(key)
    } else {
      next.set(key, value)
    }
    setParams(next, { replace: true })
  }

  if (filters.length === 0) return null

  return (
    <div className="mb-4 flex flex-wrap items-end gap-3">
      {wantsDates && (
        <>
          <label className="flex flex-col gap-1">
            <span className="text-caption font-medium text-content-muted">From</span>
            <input
              type="date"
              value={params.get('from') ?? ''}
              onChange={(e) => set('from', e.target.value)}
              className="rounded-control border border-border bg-surface px-2 py-1.5 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-caption font-medium text-content-muted">To</span>
            <input
              type="date"
              value={params.get('to') ?? ''}
              onChange={(e) => set('to', e.target.value)}
              className="rounded-control border border-border bg-surface px-2 py-1.5 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            />
          </label>
        </>
      )}

      {wantsProject && (
        <FilterDropdown
          label="Project"
          options={projectList}
          value={projectList.find((p) => String(p.id) === params.get('projectId')) ?? null}
          onChange={(p) => set('projectId', p ? String(p.id) : undefined)}
          getKey={(p) => String(p.id)}
          getLabel={(p) => p.name ?? `#${p.id}`}
          searchable
        />
      )}

      {wantsClient && (
        <FilterDropdown
          label="Client"
          options={clientList}
          value={clientList.find((c) => String(c.id) === params.get('clientId')) ?? null}
          onChange={(c) => set('clientId', c ? String(c.id) : undefined)}
          getKey={(c) => String(c.id)}
          /*
            Name and code together. Two clients of the same group routinely
            share a leading word — "Acme Retail" and "Acme Logistics" — and the
            code is the thing a support desk actually says out loud, so picking
            the wrong one off a name-only list is a report sent to the wrong
            client.
          */
          getLabel={(c) => (c.clientCode ? `${c.name} (${c.clientCode})` : (c.name ?? `#${c.id}`))}
          searchable
        />
      )}

      {wantsResource && (
        <FilterDropdown
          label="Resource"
          options={userList}
          value={userList.find((u) => String(u.id) === params.get('resourceId')) ?? null}
          onChange={(u) => set('resourceId', u ? String(u.id) : undefined)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName ?? `#${u.id}`}
          searchable
        />
      )}
    </div>
  )
}
