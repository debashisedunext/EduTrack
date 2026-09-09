import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObProduct } from '@/api/generated/model/obProduct'
import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'
import type { UserRef } from '@/api/generated/model'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import { useCreateJourneyTemplate, useJourneyTemplate } from './journeyTemplateQueries'
import { cycleFreeCandidates, moveTemplate } from './moduleServiceCatalogue'
import {
  useReorderModuleServiceCatalogue,
  useUpdateModuleServiceDependsOn,
} from './moduleServiceCatalogueQueries'

/**
 * C-123 · OB-07's other half — the Module Service catalogue itself, one card
 * per product, laid out to `docs/prototype/onboarding.html`'s `vTemplates()`:
 * page head with the versioning caption, a create card, a product filter
 * card, then a responsive card grid. `JourneyTemplateDesignerPage` is where a
 * single service's steps are edited; each card's "✎ Edit" opens it.
 *
 * <h2>The reorder is catalogue-wide, the picker is per-card</h2>
 *
 * `sequence` is one number shared by every active template, so the ↑/↓
 * buttons act on the *whole* catalogue's ordering, not the filtered view —
 * exactly the mockup's `msMove`, which reorders `TEMPLATES` regardless of
 * the filter. "Service depends on" is the opposite: one field on one
 * template, editable from any card.
 *
 * <h2>Create wires to the data layer's own `useCreateJourneyTemplate`</h2>
 *
 * The mockup's "Create a new module service" card. A product mid-draft
 * answers `409`, which surfaces verbatim as a toast — the server is the
 * authority on whether a draft already exists, and this page does not guess.
 */
