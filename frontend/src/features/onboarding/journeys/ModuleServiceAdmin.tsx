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

import { useJourneyTemplate } from './journeyTemplateQueries'
import { useDeleteModuleService, useUpdateModuleService } from './moduleServiceCatalogueQueries'

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
 * the tile does not have. The cross-service depends-on picker is the
 * deliberate exception and stayed on the card: it is a *comparison* between
 * services, so it belongs where the other services are on screen.
 *
 * <h2>The rule both of these turn on is unchanged</h2>
 *
 * <p>A Module Service is a version chain, and the server refuses to rename or
 * delete one the moment a client has been boarded on any version of it — see
 * {@code ObJourneyTemplateService#updateModuleService}.
 * {@code serviceJourneyCount} is that number, chain-wide, on every row of the
 * chain, so the page can say so before the admin clicks rather than after a
 * {@code 409}.
 *
 * <p>Disabled <em>and</em> explained, never hidden: a control that vanishes
 * reads as a feature nobody built, and the admin is left looking for it.
 * Naming the count turns "why can't I delete this" into "three clients are on
 * it", which is something they can act on — publish over it instead.
 */
export function ModuleServiceAdmin({
  templateId,
  serviceName,
  productId,
  productName,
  version,
  serviceJourneyCount,
  products,
}: {
  templateId: number
  serviceName: string
  productId: number
  productName: string
  version: number
  /** Chain-wide client journeys — above zero, the service is frozen. */
  serviceJourneyCount: number
  products: readonly ObProduct[]
}) {
  const navigate = useNavigate()
  const detail = useJourneyTemplate(templateId)
  const update = useUpdateModuleService()
  const remove = useDeleteModuleService()

  const [editing, setEditing] = React.useState(false)
  const [confirmingDelete, setConfirmingDelete] = React.useState(false)
  const [name, setName] = React.useState(serviceName)
  const [nextProductId, setNextProductId] = React.useState<number>(productId)

  const inUse = serviceJourneyCount > 0
  const lockedReason = inUse
    ? `${serviceJourneyCount} client journey${
        serviceJourneyCount === 1 ? ' has' : 's have'
      } been instantiated from this service — it can no longer be renamed or deleted. ` +
      'Publish a new version to change it.'
    : undefined

  const openEditor = () => {
    // Reset from the service every time rather than keeping whatever was typed
    // and abandoned last time: a form that reopens holding a discarded edit is
    // one Save away from applying a change nobody meant to make.
    setName(serviceName)
    setNextProductId(productId)
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
      toast({ title: `${trimmed} updated` })
      setEditing(false)
    } catch (error) {
      toast({
        title: 'Could not update that module service',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
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
        Name, product and deletion apply to <b>every version</b> of this service — v1 to v{version}.
        Its steps are versioned instead, in the table above.
      </p>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={inUse || update.isPending}
          title={lockedReason}
          onClick={openEditor}
        >
          ✎ Edit details
        </Button>
        <Button
          type="button"
          variant="danger"
          size="sm"
          disabled={inUse || remove.isPending}
          title={lockedReason}
          aria-label={`Delete ${serviceName}`}
          onClick={() => setConfirmingDelete(true)}
        >
          🗑 Delete
        </Button>
      </div>

      {/*
        The explanation sits beside the disabled buttons rather than only in
        their `title`: a tooltip is invisible to a keyboard user who tabs past
        a disabled control, and this is the sentence that stops the section
        reading as broken.
      */}
      {inUse && <p className="m-0 text-caption text-content-muted">🔒 {lockedReason}</p>}

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
              onChange={(e) => setNextProductId(Number(e.target.value))}
            >
              {products.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={update.isPending || !name.trim()}>
              Save
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={update.isPending}
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
