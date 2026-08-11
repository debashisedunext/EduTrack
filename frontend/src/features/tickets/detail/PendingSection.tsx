import type { ReactNode } from 'react'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * A region of the detail page whose content belongs to a later task.
 *
 * Named, not blank. C-019 builds the shell; the ribbon, the journey roll-up,
 * the comment stream, the attachment gallery and the effort list each have
 * their own task and their own correctness rules, and a shell that quietly
 * rendered nothing where they go reads as a broken page rather than as an
 * unbuilt one. Naming the owning task also means the next person to open this
 * file knows which backlog line to tick when they fill it in.
 */
export function PendingSection({ title, owner, note }: { title: string; owner: string; note?: ReactNode }) {
  return (
    <EmptyState
      title={title}
      description={note ? undefined : `Arrives with ${owner}.`}
      action={
        note ? (
          <p className="max-w-prose text-sm text-content-muted">
            {note} <span className="whitespace-nowrap">({owner})</span>
          </p>
        ) : undefined
      }
      className="py-10"
    />
  )
}
