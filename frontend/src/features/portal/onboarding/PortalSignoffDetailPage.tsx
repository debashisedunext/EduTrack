import * as React from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'

import {
  acceptPortalSignoff,
  getGetPortalSignoffQueryKey,
  getListPortalSignoffsQueryKey,
  getGetPortalOnboardingHomeQueryKey,
  objectPortalSignoff,
  submitPortalCsat,
  useGetPortalSignoff,
} from '@/api/generated/portal/portal'
import type {
  PortalSignoffDecision,
  PortalSignoffReview,
} from '@/api/generated/model'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

/**
 * CP-05 · deciding a sign-off inside the portal — the shell-ful counterpart to
 * OB-09.
 *
 * ## Why this page exists beside `PublicSignoffPage` rather than replacing it
 *
 * OB-09 spends two steps proving what a portal session already proves. The
 * mailed link establishes possession of a mailbox and the OTP establishes who
 * is holding it, because that page has no principal at all — it is read by a
 * SPOC who has no account here and never will. A reader of *this* page signed
 * in, so both facts are already established, more strongly, before the first
 * byte renders. `PortalSignoffDecisionService` carries the full argument.
 *
 * So the public page stays exactly as it is, and so does the email. Plan §8:
 * "the link+OTP path still works without a portal login and remains the legal
 * record" — and the portal login is an explicit per-client option (plan §2), so
 * most clients still arrive that way. This is a second door for the ones who
 * have an account, not a replacement for the first.
 *
 * ## Three states, not five
 *
 * The public page has to carry an identify step and a "this link is not
 * complete" state. Neither exists here: the route is authenticated, the id is
 * in the path, and `PortalRequireAuth` has already answered the question that
 * the token and the OTP were answering. What is left is review → decided, plus
 * the optional survey that follows a go-live.
 *
 * ## `gateFailures` is deliberately not rendered
 *
 * `AcceptedPanel` on the public page states the reason and it holds here word
 * for word: those codes tell the *owner* what to attach and mean nothing to the
 * person reading this. An acceptance that our own gate could not act on is
 * still a recorded acceptance — "your acceptance is recorded; we are finishing
 * our side" is the whole of what this reader needs.
 */
export function PortalSignoffDetailPage() {
  const params = useParams<{ signoffId: string }>()
  const signoffId = Number(params.signoffId)
  const queryClient = useQueryClient()

  const [decision, setDecision] = React.useState<PortalSignoffDecision | null>(null)

  const { data, isPending, isError, error } = useGetPortalSignoff(signoffId, {
    query: { enabled: Number.isFinite(signoffId) },
  })

  /**
   * A decision changes the list, the home page's journey strips (a completed
   * step moves a dot) and this row. Invalidated together rather than
   * individually, on `PortalPrereqTaskDetailPage`'s own precedent for the same
   * fan-out after a submit.
   */
  const onDecided = React.useCallback(
    (result: PortalSignoffDecision) => {
      setDecision(result)
      void queryClient.invalidateQueries({ queryKey: getGetPortalSignoffQueryKey(signoffId) })
      void queryClient.invalidateQueries({ queryKey: getListPortalSignoffsQueryKey() })
      void queryClient.invalidateQueries({ queryKey: getGetPortalOnboardingHomeQueryKey() })
    },
    [queryClient, signoffId],
  )

  if (!Number.isFinite(signoffId)) {
    return <EmptyState title="Sign-off not found" />
  }

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-8 w-64 rounded-card" />
        <Skeleton className="h-48 w-full rounded-card" />
      </div>
    )
  }

  if (isError || !data) {
    // 404 covers "not yours" as well as "not there" — the server does not
    // distinguish them and neither does this.
    const missing = error instanceof ApiError && error.status === 404
    return (
      <EmptyState
        title={missing ? 'Sign-off not found' : 'Could not load this sign-off'}
        description={
          missing
            ? 'It may have been withdrawn, or it may not belong to your account.'
            : 'Something went wrong. Try reloading the page.'
        }
      />
    )
  }

  const review = data.data

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link to="/portal/onboarding/signoffs" className="text-sm text-primary hover:underline">
          ← All sign-offs
        </Link>
      </div>

      <ReviewHeader review={review} decision={decision} />

      {review.checklist.length > 0 ? <ChecklistCard review={review} /> : null}

      {decision ? (
        <DecidedCard decision={decision} signoffId={signoffId} />
      ) : review.canDecide ? (
        <DecideCard signoffId={signoffId} onDecided={onDecided} />
      ) : (
        <ClosedCard review={review} />
      )}
    </div>
  )
}

