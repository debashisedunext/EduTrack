import type { ReactNode } from 'react'
import { EmptyState } from '@/components/ui/empty-state'

/** Stand-in for a screen not yet built by its owning task. */
export function ScreenPlaceholder({
  title,
  description = 'This screen is built in a later task.',
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="p-8">
      <EmptyState title={title} description={description} action={action} />
    </div>
  )
}
