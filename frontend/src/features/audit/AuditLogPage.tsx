import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Info, Lock } from 'lucide-react'
import { useListAuditLogs } from '@/api/generated/audit/audit'
import type { AuditLogEntry } from '@/api/generated/model'
import { EmptyState } from '@/components/ui/empty-state'

import { AuditExportButtons } from './AuditExportButtons'
import { AuditFilterBar } from './AuditFilterBar'
import { AuditLogTable } from './AuditLogTable'

/**
 * A-071 · S-16, the Audit Log Viewer. Admin only.
 *
 * <h2>Load more, not page numbers</h2>
 *
 * <p>The API pages by cursor and returns no total, because counting this table
 * is not cheap and gets less so every day the product runs. That rules out
 * numbered pages honestly rather than accidentally: "page 7 of 412" would need
 * the count, and offering it would put a `COUNT(*)` over an unbounded table
 * behind every open of this screen.
 *
 * <p>Rows accumulate rather than replace, so Load more extends the list the way
 * a log reads. Changing a filter resets the accumulation — see the effect
 * below, and note that a stale first page under a new filter is the failure
 * this screen would otherwise have: it looks like data, and it is the previous
 * question's answer.
 */
export function AuditLogPage() {
  const [params] = useSearchParams()

  const actorId = params.get('actorId')
  const filters = useMemo(
    () => ({
      actorId: actorId ? Number(actorId) : undefined,
      action: params.get('action') ?? undefined,
      entityType: params.get('entityType') ?? undefined,
      from: params.get('from') ?? undefined,
      to: params.get('to') ?? undefined,
    }),
    [actorId, params],
  )

  const [cursor, setCursor] = useState<string | undefined>(undefined)
  const [loaded, setLoaded] = useState<AuditLogEntry[]>([])

  // The filters as one comparable value. `params.toString()` would change when
  // `cursor` does — and cursor is state here, not a query parameter, precisely
  // so that paging does not read as a filter change and reset itself.
  const filterKey = JSON.stringify(filters)

  useEffect(() => {
    setCursor(undefined)
    setLoaded([])
  }, [filterKey])

  const query = useListAuditLogs({ ...filters, cursor, limit: 50 })

  const page = query.data
  useEffect(() => {
    if (!page?.data) return
    setLoaded((previous) => (cursor ? [...previous, ...page.data] : page.data))
    // `cursor` is deliberately not a dependency: it is already the reason this
    // query re-ran, and including it would append the same page twice whenever
    // React re-invoked the effect with unchanged data.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page])

  const nextCursor = page?.meta?.nextCursor ?? undefined
  const hasMore = Boolean(page?.meta?.hasMore && nextCursor)

  return (
    <div className="p-6">
      <header className="mb-4">
        <h1 className="text-h2 font-semibold text-content">Audit log</h1>
        <p className="mt-1 text-sm text-content-muted">
          Every sign-in, permission change, master change and ticket action.
        </p>
      </header>

      {/*
        Said once, at the top, and not as a disabled button on every row. A
        reader needs to know two things about this screen before they trust it:
        that nothing here can be edited, and that a missing row would be a bug
        rather than a deletion.
      */}
      <p className="mb-4 flex items-center gap-2 rounded-control bg-subtle px-3 py-2 text-caption text-content-muted">
        <Lock className="h-4 w-4 shrink-0" aria-hidden />
        Entries are append-only. Nothing on this screen can be edited or removed — export is the
        only way data leaves it.
      </p>

      <AuditFilterBar />
      <AuditExportButtons params={params} />

      {query.isError && (
        <p role="alert" className="text-sm text-danger-text">
          The audit log could not be loaded. It is available to Admins only.
        </p>
      )}

      {!query.isError && loaded.length === 0 && query.isLoading && (
        <p className="text-sm text-content-muted">Loading entries…</p>
      )}

      {!query.isError && loaded.length === 0 && !query.isLoading && (
        <EmptyState
          title="No entries match"
          description="Nothing was recorded for these filters. Widen the date range or clear a filter."
        />
      )}

      {loaded.length > 0 && (
        <>
          <AuditLogTable entries={loaded} />

          <div className="mt-4 flex items-center gap-3">
            {hasMore ? (
              <button
                type="button"
                onClick={() => setCursor(nextCursor)}
                disabled={query.isFetching}
                className="rounded-control border border-border bg-surface px-3 py-1.5 text-caption font-medium text-content transition-colors hover:bg-subtle disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                {query.isFetching ? 'Loading…' : 'Load more'}
              </button>
            ) : (
              <span className="flex items-center gap-1.5 text-caption text-content-muted">
                <Info className="h-3.5 w-3.5 shrink-0" aria-hidden />
                End of the matching entries.
              </span>
            )}
            {/*
              A count of what is on screen, never of what exists. "412 entries"
              would be a claim about the table; this is a claim about the list,
              which is the only one that can be made without a COUNT(*).
            */}
            <span aria-live="polite" className="text-caption text-content-muted">
              Showing {loaded.length} {loaded.length === 1 ? 'entry' : 'entries'}
            </span>
          </div>
        </>
      )}
    </div>
  )
}
