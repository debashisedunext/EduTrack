import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObProduct } from '@/api/generated/model/obProduct'
import type { ObJourneyTemplateSummary } from '@/api/generated/model/obJourneyTemplateSummary'
import type { UserRef } from '@/api/generated/model/userRef'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListObJourneyTemplates } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useListUsers } from '@/api/generated/users/users'

import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { toast } from '@/components/ui/use-toast'

import { DependsOnMultiSelect } from './DependsOnMultiSelect'
import { useCreateJourneyTemplate, useJourneyTemplate } from './journeyTemplateQueries'
import { checklistCount, cycleFreeCandidates, defaultImplementor } from './moduleServiceCatalogue'
import { useUpdateModuleServiceDependsOn } from './moduleServiceCatalogueQueries'

/**
 * C-123 · OB-07's other half — the Module Service catalogue itself, one **row**
 * per service: page head with the versioning caption, a create card, a product
 * filter card, then the table. `JourneyTemplateDesignerPage` is where a single
 * service is read and edited; clicking a row opens it.
 *
 * <h2>A table, not a card grid</h2>
 *
 * This screen was a responsive grid of tiles, each carrying the name, five to
 * seven chips and its own controls. Nothing lined up: step counts and TATs
 * could not be compared down a column, and the sequence — which is the fact
 * this screen exists to show — was readable only by counting tiles left to
 * right as they wrapped. Seven columns say the same things in a shape a reader
 * can scan.
 *
 * <h2>What the columns are, and what left with the cards</h2>
 *
 * Position, name (with its product beneath), default implementor, step count,
 * checklist-item count, the cross-service dependency, and TAT. **Product,
 * version, state, the in-use count and the ↑/↓ reorder pair were dropped** —
 * the first four are on the service's own page, and re-sequencing lives in
 * `ModuleServiceAdmin`'s form beside the rename, which is where a service's
 * own facts are edited. One consequence is deliberate and worth knowing: with
 * neither version nor state on the row, a draft reads like a live service
 * apart from having no position — `#` says "Not in order".
 *
 * <h2>Two columns are derived from the detail read</h2>
 *
 * Neither the checklist count nor the implementor is on
 * `ObJourneyTemplateSummary` — items and owners nest inside the steps of the
 * detail read, which this page already fetches per active row for the
 * depends-on picker's ETag. Totalling them here costs one request per draft
 * row and a moment's "…" in two cells; the long-run answer is a
 * `checklistItemCount` on the list row, which is a contract change.
 *
 * <h2>Create wires to the data layer's own `useCreateJourneyTemplate`</h2>
 *
 * The mockup's "Create a new module service" card. A duplicate *name* within
 * one product answers `409`, which surfaces verbatim as a toast — the server
 * is the authority and this page does not guess.
 *
 * <h2>A card is a service, and a service is not a version</h2>
 *
 * A product sells several named services and each has its own version chain,
 * so `listObJourneyTemplates` returns every version of every one of them. The
 * card is the *head* of a chain: the active version where the service has one,
 * otherwise its newest — a service with an active v3 and a draft v4 is one
 * card showing v3, not two cards. Grouped on `(productId, name)`, which is
 * exactly the key `uq_ob_journey_templates_version` now uses, so the grouping
 * here and the uniqueness rule in the database cannot drift apart.
 */
