import type { ReactNode } from 'react'
import { Inbox } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface EmptyStateProps {
  icon?: ReactNode
  title: string
  description?: string
  action?: ReactNode
  className?: string
}

// Friendly line illustration + one-line copy + primary action — blueprint §12.2.
export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div className={cn('flex flex-col items-center justify-center gap-3 px-6 py-16 text-center', className)}>
      <div className="flex h-12 w-12 items-center justify-center rounded-full bg-subtle text-content-muted">
        {icon ?? <Inbox className="h-6 w-6" strokeWidth={1.5} />}
      </div>
      <div className="flex flex-col gap-1">
        <p className="text-sm font-medium text-content">{title}</p>
        {description && <p className="text-sm text-content-muted">{description}</p>}
      </div>
      {action}
    </div>
  )
}
