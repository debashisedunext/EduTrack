import { usePreviewPlannedCloseDate } from '@/api/generated/tickets/tickets'
import type { Level } from '@/api/generated/model/level'
import type { PlannedCloseDatePreview } from '@/api/generated/model/plannedCloseDatePreview'

/**
 * C-012 · the inline planned-close-date preview.
 *
 * Wraps the generated query with the one rule that matters: **it does not fire
 * until the answer would be meaningful.** `projectId` and `level` are both
 * required by the contract, and firing without them earns a 400 that renders as
 * an error under a field the user has not reached yet.
 *
 * Deliberately *not* debounced. Every input is a picker — project, task type,
 * level, assignee — so changes arrive one click at a time, not one keystroke at
 * a time; there is nothing to coalesce. TanStack Query already dedupes and
 * caches per parameter set, so flicking between two levels re-reads the second
 * pick from cache rather than issuing a second request.
 */
export interface PlannedCloseDateInput {
  projectId: number | null
  taskTypeId: number | null
  level: Level | null
  assigneeId: number | null
  /**
   * When the clock starts. Omitted means now, which is what the create form
   * wants — passing `new Date().toISOString()` from a render would mint a new
   * query key on every render and refetch forever.
   */
  from?: string
}

export interface PlannedCloseDateState {
  preview: PlannedCloseDatePreview | null
  isPending: boolean
  isError: boolean
  /** True once the inputs are complete enough for the server to answer. */
  isReady: boolean
}

export function usePlannedCloseDate(input: PlannedCloseDateInput): PlannedCloseDateState {
  const { projectId, taskTypeId, level, assigneeId, from } = input
  const isReady = projectId != null && level != null

  const query = usePreviewPlannedCloseDate(
    {
      // Non-null after `isReady`; the query is disabled until then, and the
      // params object still has to type-check while it is.
      projectId: projectId ?? 0,
      level: level ?? ('MEDIUM' as Level),
      ...(taskTypeId != null ? { taskTypeId } : {}),
      ...(assigneeId != null ? { assigneeId } : {}),
      ...(from ? { from } : {}),
    },
    {
      query: {
        enabled: isReady,
        // The answer depends on the SLA matrix and the working calendar, both of
        // which change on the order of weeks. Re-reading it because a window
        // regained focus would move a date the user is reading.
        staleTime: 5 * 60_000,
        refetchOnWindowFocus: false,
        // A 400 or 404 here is a statement about the inputs, not a blip. The
        // preview is advisory, so a failure degrades to "not shown" rather than
        // blocking the save — retrying it three times just delays that.
        retry: false,
      },
    },
  )

  return {
    preview: query.data?.data ?? null,
    isPending: isReady && query.isPending,
    isError: query.isError,
    isReady,
  }
}