export function ModuleServiceCataloguePage() {
  const navigate = useNavigate()
  const query = useListObProducts()
  const templates = useListObJourneyTemplates()
  const [filterId, setFilterId] = React.useState<'ALL' | number>('ALL')
  const create = useCreateJourneyTemplate()
  /* Names the step owners the implementor column reads — the designer's own
     source for exactly the same lookup. */
  const users = useListUsers({ isActive: true, limit: 200 })

  const [newName, setNewName] = React.useState('')
  const [newProductId, setNewProductId] = React.useState<number | ''>('')

  if (query.isPending || templates.isPending) {
    return <Skeleton className="h-screen w-full" />
  }
  if (query.isError || !query.data || templates.isError) {
    return (
      <EmptyState
        title="Could not load the Module Service catalogue"
        description="Reload the page to try again."
      />
    )
  }

  const allTemplates = templates.data?.data ?? []

  const products = query.data.data
  const productById = new Map(products.map((p) => [p.id, p]))

  /*
    One card per service: the active version where a service has one, its
    newest otherwise. Grouped on (productId, name) — the same key the database
    keys version uniqueness on, so the two cannot disagree about what "one
    service" means.
  */
  const heads = new Map<string, ObJourneyTemplateSummary>()
  for (const row of allTemplates) {
    const key = `${row.productId} ${row.name}`
    const held = heads.get(key)
    if (!held || (row.isActive && !held.isActive) || (!held.isActive && row.version > held.version)) {
      heads.set(key, row)
    }
  }
  const services = [...heads.values()].sort(
    (a, b) => a.sequence - b.sequence || a.productId - b.productId || a.name.localeCompare(b.name),
  )

  /*
    `sequence` orders the *active* services, which is what instantiation and
    the client's journey list follow — so a draft-only service has no position
    in it, and its row says so rather than printing a misleading number.
  */
  const activeOrder = services.filter((s) => s.isActive).map((s) => s.id)
  const catalogueEntries = services
    .filter((s) => s.isActive)
    .map((s) => ({
      activeTemplateId: s.id,
      dependsOnTemplateIds: s.dependsOnTemplateIds ?? [],
      name: s.name,
    }))

  const visible =
    filterId === 'ALL' ? services : services.filter((s) => s.productId === filterId)

  /** Products nobody has written a service for yet — the create form's real candidates. */
  const withoutService = products.filter(
    (p) => p.isActive && !services.some((s) => s.productId === p.id),
  )

  const doCreate = async (event: React.FormEvent) => {
    event.preventDefault()
    const name = newName.trim()
    if (!name || newProductId === '') return
    // Sequence is 0-based and catalogue-wide — the new service joins at the
    // end of the current order, where the ↑/↓ buttons can then move it.
    // Measured across every service, not only the active ones: two drafts
    // created back to back would otherwise be handed the same position.
    const nextSequence = services.reduce((max, s) => Math.max(max, s.sequence + 1), 0)
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
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-h1 text-content">Module Service</h1>
          <p className="mt-0.5 text-caption text-content-muted">
            Each product's journey is defined here as a Module Service — its services, their
            dependencies, task lists and TATs. Editing publishes a new version; in-flight clients
            keep theirs.
          </p>
        </div>
        {/*
          The role master is reachable from OB-08 (Roles & module access),
          which is where roles are administered, and from the step form in the
          designer — where the owner picker is a closed list and "the role I
          want is not here" has to be answerable in place. It is deliberately
          not offered here as well: this page defines services, not roles, and
          three doors to one screen is two more than the reader needs.
        */}
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
          Row order below is the service sequence — it drives the order journeys are instantiated
          and shown for every new client. Open a row to see and edit its steps, or to re-sequence
          it.
        </span>
      </div>

      {visible.length === 0 ? (
        <EmptyState
          title="No Module Services here yet"
          description="Create one above, or choose another product in the filter."
        />
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-16" title="Position in the catalogue sequence">
                  #
                </TableHead>
                <TableHead>Module/Service</TableHead>
                <TableHead title="Who this service lands on when a client is boarded">
                  Default Implementor
                </TableHead>
                <TableHead className="w-24">Steps</TableHead>
                <TableHead className="w-28" title="Checklist items across every step">
                  Checklists
                </TableHead>
                <TableHead className="w-[17rem]">Depends on</TableHead>
                <TableHead className="w-24 text-right" title="Sum of this service's step TATs">
                  TAT
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map((service) => (
                <ModuleServiceRow
                  key={service.id}
                  service={service}
                  product={productById.get(service.productId)}
                  catalogueEntries={catalogueEntries}
                  index={activeOrder.indexOf(service.id)}
                  users={users.data?.data ?? []}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/*
        Named rather than left blank. A product with no service cannot be
        bought — OB-04's picker requires one — and the only place that fact
        was previously visible was a card this page no longer draws, now that
        cards are services.
      */}
      {filterId === 'ALL' && withoutService.length > 0 && (
        <p className="text-caption text-content-muted">
          No Module Service yet:{' '}
          <span className="text-content">{withoutService.map((p) => p.name).join(', ')}</span> — a
          product cannot be bought until one is published for it.
        </p>
      )}
    </div>
  )
}

/**
 * One service, as a table row. The name is the link to its own page and the
 * whole row navigates too — a reader aiming at a row rather than at nine
 * characters of link text is the common case, and the anchor keeps
 * middle-click and "copy link" working. The depends-on select stops its own
 * clicks from reaching the row.
 *
 * Two cells wait on the service's detail read: the implementor and the
 * checklist total. They say "…" while it is in flight rather than "0", which
 * would be a claim rather than a gap.
 */
function ModuleServiceRow({
  service,
  product,
  catalogueEntries,
  index,
  users,
}: {
  service: ObJourneyTemplateSummary
  /** Undefined only if the catalogue and the product list disagree — drawn as the id. */
  product: ObProduct | undefined
  /** Every active service, for the depends-on picker's cycle-free candidates. */
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateIds: number[]; name: string }[]
  /** Position in the whole (unfiltered) active order, or -1 for a service that is not active. */
  index: number
  users: readonly UserRef[]
}) {
  const navigate = useNavigate()
  const detail = useJourneyTemplate(service.id)
  const steps = detail.data?.detail.steps ?? []
  const implementor = defaultImplementor(steps, users)
  const checklists = checklistCount(steps)
  /*
    A draft's dependencies, named. Read off the summary row rather than the
    detail, because a draft draws no picker and so never fetches one — and
    resolved through `catalogueEntries` so a dependency on a service that has
    since been retired is counted but not named, rather than printed as a
    bare id.
  */
  const draftDependsOn = (service.dependsOnTemplateIds ?? [])
    .map((id) => catalogueEntries.find((c) => c.activeTemplateId === id)?.name)
    .filter((name): name is string => name != null)
  const open = () => navigate(`/onboarding/journey-templates/${service.id}`)

  return (
    <TableRow
      tabIndex={0}
      onClick={open}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && e.target === e.currentTarget) open()
      }}
      className="cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
    >
      <TableCell className="tabular-nums text-content-muted">
        {index >= 0 ? (
          index + 1
        ) : (
          /* A draft or retired service has no place in the active order.
             Words rather than a dash: the cell is not missing a number, there
             is no number for it to hold. */
          <span title="Only an active service holds a position in the catalogue sequence.">
            Not in order
          </span>
        )}
      </TableCell>
      <TableCell>
        <Link
          to={`/onboarding/journey-templates/${service.id}`}
          onClick={(e) => e.stopPropagation()}
          className="font-medium text-content hover:text-primary hover:underline"
        >
          {service.name}
        </Link>
        {/* The product is context on the service, not a column of its own —
            without it the "All products" view cannot say whose service a row
            is. */}
        <span className="mt-0.5 block text-caption text-content-muted">
          {product?.name ?? `Product ${service.productId}`}
          {product && !product.isActive && ' · retired'}
        </span>
      </TableCell>
      <TableCell title={implementor.hint}>
        {detail.isPending ? (
          <span className="text-content-muted">…</span>
        ) : (
          <>
            <span className={implementor.named ? 'text-content' : 'text-content-muted'}>
              {implementor.label}
            </span>
            {implementor.extra !== '' && (
              <span className="ml-1.5 text-caption text-content-muted">{implementor.extra}</span>
            )}
          </>
        )}
      </TableCell>
      <TableCell
        className="tabular-nums text-content-muted"
        title="Services in this journey — open the row to see them"
      >
        ☰ {service.stepCount}
      </TableCell>
      <TableCell
        className="tabular-nums text-content-muted"
        title="Checklist items across every step of this service"
      >
        {detail.isPending ? '…' : `☑ ${checklists}`}
      </TableCell>
      <TableCell>
        {service.isActive ? (
          <DependsOnPicker
            templateId={service.id}
            serviceName={service.name}
            catalogueEntries={catalogueEntries}
          />
        ) : (
          /* A draft holds no live dependency — the picker is an active
             service's control, and the row says which of the two states this
             is rather than showing a control that would refuse. */
          <span
            className="text-caption text-content-muted"
            title="Drafts hold no position and no live dependency."
          >
            {draftDependsOn.length > 0 ? `⛓ after ${draftDependsOn.join(', ')}` : '∥ parallel'}
          </span>
        )}
      </TableCell>
      <TableCell className="whitespace-nowrap text-right tabular-nums text-content-muted">
        ⏱ {service.totalTatDays}d
      </TableCell>
    </TableRow>
  )
}

