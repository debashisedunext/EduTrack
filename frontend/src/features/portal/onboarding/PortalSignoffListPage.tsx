import { Link } from 'react-router-dom'

import { useListPortalSignoffs } from '@/api/generated/portal/portal'
import type { PortalSignoff } from '@/api/generated/model/portalSignoff'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * CP-05 · the sign-off list — pending and past, deep-linking into the §8 flow
 * (Onboarding-Module-Plan.md §8/§9).
 *
 * ## Why a "pending" row has no clickable link to `/signoff`
 *
 * `ob_signoffs.token_hash` is a one-way hash — `ObSignoffTokens`' own reason
 * is "our own database must not be able to yield a working link" — so this
 * screen, or any screen, can never be handed the plaintext a pending row's
 * email carries. The plan's "deep-linking into the same flow" is served two
 * ways instead: a standing link to `/signoff` (OB-09, unchanged — the same
 * page the emailed link opens) so the client can act the moment they have
 * that email open, and `sentToEmail` on each pending row so they know which
 * inbox to search. A live per-row link would need a self-service resend
 * (mint a fresh token, mail it) and that mechanism does not exist anywhere
 * yet — `resendObSignoff` is contract-only, staff side included. Building a
 * portal-only version of a mechanism no one has built for staff was judged
 * out of scope for a list screen; see this task's STREAM-C-TICKETS.md entry.
 *
 * ## Past rows render their own record inline
 *
 * A `SIGNED` or `OBJECTED` row already carries everything worth showing —
 * who signed, when, their note, or the objection — and needs no further
 * click-through: the "flow" for a decided sign-off is reading the decision,
 * not reopening it. `hasCertificate` is always `false` today (B-116, the
 * acceptance PDF, is not built), so no download affordance is rendered for
 * one yet — nothing to wire up when it lands, since the field already
 * exists on the wire (`PortalSignoff.hasCertificate`).
 */
export function PortalSignoffListPage() {
  const { data, isPending, isError } = useListPortalSignoffs()

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-24 w-full rounded-card" />
        <Skeleton className="h-24 w-full rounded-card" />
      </div>
    )
  }

  if (isError || !data) {
    return (
      <EmptyState
        title="Could not load your sign-offs"
        description="Something went wrong. Try reloading the page."
      />
    )
  }

  const signoffs = data.data
  const pending = signoffs.filter((s) => s.status === 'PENDING')
  const past = signoffs.filter((s) => s.status !== 'PENDING')

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-h2 text-content">Sign-offs</h1>
        <p className="mt-1 text-sm text-content-muted">
          Accept or review sign-off requests for your services.
        </p>
      </div>

      <section className="rounded-card border border-border bg-surface p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-h3 text-content">Pending</h2>
          <Link to="/signoff" className="text-sm text-primary hover:underline">
            Open the sign-off page →
          </Link>
        </div>

        {pending.length === 0 ? (
          <p className="mt-3 text-sm text-content-muted">Nothing waiting on you right now.</p>
        ) : (
          <ul className="mt-3 flex flex-col divide-y divide-border">
            {pending.map((signoff) => (
              <PendingRow key={signoff.id} signoff={signoff} />
            ))}
          </ul>
        )}
      </section>

      <section className="rounded-card border border-border bg-surface p-5">
        <h2 className="text-h3 text-content">Past</h2>

        {past.length === 0 ? (
          <p className="mt-3 text-sm text-content-muted">No decided sign-offs yet.</p>
        ) : (
          <ul className="mt-3 flex flex-col divide-y divide-border">
            {past.map((signoff) => (
              <PastRow key={signoff.id} signoff={signoff} />
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function titleOf(signoff: PortalSignoff): string {
  if (signoff.kind === 'GO_LIVE') return 'Go-live confirmation'
  return signoff.stepTitle ?? 'Service sign-off'
}

function PendingRow({ signoff }: { signoff: PortalSignoff }) {
  const expiresAt = signoff.tokenExpiresAt ? new Date(signoff.tokenExpiresAt) : null
  const expired = expiresAt !== null && expiresAt.getTime() < Date.now()

  return (
    <li className="flex flex-col gap-1 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col">
          <span className="text-sm font-medium text-content">{titleOf(signoff)}</span>
          {signoff.productName ? (
            <span className="text-caption text-content-muted">{signoff.productName}</span>
          ) : null}
        </div>
        <Chip variant={expired ? 'danger' : 'warning'}>{expired ? 'Link expired' : 'Awaiting your action'}</Chip>
      </div>
      <p className="text-caption text-content-muted">
        {signoff.sentToEmail
          ? `We emailed a secure link to ${signoff.sentToEmail} on ${new Date(signoff.requestedAt).toLocaleDateString()}.`
          : `Requested ${new Date(signoff.requestedAt).toLocaleDateString()}.`}
        {expiresAt && !expired
          ? ` Open it before ${expiresAt.toLocaleDateString()}.`
          : ''}
        {expired ? ' Ask your point of contact to send a fresh link.' : ''}
      </p>
    </li>
  )
}

function PastRow({ signoff }: { signoff: PortalSignoff }) {
  const isObjected = signoff.status === 'OBJECTED'
  const isSigned = signoff.status === 'SIGNED'

  return (
    <li className="flex flex-col gap-1 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col">
          <span className="text-sm font-medium text-content">{titleOf(signoff)}</span>
          {signoff.productName ? (
            <span className="text-caption text-content-muted">{signoff.productName}</span>
          ) : null}
        </div>
        <StatusChip status={signoff.status} />
      </div>

      {isSigned && signoff.signedAt ? (
        <p className="text-caption text-content-muted">
          Accepted{signoff.signedName ? ` by ${signoff.signedName}` : ''} on{' '}
          {new Date(signoff.signedAt).toLocaleString()}.
          {signoff.acceptanceNote ? ` "${signoff.acceptanceNote}"` : ''}
        </p>
      ) : null}

      {isObjected ? (
        <p className="text-caption text-content-muted">
          Objected{signoff.objectedAt ? ` on ${new Date(signoff.objectedAt).toLocaleString()}` : ''}.
          {signoff.objectionNote ? ` "${signoff.objectionNote}"` : ''}
        </p>
      ) : null}
    </li>
  )
}

function StatusChip({ status }: { status: PortalSignoff['status'] }) {
  switch (status) {
    case 'SIGNED':
      return <Chip variant="success">Accepted</Chip>
    case 'OBJECTED':
      return <Chip variant="danger">Objected</Chip>
    case 'EXPIRED':
      return <Chip variant="neutral">Expired</Chip>
    case 'CANCELLED':
      return <Chip variant="neutral">Cancelled</Chip>
    default:
      return <Chip variant="warning">Pending</Chip>
  }
}

export default PortalSignoffListPage
