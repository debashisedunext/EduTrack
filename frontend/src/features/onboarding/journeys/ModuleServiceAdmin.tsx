import * as React from 'react'
import { useNavigate } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObProduct } from '@/api/generated/model/obProduct'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'

import { DependsOnMultiSelect } from './DependsOnMultiSelect'
import { cycleFreeCandidates, moveTemplate } from './moduleServiceCatalogue'
import { useJourneyTemplate } from './journeyTemplateQueries'
import {
  useDeleteModuleService,
  useReorderModuleServiceCatalogue,
  useUpdateModuleService,
  useUpdateModuleServiceDependsOn,
} from './moduleServiceCatalogueQueries'

/**
 * Rename, move to another product, delete — the writes that act on a Module
 * Service as a whole rather than on one version of it, lifted off the OB-07
 * catalogue card and onto the service's own page.
 *
 * <h2>Why they left the card</h2>
 *
 * <p>The catalogue is now a grid of *summaries*: a card names a service, its
 * step count and its state, and clicking anywhere on it opens the service. A
 * card that is one big link cannot also host a rename form and a delete
 * dialog — both are clicks the card would have to swallow, and both need room
 * the tile does not have.
 *
 * <h2>Position and dependency joined the same form</h2>
 *
 * <p>The catalogue card still carries its own ↑/↓ buttons and its own "Service
 * depends on" picker — nothing here replaces them. What changed is that this
 * form offers the same two facts alongside the rename, so an admin fixing up a
 * service can do it in one place instead of bouncing to the catalogue for the
 * order and the dependency. Both are catalogue metadata for the service's
 * <em>active</em> version only — a draft or retired row has no position in the
 * active order and cannot be a live dependency target — so the two fields only
 * appear when {@code isActive}.
 *
 * <h2>Every service is editable; only Delete still asks who is on it</h2>
 *
 * <p>A Module Service is a version chain, and a client boarded on any version
 * of it used to freeze this whole card — name and product both. Neither is
 * frozen now. The server takes both writes at any number of clients and
 * settles the copy the journeys hold in each case:
 * {@code ObJourneyTemplateService#updateModuleService} re-stamps their
 * denormalised {@code service_name} along with the rename, and deliberately
 * leaves their {@code product_id} alone, because that one records the client's
 * purchase rather than where the catalogue files the service. A service 49
 * clients are on is the one most worth being able to correct.
 *
 * <p>So {@code serviceJourneyCount} — chain-wide, the same total on every row
 * of the chain — speaks for <b>Delete alone</b>, which is refused while
 * anybody is on it. That refusal is not a policy waiting to be relaxed the way
 * these two were: {@code fk_ob_journeys_template} is RESTRICT and a running
 * journey renders its steps from these very rows, so there is no version of
 * deleting that does not either fail at the foreign key or empty somebody's
 * ribbon. Publishing a new version over the service is the way out.
 *
 * <p>Disabled <em>and</em> explained, never hidden: a control that vanishes
 * reads as a feature nobody built, and the admin is left looking for it.
 * Naming the count turns "why can't I delete this" into "three clients are on
 * it", which is something they can act on — publish over it instead.
 */
/** Two dependency sets holding the same ids, whatever order they arrived in. */
function sameIds(a: readonly number[], b: readonly number[]): boolean {
  if (a.length !== b.length) return false
  const held = new Set(b)
  return a.every((id) => held.has(id))
}

