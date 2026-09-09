import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { format, parseISO } from 'date-fns'

import type { ObContact, ObSignoff, ObSignoffKind } from '@/api/generated/model'
import {
  getListObSignoffsQueryKey,
  useCancelObSignoff,
  useGetObClient,
  useListObSignoffs,
  useRequestObSignoff,
  useResendObSignoff,
} from '@/api/generated/onboarding/onboarding'
import {
  getGetObJourneyQueryKey,
  getGetObJourneyStepQueryKey,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { ReasonDialog } from '@/components/ui/reason-dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

/**
 * The staff half of §8's sign-off — request, chase, withdraw, and read the
 * decision back. Mounted twice on OB-05: once per step that
 * `requiresSignoff`, and once per journey for the go-live.
 *
 * ## One component for both kinds, because they differ by two fields
 *
 * `ObSignoffKind` is the whole difference on the wire — a `STEP` sign-off
 * carries a `stepId` and a `GO_LIVE` one must not, which
 * `ck_ob_signoffs_step_matches_kind` enforces in the database and the request
 * body mirrors. Everything else — the OTP link, the fourteen-day token, the
 * five statuses, resend, cancel, the certificate — is identical. Two
 * components would have been the same file twice with a different heading.
 *
 * ## The link is never shown, and there is nothing here to copy
 *
 * A-107 stores the token only as a SHA-256, and `ObSignoff` carries
 * `tokenExpiresAt` but not the token: reading the table does not yield a
 * working link, and neither does reading this panel. So the actions are
 * "email it again" rather than "copy the link" — the mockup's
 * "🔗 Open client sign-off link (simulate)" is a prototype affordance that
 * cannot exist against the real contract, and pretending otherwise would put
 * a dead button on the screen.
 *
 * ## Why the contact is chosen and never defaulted
 *
 * `ObSignoffRequestBody.sentToContactId` is required rather than defaulted to
 * the primary SPOC, and the contract says why: "the person who signs off a
 * data migration is frequently not the person who signs the contract, and a
 * default that is usually right is one nobody checks". The picker therefore
 * opens on no selection, and Request stays disabled until somebody makes one.
 *
 * ## Requesting is offered only where the server would accept it
 *
 * A settled sign-off (`SIGNED`, `OBJECTED`) is a decision, not a draft: the
 * server answers 422 for a re-ask and this offers no button for it. A live
 * one (`PENDING`) is answered with resend and cancel rather than a second
 * request, which would be `ob-signoff-already-pending`'s 409. `EXPIRED` and
 * `CANCELLED` are the two the panel does offer a fresh request on, because
 * they are the two the server accepts one for.
 *
 * The refusal this **cannot** predict is the go-live's own: every service must
 * be finished first (`ob-signoff-journey-incomplete`, 422). The caller decides
 * whether to render this at all, so that gate lives at the call site, where
 * the journey's steps are in hand.
 */
export interface SignoffPanelProps {
  kind: ObSignoffKind
  journeyId: number
  /** Required for `STEP`, absent for `GO_LIVE` — the contract's own pairing. */
  stepId?: number
  /** Whose contacts fill the picker. The page has already read this client,
   * so the lookup below is a cache hit rather than a second request. */
  obClientId: number
}

export function SignoffPanel({ kind, journeyId, stepId, obClientId }: SignoffPanelProps) {
  const queryClient = useQueryClient()
  const [contactId, setContactId] = React.useState<number | ''>('')
  const [cancelling, setCancelling] = React.useState(false)
  const selectId = React.useId()

  const params = { obClientId, journeyId, kind }
  const signoffs = useListObSignoffs(params)
  const client = useGetObClient(obClientId)

  /**
   * The one this panel is about.
   *
   * `listObSignoffs` is filtered by client, journey and kind, which for a
   * go-live is already a single row — but a step's journey can hold one per
   * step, so the step id narrows it the rest of the way. Latest first, because
   * a cancelled request followed by a fresh one must render as the fresh one.
   */
  const signoff: ObSignoff | undefined = React.useMemo(() => {
    const rows = (signoffs.data?.data ?? []).filter((s) => (stepId == null ? true : s.stepId === stepId))
    return [...rows].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt))[0]
  }, [signoffs.data, stepId])

  const contacts: ObContact[] = (client.data?.data?.contacts ?? []).filter((c) => c.isActive)

  const refresh = React.useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: getListObSignoffsQueryKey(params) })
    void queryClient.invalidateQueries({ queryKey: getGetObJourneyQueryKey(journeyId) })
    if (stepId != null) {
      void queryClient.invalidateQueries({ queryKey: getGetObJourneyStepQueryKey(stepId) })
    }
    // A go-live acceptance flips the client to LIVE (`ObClientGoLiveService`),
    // so the header and its banner are stale the moment one settles.
    void queryClient.invalidateQueries({ queryKey: ['/onboarding/clients', obClientId] })
  }, [queryClient, journeyId, stepId, obClientId]) // eslint-disable-line react-hooks/exhaustive-deps

  /** The server's refusal verbatim — `StepActionBar`'s rule, and its reason. */
  const onError = React.useCallback((error: unknown) => {
    const problem = (error as { error?: { detail?: string; title?: string } })?.error
    toast({
      variant: 'danger',
      title: 'That did not go through',
      description: problem?.detail ?? problem?.title ?? 'The server refused it. Reload and try again.',
    })
  }, [])

  const request = useRequestObSignoff({ mutation: { onSuccess: refresh, onError } })
  const resend = useResendObSignoff({ mutation: { onSuccess: refresh, onError } })
  const cancel = useCancelObSignoff({
    mutation: {
      onSuccess: () => {
        setCancelling(false)
        refresh()
      },
      onError,
    },
  })

  const busy = request.isPending || resend.isPending || cancel.isPending
  const status = signoff?.status
  const canRequest = signoff == null || status === 'EXPIRED' || status === 'CANCELLED'
  const isLive = status === 'PENDING'

  const heading = kind === 'GO_LIVE' ? 'Go-live sign-off' : 'Client sign-off'

  return (
    <section
      aria-label={heading}
      className="mt-4 rounded-card border border-border bg-surface p-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="m-0 text-sm font-semibold text-content">
          <span aria-hidden="true">✍️ </span>
          {heading}
        </h4>
        {signoffs.isPending ? <Skeleton className="h-5 w-28" /> : <StatusChip status={status} />}
      </div>

      <p className="m-0 mt-1.5 text-caption text-content-muted">
        {kind === 'GO_LIVE'
          ? 'Every service is finished. Ask the client to accept the journey and go live.'
          : 'This service cannot complete until the client has accepted it.'}
      </p>

      {signoff && (
        <dl className="mt-3 grid gap-x-4 gap-y-2 text-sm [grid-template-columns:repeat(auto-fit,minmax(160px,1fr))]">
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Sent to</dt>
            <dd className="m-0 mt-0.5 text-content">
              {signoff.sentToContact.name}
              <div className="text-caption text-content-muted">{signoff.sentToContact.email}</div>
            </dd>
          </div>
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Requested</dt>
            <dd className="m-0 mt-0.5 text-content">
              {formatMoment(signoff.requestedAt)}
              {signoff.requestedBy && (
                <div className="text-caption text-content-muted">by {signoff.requestedBy.displayName}</div>
              )}
            </dd>
          </div>
          {isLive && (
            <div>
              <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">
                Link expires
              </dt>
              <dd className="m-0 mt-0.5 text-content">{formatMoment(signoff.tokenExpiresAt)}</dd>
            </div>
          )}
          {signoff.signedAt && (
            <div>
              <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">
                Accepted
              </dt>
              <dd className="m-0 mt-0.5 text-content">{formatMoment(signoff.signedAt)}</dd>
            </div>
          )}
          {signoff.objectedAt && (
            <div>
              <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">
                Objected
              </dt>
              <dd className="m-0 mt-0.5 text-content">{formatMoment(signoff.objectedAt)}</dd>
            </div>
          )}
        </dl>
      )}

      {/*
        B-117's objection, stated rather than left to the status chip. An
        objection reverts the step and is the one outcome somebody has to go
        and act on — the note itself is on `getObSignoff`, not on this list
        read, so this points at it rather than inventing a summary.
      */}
      {status === 'OBJECTED' && (
        <p className="m-0 mt-3 rounded-control bg-level-critical-soft px-3 py-2 text-sm text-danger-text">
          ⚠ The client raised an objection. The service has been reopened — resolve what they
          raised, then request a fresh sign-off.
        </p>
      )}

      {canRequest && (
        <div className="mt-3 flex flex-wrap items-end gap-2 border-t border-border pt-3">
          <div className="min-w-[220px] flex-1">
            <label
              htmlFor={selectId}
              className="block text-caption font-semibold uppercase tracking-wide text-content-muted"
            >
              Send to
            </label>
            <select
              id={selectId}
              className="mt-1 h-9 w-full rounded-control border border-border bg-surface px-2 text-sm text-content"
              value={contactId}
              onChange={(event) => setContactId(event.target.value ? Number(event.target.value) : '')}
            >
              {/* Deliberately unselected — see the docstring on why the SPOC
                  is not a default. */}
              <option value="">Choose a contact…</option>
              {contacts.map((contact) => (
                <option key={contact.id} value={contact.id}>
                  {contact.name}
                  {contact.designation ? ` · ${contact.designation}` : ''}
                  {contact.isPrimary ? ' (primary)' : ''}
                </option>
              ))}
            </select>
          </div>
          <Button
            size="sm"
            disabled={contactId === '' || busy}
            title={contactId === '' ? 'Choose which contact signs this off first.' : undefined}
            onClick={() =>
              request.mutate({
                journeyId,
                data: { kind, sentToContactId: Number(contactId), ...(stepId != null && { stepId }) },
              })
            }
          >
            {request.isPending ? 'Sending…' : '✉ Request sign-off'}
          </Button>
        </div>
      )}

      {isLive && (
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border pt-3">
          <Button size="sm" variant="secondary" disabled={busy} onClick={() => resend.mutate({ signoffId: signoff.id })}>
            {resend.isPending ? 'Sending…' : '✉ Email the link again'}
          </Button>
          <Button size="sm" variant="danger" disabled={busy} onClick={() => setCancelling(true)}>
            Withdraw
          </Button>
          <p role="status" className="w-full text-caption text-content-muted">
            Emailing again issues a fresh link and stops the previous one working — two live links to
            one decision would leave nothing to say which was used.
          </p>
        </div>
      )}

      {status === 'SIGNED' && signoff?.hasCertificate && (
        <div className="mt-3 border-t border-border pt-3">
          <a
            className="text-sm font-medium text-primary"
            href={`/api/v1/onboarding/signoffs/${signoff.id}/certificate`}
            target="_blank"
            rel="noreferrer"
          >
            📄 Download the signed certificate (PDF)
          </a>
        </div>
      )}

      <ReasonDialog
        open={cancelling}
        onOpenChange={setCancelling}
        title="Withdraw this sign-off request"
        description="The client's link stops working immediately. The withdrawn request stays on the record with your reason, so say why."
        fieldLabel="Why is it being withdrawn?"
        confirmLabel="Withdraw the request"
        confirmVariant="danger"
        isPending={cancel.isPending}
        onConfirm={(reason) => cancel.mutate({ signoffId: signoff!.id, data: { reason } })}
      />
    </section>
  )
}

/** The five statuses, each said in words rather than left to a colour. */
function StatusChip({ status }: { status?: string }) {
  if (!status) return <Chip variant="neutral">Not requested</Chip>
  if (status === 'PENDING') return <Chip variant="info">Awaiting the client</Chip>
  if (status === 'SIGNED') return <Chip variant="success">✓ Accepted</Chip>
  if (status === 'OBJECTED') return <Chip variant="danger">⚠ Objection raised</Chip>
  if (status === 'EXPIRED') return <Chip variant="warning">Link expired</Chip>
  return <Chip variant="neutral">Withdrawn</Chip>
}

function formatMoment(value: string): string {
  const parsed = parseISO(value)
  return Number.isNaN(parsed.getTime()) ? value : format(parsed, 'd MMM yyyy, HH:mm')
}
