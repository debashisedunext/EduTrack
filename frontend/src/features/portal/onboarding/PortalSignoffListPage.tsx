import { Link } from 'react-router-dom'

import { useListPortalSignoffs } from '@/api/generated/portal/portal'
import type { PortalSignoff } from '@/api/generated/model/portalSignoff'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * CP-05 · the sign-off list — pending and past (Onboarding-Module-Plan.md
 * §8/§9).
 *
 * ## A pending row now opens a page that can actually decide it
 *
 * It used to link nowhere. `ob_signoffs.token_hash` is a one-way hash —
 * `ObSignoffTokens`' own reason is "our own database must not be able to yield
 * a working link" — so no screen can ever be handed the plaintext a pending
 * row's email carries, and a per-row deep link into OB-09 was impossible
 * without a self-service resend that still does not exist (`resendObSignoff`
 * is contract-only, staff side included). What this screen could offer was a
 * standing link to `/signoff` and the inbox to search for the email.
 *
 * That constraint was about the *token*, and the token is no longer the only
 * way in. A row links to `PortalSignoffDetailPage`, on this same authenticated
 * surface, where the client accepts or objects with no link and no OTP at all —
 * see `PortalSignoffDecisionService` for why a portal session is a stronger
 * proof of the same two facts rather than a weaker one.
 *
 * `sentToEmail` stays on the row. The email still exists and still works, and a
 * client part-way through it is owed the inbox it went to; plan §8 keeps that
 * path as the legal record for the clients — most of them — with no portal
 * account at all.
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
        <h2 className="text-h3 text-content">Pending</h2>

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

/**
 * `tokenExpiresAt` is no longer rendered as a deadline, and deliberately not.
 *
 * It caps the *emailed link*, and this row no longer sends the client to one —
 * `PortalSignoffDecisionService` explains why an expired token does not gate
 * the portal path, since a caller who authenticated never presented it. Drawing
 * "link expired" beside a button that works regardless would tell the reader
 * they have missed a deadline they have not missed, which is the one thing this
 * row must not do.
 */
function PendingRow({ signoff }: { signoff: PortalSignoff }) {
  return (
    <li className="py-3">
      <Link
        to={`/portal/onboarding/signoffs/${signoff.id}`}
        className="group flex flex-col gap-1 rounded-card focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-col">
            <span className="text-sm font-medium text-content group-hover:underline">
              {titleOf(signoff)}
            </span>
            {signoff.productName ? (
              <span className="text-caption text-content-muted">{signoff.productName}</span>
            ) : null}
          </div>
          <Chip variant="warning">Awaiting your decision</Chip>
        </div>
        <p className="text-caption text-content-muted">
          Requested {new Date(signoff.requestedAt).toLocaleDateString()}. Open it to review
          and accept, or raise an objection.
          {signoff.sentToEmail ? ` We also emailed it to ${signoff.sentToEmail}.` : ''}
        </p>
      </Link>
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