/**
 * "Depends on" — the cross-service dependency (plan §5.5), **a set** of other
 * services, editable from any row.
 *
 * <p>It stays on the catalogue rather than moving to the service's own page
 * with the rename and the delete, because the choice it offers is *the other
 * services*: setting it is a comparison, and this is the only screen where
 * everything being compared is already on the page. That argument gets
 * stronger with a set rather than weaker — picking three of nine services is
 * more of a comparison than picking one.
 *
 * <p>The row it sits in is itself clickable, so the control stops its own
 * clicks rather than opening the service underneath it.
 *
 * <p>Loads its own template detail for two things now: the `ETag` that
 * `PUT .../depends-on` requires, and the current set. The set is read from
 * the detail rather than from the summary row this component's parent already
 * holds, so the value sent back is the one that matches the tag sent with it
 * — reading the selection from one response and the precondition from another
 * is how a picker sends a set the server has already superseded.
 */
function DependsOnPicker({
  templateId,
  serviceName,
  catalogueEntries,
}: {
  templateId: number
  serviceName: string
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateIds: number[]; name: string }[]
}) {
  const detail = useJourneyTemplate(templateId)
  const update = useUpdateModuleServiceDependsOn()

  const candidates = cycleFreeCandidates(templateId, catalogueEntries)
  const current = detail.data?.detail.dependsOnTemplateIds ?? []

  const onToggle = async (dependsOnId: number, next: boolean) => {
    /*
      The whole set on every call — the route replaces rather than patches.
      Built from `current` at the moment of the tick, which is the set the
      `ETag` beside it describes: a stale pair here would be answered `412`
      rather than silently writing the wrong thing.
    */
    const dependsOnTemplateIds = next
      ? [...current, dependsOnId]
      : current.filter((id) => id !== dependsOnId)
    try {
      await update.mutateAsync({ templateId, dependsOnTemplateIds, etag: detail.data?.etag ?? null })
      toast({
        title:
          dependsOnTemplateIds.length === 0
            ? `${serviceName} now runs in parallel`
            : `${serviceName}'s dependencies updated`,
      })
    } catch (error) {
      toast({
        title: 'Could not update that dependency',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  // `min-w-0` here for the control's own reason, one level up: a shrinkable
  // child inside an unshrinkable parent still overflows.
  return (
    <div className="flex min-w-0 items-center gap-2 text-sm" onClick={(e) => e.stopPropagation()}>
      <DependsOnMultiSelect
        id={`depends-on-${templateId}`}
        label={`Services ${serviceName} depends on`}
        options={candidates}
        selectedIds={current}
        disabled={detail.isPending}
        busy={update.isPending}
        onToggle={(id, next) => void onToggle(id, next)}
      />
    </div>
  )
}

function problemDetail(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Reload the page and try again.'
  return error.problem.detail ?? error.problem.title ?? 'Reload the page and try again.'
}
