import * as React from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObProduct } from '@/api/generated/model/obProduct'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import { useJourneyTemplate } from './journeyTemplateQueries'
import { cycleFreeCandidates, moveTemplate } from './moduleServiceCatalogue'
import {
  useReorderModuleServiceCatalogue,
  useUpdateModuleServiceDependsOn,
} from './moduleServiceCatalogueQueries'

/**
 * C-123 · OB-07's other half — the Module Service catalogue itself, one card
 * per product. `JourneyTemplateDesignerPage` is where a single service's
 * steps are edited; this is where an admin decides which services exist,
 * what order they instantiate and display in, and which one depends on
 * which. A card's own link opens that designer.
 *
 * <h2>The reorder is catalogue-wide, the picker is per-card</h2>
 *
 * `sequence` is one number shared by every active template, so the ↑/↓
 * buttons act on the *whole* catalogue's ordering, not the filtered view —
 * shown only when no product filter narrows the list, on the same reasoning
 * a filtered single-row view has nothing meaningful to reorder against.
 * "Service depends on" is the opposite: one field on one template, editable
 * from any card regardless of the filter.
 *
 * <h2>A product with no active template is a dead end here, deliberately</h2>
 *
 * Creating the first version of a Module Service is C-102's "+ Create
 * journey template" (OB-07's own designer entry, product-scoped), not this
 * page's — a product's own `hasActiveTemplate`/draft state is not fully
 * knowable from the catalogue read (`ObProduct` answers "is there an active
 * one", not "is there a draft in progress"), and offering a create button
 * this page cannot safely gate would risk a `409` on a product someone is
 * already mid-draft on. Named as a boundary rather than guessed past.
 */