export function ModuleServiceCataloguePage() {
  const navigate = useNavigate()
  const query = useListObProducts()
  const users = useListUsers({ isActive: true, limit: 200 })
  const [filterId, setFilterId] = React.useState<'ALL' | number>('ALL')
  const reorder = useReorderModuleServiceCatalogue()
  const create = useCreateJourneyTemplate()

  const [newName, setNewName] = React.useState('')
  const [newProductId, setNewProductId] = React.useState<number | ''>('')

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
  const userList = users.data?.data ?? []
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

  const visible = filterId === 'ALL' ? products : products.filter((p) => p.id === filterId)

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

  const doCreate = async (event: React.FormEvent) => {
    event.preventDefault()
    const name = newName.trim()
    if (!name || newProductId === '') return
    // Sequence is 0-based and catalogue-wide — the new service joins at the
    // end of the current order, where the ↑/↓ buttons can then move it.
    const nextSequence = active.reduce((max, p) => Math.max(max, p.templateSequence + 1), 0)
    try {
      const template = await create.mutateAsync({
        data: { name, productId: newProductId, sequence: nextSequence },
      })
      toast({ title: `${name} created — add its services and publish v1` })
      navigate(`/onboarding/journey-templates/${template.id}`)
    } catch (error) {
      toast({
        title: 'Could not create that module service',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <div className="flex flex-col gap-4 p-6">
      <header>
        <h1 className="text-h1 text-content">Module Service</h1>
        <p className="mt-0.5 text-caption text-content-muted">
          Each product's journey is defined here as a Module Service — its services, their
          dependencies, task lists and TATs. Editing publishes a new version; in-flight clients
          keep theirs.
        </p>
      </header>

      <form
        onSubmit={doCreate}
        aria-label="New module service"
        className="flex flex-wrap items-end gap-3 rounded-card border border-border bg-surface px-5 py-3.5 shadow-rest"
      >
        <div className="flex min-w-[220px] flex-1 flex-col gap-1 text-sm">
          <label htmlFor="ms-new-name" className="font-medium text-content">
            Create a new module service
          </label>
          <Input
            id="ms-new-name"
            value={newName}
            maxLength={200}
            placeholder="Template name — e.g. RFID Card Rollout"
            onChange={(e) => setNewName(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="ms-new-product" className="font-medium text-content">
            For product
          </label>
          <select
            id="ms-new-product"
            className="h-10 rounded-control border border-border bg-surface px-2 text-sm text-content"
            value={newProductId}
            onChange={(e) => setNewProductId(e.target.value === '' ? '' : Number(e.target.value))}
          >
            <option value="">Choose a product…</option>
            {products.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        <Button type="submit" disabled={create.isPending || !newName.trim() || newProductId === ''}>
          + Create module service
        </Button>
      </form>

      <div className="flex flex-wrap items-center gap-2.5 rounded-card border border-border bg-surface px-5 py-3 shadow-rest">
        <label
          htmlFor="ms-filter"
          className="text-caption font-semibold uppercase tracking-wide text-content-muted"
        >
          Show services for
        </label>
        <select
          id="ms-filter"
          className="h-9 max-w-[260px] rounded-control border border-border bg-surface px-2 text-sm text-content"
          value={filterId === 'ALL' ? 'ALL' : String(filterId)}
          onChange={(e) => setFilterId(e.target.value === 'ALL' ? 'ALL' : Number(e.target.value))}
        >
          <option value="ALL">All products</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
        <span className="text-caption text-content-muted">
          Card order below is the service sequence — it drives the order journeys are instantiated
          and shown for every new client. Use ↑ ↓ to re-sequence.
        </span>
      </div>

      {visible.length === 0 ? (
        <EmptyState title="No products match that filter" description="Choose another product, or clear the filter." />
      ) : (
        <ul role="list" className="grid gap-4 [grid-template-columns:repeat(auto-fit,minmax(320px,1fr))]">
          {visible.map((product) => (
            <ModuleServiceCard
              key={product.id}
              product={product}
              index={product.activeTemplateId != null ? activeOrder.indexOf(product.activeTemplateId) : -1}
              total={activeOrder.length}
              reordering={reorder.isPending}
              catalogueEntries={catalogueEntries}
              users={userList}
              onMove={move}
            />
          ))}
        </ul>
      )}
    </div>
  )
}

function ModuleServiceCard({
  product,
  index,
  total,
  reordering,
  catalogueEntries,
  users,
  onMove,
}: {
  product: ObProduct
  /** Position in the whole (unfiltered) active-template order, or -1 without one. */
  index: number
  total: number
  reordering: boolean
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateId: number | null; name: string }[]
  users: readonly UserRef[]
  onMove: (templateId: number, direction: -1 | 1) => void
}) {
  const hasActive = product.activeTemplateId != null
  /**
   * One detail read per active card, shared with the designer page's cache —
   * it carries the version chip, the steps list and the `ETag` the
   * depends-on `PUT` requires. The catalogue is "a handful of rows" by the
   * contract's own exemption note, so this costs nothing a list this size
   * would notice.
   */
  const detail = useJourneyTemplate(hasActive ? product.activeTemplateId! : null)
  const template = detail.data?.detail
  const dependsOnEntry = catalogueEntries.find(
    (c) => c.activeTemplateId === (product.dependsOnTemplateId ?? null),
  )

  return (
    <li className="flex flex-col gap-2 rounded-card border border-border bg-surface p-4 shadow-rest">
      <div className="flex flex-wrap items-center gap-2">
        {index >= 0 && (
          <Chip variant="neutral" className="tabular-nums" title="Service sequence">
            #{index + 1}
          </Chip>
        )}
        <h3 className="m-0 text-h3 text-content">{product.name}</h3>
        {template && <Chip variant="info">v{template.version}</Chip>}
        {hasActive && (
          <span className="ml-auto inline-flex gap-1">
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
          </span>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Chip variant="neutral">{product.code}</Chip>
        {!product.isActive && <Chip variant="neutral">Retired</Chip>}
        {hasActive ? (
          <>
            <Chip variant="info" title="Sum of all service TATs">
              ⏱ {product.totalTatDays ?? 0}d total TAT
            </Chip>
            <Chip variant="success">Active for product</Chip>
            {dependsOnEntry ? (
              <Chip variant="warning">⛓ after {dependsOnEntry.name}</Chip>
            ) : (
              <Chip variant="neutral">∥ parallel</Chip>
            )}
          </>
        ) : (
          <Chip variant="neutral">No active Module Service yet</Chip>
        )}
        <Chip variant="neutral">{product.journeyCount} journey{product.journeyCount === 1 ? '' : 's'}</Chip>
      </div>

      {hasActive && (
        <DependsOnPicker
          templateId={product.activeTemplateId!}
          productName={product.name}
          catalogueEntries={catalogueEntries}
        />
      )}

      {hasActive && (
        <>
          {template ? (
            <StepSummaryList steps={template.steps} users={users} />
          ) : (
            <Skeleton className="h-16 w-full" />
          )}
          <Button asChild variant="secondary" size="sm" className="self-start">
            <Link to={`/onboarding/journey-templates/${product.activeTemplateId}`}>
              ✎ Edit{template ? ` (publishes v${template.version + 1})` : ''}
            </Link>
          </Button>
        </>
      )}
    </li>
  )
}

/**
 * The mockup card's ordered steps list — one line per service:
 * `name · TATd · owner · ↳ after step n / ∥ parallel · ✍️ client sign-off`.
 */
function StepSummaryList({
  steps,
  users,
}: {
  steps: ObJourneyTemplateStep[]
  users: readonly UserRef[]
}) {
  if (steps.length === 0) {
    return <p className="m-0 text-caption text-content-muted">No services yet — open the designer to add them.</p>
  }

  const ownerOf = (step: ObJourneyTemplateStep): string => {
    if (step.ownerUserId != null) {
      return users.find((u) => u.id === step.ownerUserId)?.displayName ?? `user #${step.ownerUserId}`
    }
    return step.ownerRole ?? 'Unassigned'
  }

  return (
    <ol className="my-1 flex list-decimal flex-col gap-1.5 pl-5 text-sm text-content">
      {steps.map((step) => {
        const depIndex = step.dependsOnStepId != null
          ? steps.findIndex((s) => s.id === step.dependsOnStepId)
          : -1
        return (
          <li key={step.id}>
            {step.name}{' '}
            <span className="text-caption text-content-muted">
              · {step.tatDays}d · {ownerOf(step)} ·{' '}
              {depIndex >= 0 ? `↳ after step ${depIndex + 1}` : '∥ parallel'}
              {step.requiresSignoff && ' · ✍️ client sign-off'}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

/**
 * Loads its own template detail purely for the `ETag` `PUT .../depends-on`
 * requires — `useJourneyTemplate`'s own cache, shared with the card around it
 * and the designer page if the same template is opened there in the same
 * session.
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
    <div className="flex items-center gap-2 text-sm">
      <label htmlFor={`depends-on-${templateId}`} className="whitespace-nowrap font-medium text-content">
        Service depends on
      </label>
      <select
        id={`depends-on-${templateId}`}
        className="h-9 flex-1 rounded-control border border-border bg-surface px-2 text-sm text-content"
        value={currentDependsOn ?? ''}
        disabled={detail.isPending || update.isPending}
        onChange={onChange}
      >
        <option value="">∥ No dependency — runs parallel</option>
        {candidates.map((c) => (
          <option key={c.activeTemplateId} value={c.activeTemplateId}>
            ⛓ {c.name}
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
