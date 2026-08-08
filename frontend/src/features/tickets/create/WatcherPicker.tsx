import { X } from 'lucide-react'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Chip } from '@/components/ui/chip'
import type { User } from '@/api/generated/model/user'
import type { FieldAria } from './FormField'

export interface WatcherPickerProps extends FieldAria {
  candidates: readonly User[]
  value: readonly number[]
  onChange: (userIds: number[]) => void
  disabled?: boolean
  disabledHint?: string
}

/**
 * Watchers — multi-select, blueprint §7.5. They get the notifications too.
 *
 * Built from the shared single-select dropdown plus removable chips rather than
 * a new shared multi-select: the picker is the only place in the product that
 * needs one so far, and a component in `components/ui/` is a contract the other
 * three streams then depend on. Promote it there when a second caller appears.
 */
export function WatcherPicker({
  candidates,
  value,
  onChange,
  disabled,
  disabledHint,
  ...aria
}: WatcherPickerProps) {
  const selected = value
    .map((id) => candidates.find((u) => u.id === id))
    .filter((u): u is User => u != null)
  const available = candidates.filter((u) => !value.includes(u.id))

  return (
    <div className="flex flex-col gap-2">
      <SearchableDropdown<User>
        {...aria}
        options={available}
        value={null}
        onChange={(user) => onChange([...value, user.id])}
        getKey={(user) => String(user.id)}
        getLabel={(user) => user.displayName}
        getSearchable={(user) => [user.email ?? '', user.role ?? '']}
        placeholder={disabled ? (disabledHint ?? 'Select a project first') : 'Add a watcher…'}
        emptyText="Everyone on the project is already watching"
        disabled={disabled}
      />
      {selected.length > 0 && (
        <ul className="flex flex-wrap gap-1.5">
          {selected.map((user) => (
            <li key={user.id}>
              <Chip variant="info" className="pr-1">
                {user.displayName}
                <button
                  type="button"
                  onClick={() => onChange(value.filter((id) => id !== user.id))}
                  aria-label={`Remove ${user.displayName} from watchers`}
                  className="rounded-chip p-0.5 hover:bg-black/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <X className="h-3 w-3" />
                </button>
              </Chip>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
