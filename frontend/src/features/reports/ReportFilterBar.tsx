import { useSearchParams } from 'react-router-dom'
import { useListClients } from '@/api/generated/clients/clients'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListTaskTypes } from '@/api/generated/masters/masters'
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
 * <p>B-060 added the fourth control, CLIENT, for the client report. **A-070 adds
 * the fifth, TASK_TYPE.** It had been declared by `sla-breach` and
 * `effort-summary` since A-066 and honoured by their runners since B-060
 * threaded `ReportFilters`, with no way to set it but editing the URL — a
 * filter that worked and could not be reached. A-070 declares it too, and
 * shipping a report that promises a cut nobody can make is the failure this
 * per-report list exists to prevent, so it is drawn rather than inherited.
 * Those two reports gain the control for free.
 *
 * <p>**LEVEL is still undrawn**, declared by `sla-breach` alone. One branch, and
 * it belongs to that report — the pattern to copy is directly below.
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
  // A-070 · the fifth control. TASK_TYPE has been declared by sla-breach and
  // effort-summary since A-066 and honoured by their runners, with no way to
  // set it except by editing the URL — a filter that works and cannot be
  // reached. A-070 declares it too, and a report that promises a cut nobody
  // can make is the thing this component's per-report filter list exists to
  // prevent, so the gap is closed here rather than inherited.
  const wantsTaskType = filters.includes('TASK_TYPE')

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
  // No params argument on this one, unlike the three above — listTaskTypes
  // takes no query parameters, so the generated hook's first argument is the
  // options object itself.
  const taskTypes = useListTaskTypes({ query: { enabled: wantsTaskType } })

  const projectList = projects.data?.data ?? []
  const userList = users.data?.data ?? []
  const clientList = clients.data?.data ?? []
  const taskTypeList = taskTypes.data?.data ?? []

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

      {wantsTaskType && (
        <FilterDropdown
          label="Task type"
          /*
            Whatever the master returns, unfiltered. `listTaskTypes` is
            active-only by default (B-021), and that is right for a *create*
            form and arguable here — a retired type still has historical
            tickets worth reporting on. Left as the endpoint's default rather
            than widened, because widening it is a decision about every screen
            that calls this endpoint and not one to take inside a filter bar.
          */
          options={taskTypeList}
          value={taskTypeList.find((t) => String(t.id) === params.get('taskTypeId')) ?? null}
          onChange={(t) => set('taskTypeId', t ? String(t.id) : undefined)}
          getKey={(t) => String(t.id)}
          getLabel={(t) => t.name ?? `#${t.id}`}
          searchable
        />
      )}
    </div>
  )
}
