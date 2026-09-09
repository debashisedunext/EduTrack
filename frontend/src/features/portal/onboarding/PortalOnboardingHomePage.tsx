import * as React from 'react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'

import {
  getGetPortalOnboardingHomeQueryKey,
  raisePortalEscalation,
  useGetPortalOnboardingHome,
} from '@/api/generated/portal/portal'
import type { PortalJourneyStrip } from '@/api/generated/model/portalJourneyStrip'
import type { PortalStepDot } from '@/api/generated/model/portalStepDot'
import type { ObClientPrereqTask } from '@/api/generated/model/obClientPrereqTask'
import type { ObClientPrereqs } from '@/api/generated/model/obClientPrereqs'
import { ApiError } from '@/api/http'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { ReasonDialog } from '@/components/ui/reason-dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'
import { StepDotStrip } from '@/features/onboarding/journey/clientDetail/StepDotStrip'

/**
 * CP-03 · onboarding home — interactive prerequisites above read-only
 * journey accordions (Onboarding-Module-Plan.md §9).
 *
 * The journey strip reuses `StepDotStrip` (C-110) as-is: it already draws
 * from `ObStepDot`, deliberately thinner than the staff ribbon's step (no
 * owner, no TAT, no note) for exactly the reason this screen needs — "the
 * client portal renders this same strip" is that component's own docstring.
 * `PortalStepDot` is a structural superset of `ObStepDot` (it adds
 * `openEscalation`), so no adapter is needed to pass one where the other is
 * expected.
 *
 * <h2>C-126 · the Escalate control</h2>
 *
 * A running (`IN_PROGRESS`) step with no open escalation gets the control;
 * one that already carries `openEscalation` gets a chip instead — the
 * server enforces "one open escalation per service" regardless, but showing
 * the state rather than a second live button is what keeps a client from
 * reading a disabled-looking enabled button as broken. The mandatory comment
 * is `ReasonDialog` (`components/ui`), the same dialog OB-05's own resolve
 * flow uses.
 */
export function PortalOnboardingHomePage() {
  const { data, isPending, isError } = useGetPortalOnboardingHome()

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-24 w-full rounded-card" />
        <Skeleton className="h-40 w-full rounded-card" />
      </div>
    )
  }

  if (isError || !data) {
    return (
      <EmptyState
        title="Could not load your onboarding"
        description="Something went wrong. Try reloading the page."
      />
    )
  }

  const home = data.data

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-h2 text-content">
            {home.clientName ? `${home.clientName}'s onboarding` : 'Your onboarding'}
          </h1>
          <p className="mt-1 text-sm text-content-muted">
            Complete the tasks below to get your journeys started.
          </p>
        </div>
        {/* CP-05 — C-122. Onboarding home is where a client already lands
            inside this module, so it is the entry point into the sign-off
            list rather than a nav bar added to the shared `PortalShell`. */}
        <Link
          to="/portal/onboarding/signoffs"
          className="text-sm text-primary hover:underline"
        >
          View sign-offs →
        </Link>
      </div>

      <PrereqsCard prereqs={home.prereqs} />

      <div className="flex flex-col gap-4">
        <h2 className="text-h3 text-content">Your journeys</h2>
        {home.journeys.length === 0 ? (
          <EmptyState
            title="No journeys yet"
            description="Journeys start once your prerequisites are cleared."
          />
        ) : (
          home.journeys.map((journey) => <JourneyAccordion key={journey.id} journey={journey} />)
        )}
      </div>
    </div>
  )
}

function PrereqsCard({ prereqs }: { prereqs: ObClientPrereqs }) {
  const gateOpen = prereqs.gateStatus === 'OPEN'

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-h3 text-content">Prerequisites</h2>
        <Chip variant={gateOpen ? 'success' : 'warning'}>
          {gateOpen ? 'Journeys unlocked' : 'Prerequisites pending'}
        </Chip>
      </div>
      <p className="mt-1 text-sm text-content-muted">
        {prereqs.mandatoryVerified} of {prereqs.mandatoryTotal} required tasks verified
        {(prereqs.optionalOutstanding ?? 0) > 0
          ? ` · ${prereqs.optionalOutstanding} optional task${prereqs.optionalOutstanding === 1 ? '' : 's'} outstanding`
          : ''}
      </p>

      {prereqs.tasks.length === 0 ? (
        <p className="mt-4 text-sm text-content-muted">Nothing to do here yet.</p>
      ) : (
        <ul className="mt-4 flex flex-col divide-y divide-border">
          {prereqs.tasks.map((task) => (
            <PrereqRow key={task.id} task={task} />
          ))}
        </ul>
      )}
    </section>
  )
}