export function ModuleServiceAdmin({
  templateId,
  serviceName,
  productId,
  productName,
  version,
  serviceJourneyCount,
  products,
  isActive,
  dependsOnTemplateIds,
  activeOrder,
  catalogueEntries,
}: {
  templateId: number
  serviceName: string
  productId: number
  productName: string
  version: number
  /**
   * Chain-wide client journeys — above zero, only Delete is refused
   * server-side. Neither edit is: the rename travels to those journeys, and
   * the product move leaves them recording what was bought.
   */
  serviceJourneyCount: number
  products: readonly ObProduct[]
  /** Whether this version is the service's currently active one. */
  isActive: boolean
  /** Every service this version waits behind — empty for one that runs unheld. */
  dependsOnTemplateIds: readonly number[]
  /** Every currently-active template's id, in catalogue order — the reorder route's own shape. */
  activeOrder: readonly number[]
  /** Every active service, for the depends-on picker's cycle-free candidates. */
  catalogueEntries: { activeTemplateId: number; dependsOnTemplateIds: number[]; name: string }[]
}) {
  const navigate = useNavigate()
  const detail = useJourneyTemplate(templateId)
  const update = useUpdateModuleService()
  const updateDependsOn = useUpdateModuleServiceDependsOn()
  const reorder = useReorderModuleServiceCatalogue()
  const remove = useDeleteModuleService()

  const [editing, setEditing] = React.useState(false)
  const [confirmingDelete, setConfirmingDelete] = React.useState(false)
  const [name, setName] = React.useState(serviceName)
  const [nextProductId, setNextProductId] = React.useState<number>(productId)
  const [nextDependsOn, setNextDependsOn] = React.useState<number[]>([...dependsOnTemplateIds])
  const currentPosition = Math.max(0, activeOrder.indexOf(templateId)) + 1
  const [nextPosition, setNextPosition] = React.useState<number>(currentPosition)
  const dependsOnCandidates = cycleFreeCandidates(templateId, catalogueEntries)
  const saving = update.isPending || updateDependsOn.isPending || reorder.isPending

  const inUse = serviceJourneyCount > 0
  const boarded = `${serviceJourneyCount} client journey${
    serviceJourneyCount === 1 ? ' has' : 's have'
  } been instantiated from this service`
  const deleteLockedReason = inUse
    ? `${boarded} — it can no longer be deleted. Publish a new version over it instead.`
    : undefined

  const openEditor = () => {
    // Reset from the service every time rather than keeping whatever was typed
    // and abandoned last time: a form that reopens holding a discarded edit is
    // one Save away from applying a change nobody meant to make.
    setName(serviceName)
    setNextProductId(productId)
    setNextDependsOn([...dependsOnTemplateIds])
    setNextPosition(currentPosition)
    setEditing(true)
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    try {
      await update.mutateAsync({
        templateId,
        name: trimmed,
        productId: nextProductId,
        etag: detail.data?.etag ?? null,
      })
    } catch (error) {
      toast({
        title: 'Could not update that module service',
        description: problemDetail(error),
        variant: 'danger',
      })
      return
    }

    // Position and dependency are two more routes, not two more fields on
    // this one — each keeps its own precondition, so each is its own call,
    // and a failure here does not roll back the rename that already saved.
    // Compared as sets, not by reference: `nextDependsOn` is a fresh array on
    // every tick, so an identity check would post a write on Save even when
    // nothing was touched — and that write is an extra round trip against a
    // precondition that can fail.
    if (isActive && !sameIds(nextDependsOn, dependsOnTemplateIds)) {
      try {
        // Refetched rather than the `detail` this render started with: the
        // rename just above changed the row's own ETag, and sending the tag
        // read before it would answer `412` against a write that already
        // landed.
        const fresh = await detail.refetch()
        await updateDependsOn.mutateAsync({
          templateId,
          dependsOnTemplateIds: nextDependsOn,
          etag: fresh.data?.etag ?? null,
        })
      } catch (error) {
        toast({
          title: `${trimmed} renamed, but its dependencies could not be updated`,
          description: problemDetail(error),
          variant: 'danger',
        })
      }
    }

    if (isActive && nextPosition !== currentPosition) {
      const nextOrder = moveTemplate([...activeOrder], currentPosition - 1, nextPosition - 1)
      if (nextOrder !== activeOrder) {
        try {
          await reorder.mutateAsync({ templateIds: nextOrder })
        } catch (error) {
          toast({
            title: `${trimmed} renamed, but its position could not be updated`,
            description: problemDetail(error),
            variant: 'danger',
          })
        }
      }
    }

    toast({ title: `${trimmed} updated` })
    setEditing(false)
  }

  const doDelete = async () => {
    try {
      await remove.mutateAsync({ templateId })
      toast({ title: `${serviceName} deleted` })
      setConfirmingDelete(false)
      // Nothing to stay on — every version of this service is gone, this page
      // included, so the catalogue is where the admin now is.
      navigate('/onboarding/journey-templates')
    } catch (error) {
      // The dialog stays open. The refusal is nearly always "somebody was
      // boarded on it while this page was open", and closing would hide the
      // sentence that explains why the service is still there.
      toast({
        title: 'Could not delete that module service',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <section
      aria-labelledby="module-service-admin-heading"
      className="flex flex-col gap-2 rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      <h2 id="module-service-admin-heading" className="m-0 text-h3 text-content">
        Service details
      </h2>
      <p className="m-0 text-sm text-content-muted">
        Name, product and deletion apply to <b>every version</b> of this service — v1 to v{version} —
        and a rename follows through to the client journeys already boarded on it. Its steps are
        versioned instead, in the table above.
      </p>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={saving}
          onClick={openEditor}
        >
          ✎ Edit details
        </Button>
        <Button
          type="button"
          variant="danger"
          size="sm"
          disabled={inUse || remove.isPending}
          title={deleteLockedReason}
          aria-label={`Delete ${serviceName}`}
          onClick={() => setConfirmingDelete(true)}
        >
          🗑 Delete
        </Button>
      </div>

      {/*
        The explanation sits beside the disabled button rather than only in its
        `title`: a tooltip is invisible to a keyboard user who tabs past a
        disabled control, and this is the sentence that stops the section
        reading as broken. It says what is *still* possible in the same breath,
        so an admin who came here to fix a typo does not read the padlock and
        leave.
      */}
      {inUse && (
        <p className="m-0 text-caption text-content-muted">
          🔒 {boarded} — it can no longer be deleted. Editing it is still fine: a rename updates
          every one of those journeys too.
        </p>
      )}

      {editing && (
        <form
          onSubmit={submit}
          aria-label={`Edit ${serviceName}`}
          className="flex flex-col gap-2 rounded-control border border-border bg-subtle p-3"
        >
          <div className="flex flex-col gap-1 text-sm">
            <label htmlFor={`ms-name-${templateId}`} className="font-medium text-content">
              Name
            </label>
            <Input
              id={`ms-name-${templateId}`}
              value={name}
              maxLength={160}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1 text-sm">
            <label htmlFor={`ms-product-${templateId}`} className="font-medium text-content">
              Product
            </label>
            <select
              id={`ms-product-${templateId}`}
              className="h-9 min-w-0 rounded-control border border-border bg-surface px-2 text-sm text-content"
              value={nextProductId}
              aria-describedby={inUse ? `ms-product-note-${templateId}` : undefined}
              onChange={(e) => setNextProductId(Number(e.target.value))}
            >
              {products.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            {/*
              Not a refusal — a consequence. Moving the service re-files the
              catalogue entry, and the journeys already running keep the
              product their client actually bought, which is the only honest
              answer and not one an admin would guess.
            */}
            {inUse && (
              <p id={`ms-product-note-${templateId}`} className="m-0 text-caption text-content-muted">
                Moving it re-files the service. The {serviceJourneyCount} journey
                {serviceJourneyCount === 1 ? '' : 's'} already running stay under the product their
                client bought.
              </p>
            )}
          </div>
          {isActive ? (
            <>
              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor={`ms-position-${templateId}`} className="font-medium text-content">
                  Position in catalogue
                </label>
                <select
                  id={`ms-position-${templateId}`}
                  className="h-9 min-w-0 rounded-control border border-border bg-surface px-2 text-sm text-content"
                  value={nextPosition}
                  onChange={(e) => setNextPosition(Number(e.target.value))}
                >
                  {activeOrder.map((_, i) => (
                    <option key={i} value={i + 1}>
                      #{i + 1} of {activeOrder.length}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-col gap-1 text-sm">
                <span id={`ms-depends-on-label-${templateId}`} className="font-medium text-content">
                  Depends on
                </span>
                {/*
                  Local state here, saved with the rest of the form, where the
                  catalogue row's own copy of this control writes on each tick.
                  The difference is the surrounding gesture rather than an
                  inconsistency: this one sits inside a form with a Save and a
                  Cancel, and a field that wrote immediately would make Cancel
                  a lie about one of the four things on it.
                */}
                <DependsOnMultiSelect
                  id={`ms-depends-on-${templateId}`}
                  label={`Services ${serviceName} depends on`}
                  options={dependsOnCandidates}
                  selectedIds={nextDependsOn}
                  onToggle={(id, next) =>
                    setNextDependsOn((current) =>
                      next ? [...current, id] : current.filter((held) => held !== id),
                    )
                  }
                />
              </div>
            </>
          ) : (
            <p className="m-0 text-caption text-content-muted">
              Position and cross-service dependencies apply only to this service&apos;s active
              version — this one is not it.
            </p>
          )}
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={saving || !name.trim()}>
              Save
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={saving}
              onClick={() => setEditing(false)}
            >
              Cancel
            </Button>
          </div>
        </form>
      )}

      <Modal
        open={confirmingDelete}
        onOpenChange={(next) => {
          if (!next) setConfirmingDelete(false)
        }}
      >
        <ModalContent>
          <ModalHeader>
            <ModalTitle>Delete {serviceName}?</ModalTitle>
            <ModalDescription>
              This removes every version of the service — v1 to v{version} — under {productName},
              with each version&apos;s steps, checklists and document lists. No client has been
              boarded on it, so nothing in flight is affected. It cannot be undone.
            </ModalDescription>
          </ModalHeader>
          <ModalFooter>
            <Button
              variant="secondary"
              size="sm"
              disabled={remove.isPending}
              onClick={() => setConfirmingDelete(false)}
            >
              Cancel
            </Button>
            <Button variant="danger" size="sm" disabled={remove.isPending} onClick={doDelete}>
              {remove.isPending ? 'Deleting…' : 'Delete service'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </section>
  )
}

function problemDetail(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Reload the page and try again.'
  return error.problem.detail ?? error.problem.title ?? 'Reload the page and try again.'
}
