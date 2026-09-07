import { useSearchParams } from 'react-router-dom'
import { useListObClients } from '@/api/generated/onboarding/onboarding'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
import type { ObReportFilterKind } from '@/api/generated/model'

/**
 * B-122 · OB-10's controls, drawn from what the report declares.
 *
 * <h2>Only the filters the runner honours</h2>
 *
 * A bar showing all five on every report would put a Health control on the
 * sales pipeline, where no row has a health. The user sets it, nothing changes,
 * and the only conclusion available to them is that the screen is broken —
 * worse than the control being absent, because a missing control asks no
 * question.
 *
 * <h2>The lists are only fetched when they are drawn</h2>
 *
 * A report with no Product filter issues no `/onboarding/products` request. The
 * ticketing hub learned this one module over: two dropdowns nobody could use
 * were costing two master-list fetches on every load.
 *
 * <h2>🔴 The Owner list is every user, not every implementor</h2>
 *
 * `useListUsers` is the whole directory, and the population this control wants
 * is the users holding an `OB_STEP_OWNER` grant — picking a colleague from
 * finance narrows TAT compliance to nothing and looks like a broken report
 * rather than an empty one. The right source is
 * `listObImplementorWorkload`, which is exactly "a row per implementor
 * including the bench" — and it is **B-128's** route with no implementation
 * behind it yet. Wiring this control to an endpoint that 404s would be worse
 * than a list that is too wide, so it takes the directory for now and B-128
 * narrows it. Named here rather than left to be noticed.
 */
export function ObReportFilterBar({ filters }: { filters: ObReportFilterKind[] }) {
  const [params, setParams] = useSearchParams()

  const wantsDates = filters.includes('dateRange')
  const wantsProduct = filters.includes('product')
  const wantsClient = filters.includes('client')
  const wantsOwner = filters.includes('owner')
  const wantsRag = filters.includes('rag')

  // `enabled` rather than a conditional hook — hooks cannot be called
  // conditionally, and the query simply does not run when the control is absent.
  const products = useListObProducts(undefined, { query: { enabled: wantsProduct } })
  /*
    Every client, not only the active ones. A report is history: plan §4 keeps a
    DROPPED or ON_HOLD client's journeys visible, and narrowing a stuck-and-aging
    report to a client who was put on hold last month is exactly the question
    somebody asks about a client who was put on hold last month.
  */
  const clients = useListObClients(undefined, { query: { enabled: wantsClient } })
  const users = useListUsers(undefined, { query: { enabled: wantsOwner } })

  const productList = products.data?.data ?? []
  const clientList = clients.data?.data ?? []
  const userList = users.data?.data ?? []

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

      {wantsProduct && (
        <FilterDropdown
          label="Product"
          /*
            Whatever the master returns, retired products included. A product
            retired last quarter still has journeys that ran, and a report is
            about what happened rather than about what can be sold today —
            `isActive` gates the OB-04 picker, not this.
          */
          options={productList}
          value={productList.find((p) => String(p.id) === params.get('productId')) ?? null}
          onChange={(p) => set('productId', p ? String(p.id) : undefined)}
          getKey={(p) => String(p.id)}
          getLabel={(p) => p.name ?? `#${p.id}`}
          searchable
        />
      )}

      {wantsClient && (
        <FilterDropdown
          label="Client"
          options={clientList}
          value={clientList.find((c) => String(c.id) === params.get('obClientId')) ?? null}
          onChange={(c) => set('obClientId', c ? String(c.id) : undefined)}
          getKey={(c) => String(c.id)}
          getLabel={(c) => c.name ?? `#${c.id}`}
          searchable
        />
      )}

      {wantsOwner && (
        <FilterDropdown
          label="Owner"
          options={userList}
          value={userList.find((u) => String(u.id) === params.get('ownerUserId')) ?? null}
          onChange={(u) => set('ownerUserId', u ? String(u.id) : undefined)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName ?? `#${u.id}`}
          searchable
        />
      )}

      {wantsRag && (
        <FilterDropdown
          label="Health"
          /*
            A fixed list rather than a fetch: `ObRag` is three values in the
            contract and there is no endpoint that serves them. Written out
            here is the only copy in this module — the chip's colours come from
            the table's own mapping, so a fourth value would show up as an
            unstyled chip rather than as two lists disagreeing.
          */
          options={[...RAG_OPTIONS]}
          value={RAG_OPTIONS.find((r) => r.value === params.get('rag')) ?? null}
          onChange={(r) => set('rag', r?.value)}
          getKey={(r) => r.value}
          getLabel={(r) => r.label}
        />
      )}
    </div>
  )
}

const RAG_OPTIONS = [
  { value: 'GREEN', label: 'Green' },
  { value: 'AMBER', label: 'Amber' },
  { value: 'RED', label: 'Red' },
] as const
