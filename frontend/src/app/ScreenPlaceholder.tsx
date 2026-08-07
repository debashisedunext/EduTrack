import { EmptyState } from '@/components/ui/empty-state'

/** Stand-in for a screen not yet built by its owning task. */
export function ScreenPlaceholder({ title }: { title: string }) {
  return (
    <div className="p-8">
      <EmptyState title={title} description="This screen is built in a later task." />
    </div>
  )
}
