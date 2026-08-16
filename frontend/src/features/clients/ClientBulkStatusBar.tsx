import { Button } from '@/components/ui/button'

/**
 * B-025 · S-32's bulk activate/deactivate controls.
 *
 * Kept out of `ClientListPage` for the reason `resources/BulkStatusBar.tsx` is:
 * the grid is long enough already.
 *
 * **B-029 · the confirmation moved out to `DeactivationWarningDialog`.** It was
 * here while the bulk bar was the only way to deactivate a client; S-33's
 * Status select is a second, and the two must not be able to describe the same
 * consequence differently. See that file for the argument.
 */
export function ClientBulkStatusBar({
  selectedCount,
  isPending,
  onApply,
  onClear,
}: {
  selectedCount: number
  isPending: boolean
  onApply: (isActive: boolean) => void
  onClear: () => void
}) {
  if (selectedCount === 0) return null

  return (
    <div
      role="region"
      aria-label="Bulk actions"
      className="flex flex-wrap items-center gap-3 rounded-control bg-subtle px-3 py-2"
    >
      <p className="text-sm text-content" role="status">
        {selectedCount} selected
      </p>
      <div className="ml-auto flex items-center gap-2">
        <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(true)}>
          Activate
        </Button>
        <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(false)}>
          Deactivate
        </Button>
        <Button size="sm" variant="ghost" disabled={isPending} onClick={onClear}>
          Clear
        </Button>
      </div>
    </div>
  )
}