function titleOf(review: PortalSignoffReview): string {
  if (review.kind === 'GO_LIVE') return 'Go-live confirmation'
  return review.stepTitle ?? 'Service sign-off'
}

function ReviewHeader({
  review,
  decision,
}: {
  review: PortalSignoffReview
  decision: PortalSignoffDecision | null
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <p className="text-caption uppercase tracking-wide text-content-muted">
          {review.clientName}
          {review.productName ? ` · ${review.productName}` : ''}
        </p>
        <h1 className="mt-1 text-h2 text-content">{titleOf(review)}</h1>
        <p className="mt-1 text-sm text-content-muted">
          Requested {new Date(review.requestedAt).toLocaleDateString()}.
        </p>
      </div>
      <StatusChip status={decision ? decision.status : review.status} />
    </div>
  )
}

function ChecklistCard({ review }: { review: PortalSignoffReview }) {
  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <h2 className="text-h3 text-content">What you are reviewing</h2>
      <ul className="mt-3 flex flex-col gap-2">
        {review.checklist.map((item) => (
          <li key={item.id} className="flex gap-2 text-sm text-content">
            {/*
              `isDone` means ANSWERED, not answered True — C-111's distinction,
              restated on the contract's own DTO. So the mark reads "recorded"
              rather than a tick, which would tell the reader something the
              field does not say. The public page draws the identical pair.
            */}
            <span aria-hidden="true" className={item.isDone ? 'text-content' : 'text-content-muted'}>
              {item.isDone ? '●' : '○'}
            </span>
            <span>
              {item.label}
              {item.isMandatory ? null : (
                <span className="ml-1 text-caption text-content-muted">(optional)</span>
              )}
              <span className="sr-only">{item.isDone ? ' — recorded' : ' — not yet recorded'}</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * The decision, both branches.
 *
 * `mode` starts on the Accept form and can switch to the objection form —
 * `PublicSignoffPage.ReviewPanel`'s own shape, and switching back is just
 * clearing local state because nothing has been sent yet.
 */
function DecideCard({
  signoffId,
  onDecided,
}: {
  signoffId: number
  onDecided: (decision: PortalSignoffDecision) => void
}) {
  const [mode, setMode] = React.useState<'accept' | 'object'>('accept')

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      {mode === 'accept' ? (
        <AcceptForm
          signoffId={signoffId}
          onDecided={onDecided}
          onObjectInstead={() => setMode('object')}
        />
      ) : (
        <ObjectForm
          signoffId={signoffId}
          onDecided={onDecided}
          onCancel={() => setMode('accept')}
        />
      )}
    </section>
  )
}

function AcceptForm({
  signoffId,
  onDecided,
  onObjectInstead,
}: {
  signoffId: number
  onDecided: (decision: PortalSignoffDecision) => void
  onObjectInstead: () => void
}) {
  const [name, setName] = React.useState('')
  const [note, setNote] = React.useState('')
  const [busy, setBusy] = React.useState(false)

  const accept = async (event: React.FormEvent) => {
    event.preventDefault()
    setBusy(true)
    try {
      const response = await acceptPortalSignoff(signoffId, {
        acceptedName: name.trim(),
        note: note.trim() || null,
      })
      onDecided(response.data)
    } catch (caught) {
      toast({
        title: 'Could not record your acceptance',
        description: caught instanceof ApiError ? caught.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={accept} aria-labelledby="signoff-accept">
      <h2 id="signoff-accept" className="text-h3 text-content">
        Accept this sign-off
      </h2>

      <label htmlFor="signoff-name" className="mt-4 block text-sm font-medium text-content">
        Your full name
      </label>
      <p className="text-caption text-content-muted">
        Typing your name records this acceptance against it, with the date and time.
      </p>
      <Input
        id="signoff-name"
        className="mt-1"
        value={name}
        onChange={(event) => setName(event.target.value)}
        maxLength={160}
        autoComplete="name"
        required
      />

      <label htmlFor="signoff-note" className="mt-4 block text-sm font-medium text-content">
        Anything to add <span className="font-normal text-content-muted">(optional)</span>
      </label>
      <textarea
        id="signoff-note"
        className="mt-1 w-full rounded-card border border-border bg-surface p-2 text-sm text-content"
        rows={3}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        maxLength={2000}
      />

      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={busy || name.trim().length === 0}>
          {busy ? 'Recording…' : 'Accept'}
        </Button>
        <button
          type="button"
          onClick={onObjectInstead}
          disabled={busy}
          className="text-sm text-content-muted underline underline-offset-2 disabled:opacity-50"
        >
          I need to raise an objection instead
        </button>
      </div>
    </form>
  )
}

/**
 * One mandatory field, because that is the whole route — the contract's own
 * reasoning for why this note cannot be optional where the acceptance note is:
 * "an objection with no reason guarantees a second round trip".
 *
 * Submitting is terminal in the same sense accepting is. There is no un-object;
 * a client who changes their mind is a new sign-off request, which is a staff
 * action with its own record.
 */
function ObjectForm({
  signoffId,
  onDecided,
  onCancel,
}: {
  signoffId: number
  onDecided: (decision: PortalSignoffDecision) => void
  onCancel: () => void
}) {
  const [note, setNote] = React.useState('')
  const [busy, setBusy] = React.useState(false)

  const object = async (event: React.FormEvent) => {
    event.preventDefault()
    setBusy(true)
    try {
      const response = await objectPortalSignoff(signoffId, { note: note.trim() })
      onDecided(response.data)
    } catch (caught) {
      toast({
        title: 'Could not record your objection',
        description: caught instanceof ApiError ? caught.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={object} aria-labelledby="signoff-object">
      <h2 id="signoff-object" className="text-h3 text-content">
        What is wrong?
      </h2>
      <p className="mt-1 text-caption text-content-muted">
        Tell us what needs to change. We will pick this back up rather than treat it as
        accepted.
      </p>
      <textarea
        id="signoff-objection"
        aria-label="What needs to change"
        className="mt-3 w-full rounded-card border border-border bg-surface p-2 text-sm text-content"
        rows={4}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        maxLength={2000}
        required
      />

      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Button type="submit" variant="danger" disabled={busy || note.trim().length === 0}>
          {busy ? 'Recording…' : 'Raise objection'}
        </Button>
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="text-sm text-content-muted underline underline-offset-2 disabled:opacity-50"
        >
          Back
        </button>
      </div>
    </form>
  )
}

/**
 * What the client reads once they have decided.
 *
 * The acceptance message is final by the time this renders and says so whether
 * or not our completion gate could act on it — see the module doc on why
 * `gateFailures` is not drawn.
 */
function DecidedCard({
  decision,
  signoffId,
}: {
  decision: PortalSignoffDecision
  signoffId: number
}) {
  if (decision.status === 'OBJECTED') {
    return (
      <section className="rounded-card border border-border bg-surface p-5" role="status">
        <h2 className="text-h3 text-content">Thank you — your objection is recorded</h2>
        <p className="mt-2 text-sm text-content-muted">
          We have picked this back up and will be in touch. There is nothing further for
          you to do right now.
        </p>
        {decision.objectedAt ? (
          <p className="mt-4 text-caption text-content-muted">
            Recorded {new Date(decision.objectedAt).toLocaleString()}.
          </p>
        ) : null}
      </section>
    )
  }

  return (
    <section className="rounded-card border border-border bg-surface p-5" role="status">
      <h2 className="text-h3 text-content">Thank you — your acceptance is recorded</h2>
      <p className="mt-2 text-sm text-content-muted">
        {decision.clientWentLive
          ? 'That was the last one — your onboarding is complete and you are live.'
          : 'We are finishing our side. There is nothing further for you to do right now.'}
      </p>
      {decision.signedAt ? (
        <p className="mt-4 text-caption text-content-muted">
          Accepted{decision.signedName ? ` by ${decision.signedName}` : ''} on{' '}
          {new Date(decision.signedAt).toLocaleString()}.
        </p>
      ) : null}

      {decision.csatOffered ? <CsatBlock signoffId={signoffId} /> : null}
    </section>
  )
}

/**
 * B-119's one question, inline beneath an acceptance that is already final.
 *
 * Additive, never gating — the design's own line is that "a client who closes
 * the tab has still gone live", and Skip is right beside Submit because closing
 * the tab is exactly as final as clicking it.
 */
function CsatBlock({ signoffId }: { signoffId: number }) {
  const [score, setScore] = React.useState<number | null>(null)
  const [comment, setComment] = React.useState('')
  const [state, setState] = React.useState<'asking' | 'done'>('asking')
  const [busy, setBusy] = React.useState(false)

  if (state === 'done') {
    return (
      <p className="mt-6 border-t border-border pt-4 text-sm text-content-muted">
        Thank you for the feedback.
      </p>
    )
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (score == null) return
    setBusy(true)
    try {
      await submitPortalCsat(signoffId, { score, comment: comment.trim() || null })
      setState('done')
    } catch (caught) {
      toast({
        title: 'Could not record your answer',
        description: caught instanceof ApiError ? caught.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mt-6 border-t border-border pt-4" aria-labelledby="csat">
      <h3 id="csat" className="text-sm font-medium text-content">
        How was your onboarding?
      </h3>
      <div className="mt-3 flex flex-wrap gap-2" role="radiogroup" aria-labelledby="csat">
        {[1, 2, 3, 4, 5].map((value) => (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={score === value}
            aria-label={`${value} out of 5`}
            onClick={() => setScore(value)}
            className={
              score === value
                ? 'h-10 w-10 rounded-card border border-primary bg-primary text-sm text-white'
                : 'h-10 w-10 rounded-card border border-border bg-surface text-sm text-content'
            }
          >
            {value}
          </button>
        ))}
      </div>

      <label htmlFor="csat-comment" className="mt-4 block text-sm font-medium text-content">
        Anything to add <span className="font-normal text-content-muted">(optional)</span>
      </label>
      <textarea
        id="csat-comment"
        className="mt-1 w-full rounded-card border border-border bg-surface p-2 text-sm text-content"
        rows={2}
        value={comment}
        onChange={(event) => setComment(event.target.value)}
        maxLength={2000}
      />

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button type="submit" size="sm" disabled={busy || score == null}>
          {busy ? 'Sending…' : 'Submit'}
        </Button>
        <button
          type="button"
          onClick={() => setState('done')}
          disabled={busy}
          className="text-sm text-content-muted underline underline-offset-2 disabled:opacity-50"
        >
          Skip
        </button>
      </div>
    </form>
  )
}

/**
 * A sign-off this client owns that is no longer open to a decision.
 *
 * Reached two ways: the row was already decided before the page loaded, or
 * staff withdrew it. Both read here rather than 404, which is why
 * `getPortalSignoff` serves every status — a client looking at their own row is
 * owed an explanation, not a dead end.
 */
function ClosedCard({ review }: { review: PortalSignoffReview }) {
  const body =
    review.status === 'SIGNED'
      ? 'This has already been accepted. There is nothing further for you to do.'
      : review.status === 'OBJECTED'
        ? 'An objection has been raised on this. We have picked it back up and will be in touch.'
        : review.status === 'EXPIRED'
          ? 'This request is no longer open. Ask your point of contact to raise a fresh one.'
          : 'This request was withdrawn. Ask your point of contact if you were expecting to sign something.'

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <p className="text-sm text-content-muted">{body}</p>
    </section>
  )
}

function StatusChip({ status }: { status: PortalSignoffReview['status'] }) {
  switch (status) {
    case 'SIGNED':
      return <Chip variant="success">Accepted</Chip>
    case 'OBJECTED':
      return <Chip variant="danger">Objected</Chip>
    case 'EXPIRED':
      return <Chip variant="neutral">Expired</Chip>
    case 'CANCELLED':
      return <Chip variant="neutral">Withdrawn</Chip>
    default:
      return <Chip variant="warning">Awaiting your decision</Chip>
  }
}

export default PortalSignoffDetailPage
