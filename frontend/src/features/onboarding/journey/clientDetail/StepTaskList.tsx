import type { ObJourneyStepDoc, ObJourneyStepItem } from '@/api/generated/model'
import { useUpdateObJourneyStepItem } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useQueryClient } from '@tanstack/react-query'
import { getGetObJourneyStepQueryKey } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { cn } from '@/lib/utils'

/**
 * C-111 · the step's Task List and its required documents — §5.8's gate, drawn
 * where the person who has to satisfy it is already looking.
 *
 * ## The gate is shown, not just enforced
 *
 * C-106 put the completion gate on the server, which is where it belongs: a
 * mandatory item unticked refuses `complete` with `ob-step-items-outstanding`,
 * whatever the client does. What that leaves the screen owing is the *reason*,
 * in advance. An owner who presses Complete and reads a 422 has been sent
 * looking for something this list could have shown them without the round
 * trip, and `StepActionBar` disables Complete against the same computation.
 *
 * ## Ticking is the owner's, and only the owner's
 *
 * `PATCH /onboarding/journey-step-items/{itemId}` is guarded the same way the
 * transitions are. `canEdit` here is the same `mayActOnStep` the action bar
 * uses, passed down rather than recomputed, so a checkbox can never be
 * interactive on a step whose Complete button is not.
 *
 * A read-only view renders real checkboxes with `disabled`, not a bullet list:
 * the ticks are the state somebody else's colleague is reporting, and a
 * flattened list would lose which items are mandatory and which are done.
 *
 * ## Documents are read-only here on purpose
 *
 * `isSatisfied` is derived from whether a clean attachment exists, so the way
 * to satisfy one is to attach a file — which is the attachment surface, not
 * this list. Rendering an upload control here would be a second, competing
 * place to do it; the list states the requirement and whether it is met.
 */
export interface StepTaskListProps {
  stepId: number
  items: readonly ObJourneyStepItem[]
  docs: readonly ObJourneyStepDoc[]
  canEdit: boolean
}

export function StepTaskList({ stepId, items, docs, canEdit }: StepTaskListProps) {
  const queryClient = useQueryClient()
  const update = useUpdateObJourneyStepItem({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getGetObJourneyStepQueryKey(stepId) })
      },
    },
  })

  if (items.length === 0 && docs.length === 0) return null

  return (
    <div className="mt-4 flex flex-col gap-4">
      {items.length > 0 && (
        <section>
          <h5 className="m-0 text-caption font-semibold uppercase tracking-wide text-content-muted">
            Task list
          </h5>
          <ul role="list" className="mt-2 flex flex-col gap-1">
            {items.map((item) => {
              const busy = update.isPending && update.variables?.itemId === item.id
              return (
                <li key={item.id} className="flex items-start gap-2">
                  <input
                    type="checkbox"
                    id={`ob-step-item-${item.id}`}
                    checked={item.isDone}
                    disabled={!canEdit || busy}
                    onChange={(event) =>
                      update.mutate({ itemId: item.id, data: { isDone: event.target.checked } })
                    }
                    className="mt-0.5 h-4 w-4 shrink-0 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  />
                  <label
                    htmlFor={`ob-step-item-${item.id}`}
                    className={cn(
                      'text-sm',
                      item.isDone ? 'text-content-muted line-through' : 'text-content',
                    )}
                  >
                    {item.label}
                    {/*
                      Mandatory is marked, optional is not. The asterisk is the
                      difference between an item that holds the gate and one
                      that does not, and it is the only thing on this row that
                      changes what Complete will do.
                    */}
                    {item.isMandatory && (
                      <span className="ml-1 text-danger-text" title="Mandatory — blocks completion">
                        *
                      </span>
                    )}
                  </label>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      {docs.length > 0 && (
        <section>
          <h5 className="m-0 text-caption font-semibold uppercase tracking-wide text-content-muted">
            Documents
          </h5>
          <ul role="list" className="mt-2 flex flex-col gap-1">
            {docs.map((doc) => (
              <li key={doc.id} className="flex items-center gap-2 text-sm">
                <span
                  aria-hidden="true"
                  className={cn(
                    'inline-block h-2 w-2 shrink-0 rounded-full',
                    doc.isSatisfied ? 'bg-success' : doc.isRequired ? 'bg-danger' : 'bg-subtle',
                  )}
                />
                <span className={doc.isSatisfied ? 'text-content-muted' : 'text-content'}>
                  {doc.label}
                </span>
                <span className="text-caption text-content-muted">
                  {doc.isSatisfied ? 'attached' : doc.isRequired ? 'required — missing' : 'optional'}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
