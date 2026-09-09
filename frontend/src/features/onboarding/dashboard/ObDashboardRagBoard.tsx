import { useNavigate } from 'react-router-dom'

import { useListObClients } from '@/api/generated/onboarding/onboarding'
import type { ObClient } from '@/api/generated/model/obClient'
import type { ObRag } from '@/api/generated/model/obRag'
import { Skeleton } from '@/components/ui/skeleton'

/** One page per column. A board, not an archive — {@link ObDashboardDrillPanel}'s own reasoning for the identical limit. */
const COLUMN_LIMIT = 20

interface Column {
  rag: ObRag
  title: string
  glyph: string
  emptyHint: string
}

const COLUMNS: Column[] = [
  { rag: 'RED', title: 'Breached / blocked', glyph: '🔴', emptyHint: 'Nothing breached. Keep it that way.' },
  { rag: 'AMBER', title: 'At risk', glyph: '🟠', emptyHint: 'No client is close to a TAT limit.' },
  { rag: 'GREEN', title: 'On track', glyph: '🟢', emptyHint: '—' },
]

/**
 * "2 journeys · ERP step 4/8 · Data migration" — `ObClient.currentStep`'s own
 * documented purpose (`obClient.ts`: "the OB-02 RAG columns' … caption").
 * Null only while the client's primary journey is locked, held behind a
 * sibling, or finished — states OB-03 already has words for, so this column
 * says nothing about a client rather than inventing a fourth phrase for them.
 */
function stepCaption(client: ObClient): string | null {
  const step = client.currentStep
  if (!step) return null
  const journeys = client.journeyCount === 1 ? '1 journey' : `${client.journeyCount} journeys`
  const product = step.product?.code ?? step.product?.name
  const stepLabel = product ? `${product} step ${step.stepIndex}/${step.stepTotal}` : `step ${step.stepIndex}/${step.stepTotal}`
  return `${journeys} · ${stepLabel} · ${step.name}`
}

/**
 * B-121/A-118 · the RAG board plan §9 draws beside the seven cards — one
 * column per health colour, each a worklist of the clients currently that
 * colour. Sourced from `/onboarding/clients?status=ONBOARDING&rag=…`, the
 * same read OB-03 itself lists from and the same field the contract's own
 * comment on `ObClient.currentStep` names this board as the reason for.
 */
export function ObDashboardRagBoard() {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {COLUMNS.map((column) => (
        <RagColumn key={column.rag} column={column} />
      ))}
    </div>
  )
}

function RagColumn({ column }: { column: Column }) {
  const navigate = useNavigate()
  const { data, isPending, isError } = useListObClients({
    status: 'ONBOARDING', rag: column.rag, limit: COLUMN_LIMIT,
  })
  const clients = data?.data ?? []

  return (
    <section
      aria-label={column.title}
      className="overflow-hidden rounded-card border border-border bg-surface shadow-sm"
    >
      <div className="flex items-center gap-2 border-b border-border px-4 py-2.5 text-sm font-semibold text-content">
        <span aria-hidden="true">{column.glyph}</span>
        {column.title}
        <span className="ml-auto text-xs font-normal text-content-muted">
          {isPending ? '' : (data?.meta?.totalCount ?? clients.length)}
        </span>
      </div>

      {isPending ? (
        <div className="flex flex-col gap-2 p-4">
          {Array.from({ length: 2 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : isError ? (
        <p className="p-4 text-center text-xs text-content-muted">This list could not be loaded.</p>
      ) : clients.length === 0 ? (
        <p className="p-4 text-center text-xs text-content-muted">{column.emptyHint}</p>
      ) : (
        clients.map((client, index) => (
          <button
            key={client.id}
            type="button"
            onClick={() => navigate(`/onboarding/clients/${client.id}`)}
            className={
              'block w-full px-4 py-3 text-left text-sm hover:bg-subtle ' +
              'focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary ' +
              (index > 0 ? 'border-t border-border' : '')
            }
          >
            <span className="block font-medium text-content">{client.name}</span>
            {stepCaption(client) && (
              <span className="mt-0.5 block text-xs text-content-muted">{stepCaption(client)}</span>
            )}
          </button>
        ))
      )}
    </section>
  )
}