function PrereqRow({ task }: { task: ObClientPrereqTask }) {
  return (
    <li>
      <Link
        to={`/portal/onboarding/prereq-tasks/${task.id}`}
        className="flex items-center justify-between gap-3 py-3 hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary rounded-control px-2 -mx-2"
      >
        <div className="flex flex-col">
          <span className="text-sm font-medium text-content">{task.title}</span>
          <span className="text-caption text-content-muted">
            {task.isMandatory ? 'Required' : 'Optional'} · Due{' '}
            {new Date(task.dueAt).toLocaleDateString()}
          </span>
        </div>
        <StatusChip status={task.status} isOverdue={task.isOverdue} />
      </Link>
    </li>
  )
}

function StatusChip({ status, isOverdue }: { status: string; isOverdue?: boolean }) {
  if (status === 'VERIFIED') return <Chip variant="success">Verified</Chip>
  if (status === 'SKIPPED') return <Chip variant="neutral">Skipped</Chip>
  if (status === 'SUBMITTED') return <Chip variant="info">Submitted</Chip>
  if (isOverdue) return <Chip variant="danger">Overdue</Chip>
  return <Chip variant="warning">Pending</Chip>
}

function JourneyAccordion({ journey }: { journey: PortalJourneyStrip }) {
  const queryClient = useQueryClient()
  const [escalating, setEscalating] = React.useState<PortalStepDot | null>(null)
  const [isPending, setIsPending] = React.useState(false)

  const onConfirmEscalate = async (comment: string) => {
    if (!escalating) return
    setIsPending(true)
    try {
      const response = await raisePortalEscalation(escalating.id, { comment })
      toast({
        title: response.data.isNew ? 'Escalated to staff' : 'Already escalated',
        description: response.data.isNew
          ? 'The onboarding manager and the service owner have been notified.'
          : 'This service already had an open escalation — staff have been notified once.',
      })
      await queryClient.invalidateQueries({ queryKey: getGetPortalOnboardingHomeQueryKey() })
      setEscalating(null)
    } catch (error) {
      toast({
        title: 'Could not escalate',
        description: error instanceof ApiError ? error.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setIsPending(false)
    }
  }

  return (
    <details className="group rounded-card border border-border bg-surface open:shadow-rest" open>
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 marker:content-none">
        <div className="flex items-center gap-3">
          <span className="text-sm font-semibold text-content">{journey.product.name}</span>
          <Chip variant={journey.gateStatus === 'OPEN' ? 'neutral' : 'warning'}>
            {journey.gateStatus === 'OPEN' ? `${journey.percentComplete}% complete` : 'Locked'}
          </Chip>
          {journey.heldByJourneyId != null ? (
            <Chip variant="neutral">Waiting on another journey</Chip>
          ) : null}
        </div>
        <StepDotStrip steps={journey.steps} />
      </summary>

      <div className="border-t border-border px-5 py-4">
        <ul className="flex flex-col gap-2">
          {journey.steps.map((step) => (
            <li key={step.id} className="flex items-center justify-between gap-3 text-sm">
              <span className="text-content">
                {step.sequence}. {step.name}
              </span>
              <div className="flex items-center gap-2">
                <Chip variant={rowVariant(step.status)}>{stepStatusLabel(step.status)}</Chip>
                {step.openEscalation ? (
                  <Chip variant="danger" title={step.openEscalation.comment}>
                    Escalated · awaiting staff
                  </Chip>
                ) : step.status === 'IN_PROGRESS' ? (
                  <button
                    type="button"
                    onClick={() => setEscalating(step)}
                    className="rounded-control border border-border px-2 py-1 text-caption text-content hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  >
                    Escalate
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      </div>

      <ReasonDialog
        open={escalating != null}
        onOpenChange={(open) => {
          if (!open) setEscalating(null)
        }}
        title={`Escalate ${escalating?.name ?? 'this service'}`}
        description="This notifies the onboarding manager and the service owner immediately. Staff will see and resolve it — you'll be told when they do."
        fieldLabel="What's the problem?"
        confirmLabel="Escalate"
        confirmVariant="danger"
        isPending={isPending}
        onConfirm={onConfirmEscalate}
      />
    </details>
  )
}

function rowVariant(status: string): 'neutral' | 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'DONE':
      return 'success'
    case 'IN_PROGRESS':
      return 'info'
    case 'BLOCKED':
      return 'danger'
    case 'WAITING_ON_CLIENT':
      return 'warning'
    case 'SKIPPED':
      return 'neutral'
    default:
      return 'neutral'
  }
}

function stepStatusLabel(status: string): string {
  switch (status) {
    case 'DONE':
      return 'Done'
    case 'IN_PROGRESS':
      return 'In progress'
    case 'BLOCKED':
      return 'Blocked'
    case 'WAITING_ON_CLIENT':
      return 'Waiting on you'
    case 'SKIPPED':
      return 'Skipped'
    default:
      return 'Pending'
  }
}