export function ModuleServiceCataloguePage() {
  const query = useListObProducts()
  const [filter, setFilter] = React.useState<ObProduct | null>(null)
  const reorder = useReorderModuleServiceCatalogue()

  if (query.isPending) {
    return <Skeleton className="h-screen w-full" />
  }
  if (query.isError || !query.data) {
    return (
      <EmptyState
        title="Could not load the Module Service catalogue"
        description="Reload the page to try again."
      />
    )
  }

  const products = query.data.data
  const active = products
    .filter((p): p is ObProduct & { activeTemplateId: number; templateSequence: number } =>
      p.activeTemplateId != null && p.templateSequence != null)
    .sort((a, b) => a.templateSequence - b.templateSequence)
  const activeOrder = active.map((p) => p.activeTemplateId)
  const catalogueEntries = active.map((p) => ({
    activeTemplateId: p.activeTemplateId,
    dependsOnTemplateId: p.dependsOnTemplateId ?? null,
    name: p.name,
  }))

  const visible = filter ? products.filter((p) => p.id === filter.id) : products
  const reorderEnabled = filter === null

  const move = async (templateId: number, direction: -1 | 1) => {
    const from = activeOrder.indexOf(templateId)
    if (from < 0) return
    const next = moveTemplate(activeOrder, from, from + direction)
    if (next === activeOrder) return
    try {
      await reorder.mutateAsync({ templateIds: next })
    } catch (error) {
      toast({
        title: 'Could not reorder the catalogue',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <div className="flex flex-col gap-6 p-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-h2 text-content">Module Service</h1>
          <p className="text-body-sm text-content-muted">
            Every product's onboarding journey, in the order clients meet them.
          </p>
        </div>
        <FilterDropdown
          label="Product"
          options={products}
          value={filter}
          onChange={setFilter}
          getKey={(p) => String(p.id)}
          getLabel={(p) => p.name}
          searchable
        />
      </header>

      {visible.length === 0 ? (
        <EmptyState title="No products match that filter" description="Choose another product, or clear the filter." />
      ) : (
        <ol className="flex flex-col gap-3">
          {visible.map((product) => (
            <ModuleServiceCard
              key={product.id}
              product={product}
              index={product.activeTemplateId != null ? activeOrder.indexOf(product.activeTemplateId) : -1}
              total={activeOrder.length}
              reorderEnabled={reorderEnabled}
              reordering={reorder.isPending}
              catalogueEntries={catalogueEntries}
              onMove={move}
            />
          ))}
        </ol>
      )}
    </div>
  )
}

function ModuleServiceCard({
  product,
  index,
  total,
  reorderEnabled,
  reordering,
  catalogueEntries,
  onMove,
}: {
  product: ObProduct
  /** Position in the whole (unfiltered) active-template order, or -1 without one. */
  index: number
  total: number
  reorderEnabled: boolean
  reordering: boolean
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateId: number | null; name: string }[]
  onMove: (templateId: number, direction: -1 | 1) => void
}) {
  const hasActive = product.activeTemplateId != null

  return (
    <li className="flex flex-col gap-3 rounded-card border border-line p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-body font-medium text-content">
            {hasActive ? (
              <Link to={`/onboarding/journey-templates/${product.activeTemplateId}`} className="hover:underline">
                {product.name}
              </Link>
            ) : (
              product.name
            )}
          </p>
          <div className="mt-1 flex flex-wrap gap-1">
            <Chip variant="neutral">{product.code}</Chip>
            {!product.isActive && <Chip variant="neutral">Retired</Chip>}
            {hasActive ? (
              <Chip>
                {product.totalTatDays ?? 0} working day{product.totalTatDays === 1 ? '' : 's'} total
              </Chip>
            ) : (
              <Chip variant="neutral">No active Module Service yet</Chip>
            )}
            <Chip variant="neutral">{product.journeyCount} journey{product.journeyCount === 1 ? '' : 's'}</Chip>
          </div>
        </div>

        {hasActive && reorderEnabled && (
          <div className="flex gap-1">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={reordering || index <= 0}
              aria-label={`Move ${product.name} up`}
              onClick={() => onMove(product.activeTemplateId!, -1)}
            >
              ↑
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={reordering || index < 0 || index >= total - 1}
              aria-label={`Move ${product.name} down`}
              onClick={() => onMove(product.activeTemplateId!, 1)}
            >
              ↓
            </Button>
          </div>
        )}
      </div>

      {hasActive && (
        <DependsOnPicker
          templateId={product.activeTemplateId!}
          productName={product.name}
          catalogueEntries={catalogueEntries}
        />
      )}
    </li>
  )
}

/**
 * Loads its own template detail purely for the `ETag` `PUT .../depends-on`
 * requires — `useJourneyTemplate`'s own cache, shared with the designer page
 * if the same template is opened there in the same session. The catalogue
 * is "a handful of rows" by the contract's own exemption note, so one detail
 * read per active card costs nothing a list this size would notice.
 */
function DependsOnPicker({
  templateId,
  productName,
  catalogueEntries,
}: {
  templateId: number
  productName: string
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateId: number | null; name: string }[]
}) {
  const detail = useJourneyTemplate(templateId)
  const update = useUpdateModuleServiceDependsOn()

  const candidates = cycleFreeCandidates(templateId, catalogueEntries)
  const currentDependsOn = detail.data?.detail.dependsOnTemplateId ?? null

  const onChange = async (event: React.ChangeEvent<HTMLSelectElement>) => {
    const raw = event.target.value
    const dependsOnTemplateId = raw === '' ? null : Number(raw)
    try {
      await update.mutateAsync({ templateId, dependsOnTemplateId, etag: detail.data?.etag ?? null })
      toast({ title: `${productName}'s dependency updated` })
    } catch (error) {
      toast({
        title: 'Could not update that dependency',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <div className="flex flex-col gap-1 text-sm">
      <label htmlFor={`depends-on-${templateId}`} className="font-medium text-content">
        Service depends on
      </label>
      <select
        id={`depends-on-${templateId}`}
        className="h-9 rounded-control border border-border bg-surface px-2 text-sm"
        value={currentDependsOn ?? ''}
        disabled={detail.isPending || update.isPending}
        onChange={onChange}
      >
        <option value="">None — runs parallel from journey start</option>
        {candidates.map((c) => (
          <option key={c.activeTemplateId} value={c.activeTemplateId}>
            {c.name}
          </option>
        ))}
      </select>
    </div>
  )
}

function problemDetail(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Reload the page and try again.'
  return error.problem.detail ?? error.problem.title ?? 'Reload the page and try again.'
}
