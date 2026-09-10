import * as React from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObProduct } from '@/api/generated/model/obProduct'
import type { ObJourneyTemplateSummary } from '@/api/generated/model/obJourneyTemplateSummary'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListObJourneyTemplates } from '@/api/generated/onboarding-journeys/onboarding-journeys'

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
 * per **service**, laid out to `docs/prototype/onboarding.html`'s `vTemplates()`:
 * page head with the versioning caption, a create card, a product filter
 * card, then a responsive card grid. `JourneyTemplateDesignerPage` is where a
 * single service is read and edited; clicking a card opens it.
 *
 * <h2>A card is a summary, and the whole card is the link</h2>
 *
 * A card says what a service *is* — its position, product, step count, total
 * TAT and state — and nothing about how it is built. It used to print every
 * step as a line of text, which made a grid of five services a wall of forty
 * lines nobody scanned; the count is the fact a catalogue is read for, and the
 * steps themselves are one click away on the service's own page.
 *
 * Rename, move to another product and delete moved onto that page with it, in
 * `ModuleServiceAdmin.tsx`. Two controls stayed, and both for the same reason
 * — they are about a service's place *among the others*, which is a fact only
 * this screen has: the ↑/↓ that sets `sequence`, and "Service depends on".
 * Both sit above the card's link overlay on `relative z-10`, so they take
 * their own clicks instead of opening the service.
 *
 * <h2>The reorder is catalogue-wide, not filter-wide</h2>
 *
 * `sequence` is one number shared by every active template, so the ↑/↓
 * buttons act on the *whole* catalogue's ordering, not the filtered view —
 * exactly the mockup's `msMove`, which reorders `TEMPLATES` regardless of
 * the filter. A card's disabled ↑ or ↓ therefore reflects its place in the
 * unfiltered order, which is the order clients are actually boarded in.
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
  const reorder = useReorderModuleServiceCatalogue()
  const create = useCreateJourneyTemplate()

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
    the client's journey list follow — so the ↑/↓ control acts on those and a
    draft-only service has no position to move.
  */
  const activeOrder = services.filter((s) => s.isActive).map((s) => s.id)
  const catalogueEntries = services
    .filter((s) => s.isActive)
    .map((s) => ({
      activeTemplateId: s.id,
      dependsOnTemplateId: s.dependsOnTemplateId ?? null,
      name: s.name,
    }))

  const visible =
    filterId === 'ALL' ? services : services.filter((s) => s.productId === filterId)

  /** Products nobody has written a service for yet — the create form's real candidates. */
  const withoutService = products.filter(
    (p) => p.isActive && !services.some((s) => s.productId === p.id),
  )

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
          Card order below is the service sequence — it drives the order journeys are instantiated
          and shown for every new client. Use ↑ ↓ to re-sequence. Open a card to see and edit its
          steps.
        </span>
      </div>

      {visible.length === 0 ? (
        <EmptyState
          title="No Module Services here yet"
          description="Create one above, or choose another product in the filter."
        />
      ) : (
        <ul role="list" className="grid gap-4 [grid-template-columns:repeat(auto-fit,minmax(320px,1fr))]">
          {visible.map((service) => (
            <ModuleServiceCard
              key={service.id}
              service={service}
              product={productById.get(service.productId)}
              catalogueEntries={catalogueEntries}
              index={activeOrder.indexOf(service.id)}
              total={activeOrder.length}
              reordering={reorder.isPending}
              onMove={move}
            />
          ))}
        </ul>
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
 * One service, as a summary tile. The whole tile is the link to the service's
 * own page — the heading carries a stretched `::after`, which is the one way
 * to make a card clickable without wrapping the *card* in an `<a>` and burying
 * its chips and buttons inside a link a screen reader then has to read as one
 * label. The reorder buttons sit above that overlay on `z-10`.
 */
function ModuleServiceCard({
  service,
  product,
  catalogueEntries,
  index,
  total,
  reordering,
  onMove,
}: {
  service: ObJourneyTemplateSummary
  /** Undefined only if the catalogue and the product list disagree — drawn as the id. */
  product: ObProduct | undefined
  /** Every active service, for the depends-on picker's cycle-free candidates. */
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateId: number | null; name: string }[]
  /** Position in the whole (unfiltered) active order, or -1 for a service that is not active. */
  index: number
  total: number
  reordering: boolean
  onMove: (templateId: number, direction: -1 | 1) => void
}) {
  const hasActive = service.isActive
  const dependsOnEntry = catalogueEntries.find(
    (c) => c.activeTemplateId === (service.dependsOnTemplateId ?? null),
  )

  return (
    <li className="relative flex flex-col gap-2 rounded-card border border-border bg-surface p-4 shadow-rest transition-colors hover:border-primary focus-within:border-primary">
      <div className="flex flex-wrap items-center gap-2">
        {index >= 0 && (
          <Chip variant="neutral" className="tabular-nums" title="Service sequence">
            #{index + 1}
          </Chip>
        )}
        {/* The service is the card's subject; the product is context on it. */}
        <h3 className="m-0 text-h3 text-content">
          <Link
            to={`/onboarding/journey-templates/${service.id}`}
            /*
              `after:absolute inset-0` stretches this link over the whole card,
              so a click anywhere on the tile opens the service while the
              accessible name stays just the service name.
            */
            className="rounded-control after:absolute after:inset-0 after:rounded-card hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            {service.name}
          </Link>
        </h3>
        <Chip variant="info">v{service.version}</Chip>
        {hasActive && (
          /* Above the card's link overlay — these edit the catalogue's order,
             they do not open the service. */
          <span className="relative z-10 ml-auto inline-flex gap-1">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={reordering || index <= 0}
              aria-label={`Move ${service.name} up`}
              onClick={() => onMove(service.id, -1)}
            >
              ↑
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={reordering || index < 0 || index >= total - 1}
              aria-label={`Move ${service.name} down`}
              onClick={() => onMove(service.id, 1)}
            >
              ↓
            </Button>
          </span>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Chip variant="neutral" title="The product this service belongs to">
          {product?.name ?? `Product ${service.productId}`}
        </Chip>
        {product && !product.isActive && <Chip variant="neutral">Product retired</Chip>}
        {/*
          The count, not the list. A catalogue answers "how big is this
          service"; the steps themselves are on the page this card opens.
        */}
        <Chip variant="neutral" title="Services in this journey — open the card to see them">
          ☰ {service.stepCount} step{service.stepCount === 1 ? '' : 's'}
        </Chip>
        <Chip variant="info" title="Sum of this service's step TATs">
          ⏱ {service.totalTatDays}d total TAT
        </Chip>
        {hasActive ? (
          <>
            {/* Active for this *service*. A product publishes several at
                once since V20260910_0030, so "active for product" would now
                read as though the others had been switched off. */}
            <Chip variant="success">Active version</Chip>
            {dependsOnEntry ? (
              <Chip variant="warning">⛓ after {dependsOnEntry.name}</Chip>
            ) : (
              <Chip variant="neutral">∥ parallel</Chip>
            )}
          </>
        ) : (
          /*
            Draft and retired are different states and the card says which:
            `publishedAt == null` has never been live, anything else was and
            has since been superseded — the distinction `ObJourneyTemplate`'s
            own contract note draws.
          */
          <Chip variant="neutral">
            {service.publishedAt == null ? 'Draft — not published' : 'Retired version'}
          </Chip>
        )}
        {product && (
          <Chip variant="neutral">
            {product.journeyCount} journey{product.journeyCount === 1 ? '' : 's'}
          </Chip>
        )}
      </div>

      {hasActive && (
        <DependsOnPicker
          templateId={service.id}
          serviceName={service.name}
          catalogueEntries={catalogueEntries}
        />
      )}

      {/*
        Named rather than left to be discovered by clicking: a tile whose only
        controls belong to the catalogue has to say that the rest of it is a
        way in.
      */}
      <p className="m-0 text-caption text-content-muted">
        {service.isActive
          ? 'Open to read its steps, task lists and TATs — editing there publishes a new version.'
          : 'Open to finish this draft and publish it.'}
      </p>
    </li>
  )
}

/**
 * "Service depends on" — the cross-service dependency (plan §5.5), one field
 * on one template, editable from any card.
 *
 * <p>It stays on the catalogue rather than moving to the service's own page
 * with the rename and the delete, because the choice it offers is *the other
 * services*: setting it is a comparison, and this is the only screen where
 * everything being compared is already on the page.
 *
 * <p>`relative z-10` lifts it above the card's stretched link, so the select
 * takes its own clicks instead of opening the service underneath it.
 *
 * <p>Loads its own template detail purely for the `ETag` `PUT .../depends-on`
 * requires — `useJourneyTemplate`'s own cache, shared with the designer page
 * if the same template is opened there in the same session.
 */
function DependsOnPicker({
  templateId,
  serviceName,
  catalogueEntries,
}: {
  templateId: number
  serviceName: string
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
      toast({ title: `${serviceName}'s dependency updated` })
    } catch (error) {
      toast({
        title: 'Could not update that dependency',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  // `min-w-0` here for the select's reason, one level up: this row is itself a
  // child of the card's flex column, and a shrinkable child inside an
  // unshrinkable parent still overflows.
  return (
    <div className="relative z-10 flex min-w-0 items-center gap-2 text-sm">
      <label
        htmlFor={`depends-on-${templateId}`}
        className="whitespace-nowrap font-medium text-content"
      >
        Service depends on
      </label>
      <select
        id={`depends-on-${templateId}`}
        /*
          `min-w-0` is load-bearing, not tidying. A flex item defaults to
          `min-width: auto`, which for a <select> is the width of its widest
          <option> — and the options here are service names of arbitrary
          length. Without it `flex-1` cannot shrink the control below that
          intrinsic width, so one long service name pushes the select straight
          out of the card and over whatever is beside it.

          `truncate` handles the closed state: the browser ellipsises the
          selected label rather than letting it decide the width. The open
          list is the browser's own popup and is unconstrained either way,
          so a long name is still readable when choosing it.
        */
        className="h-9 min-w-0 flex-1 truncate rounded-control border border-border bg-surface px-2 text-sm text-content"
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
