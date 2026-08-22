import * as AvatarPrimitive from '@radix-ui/react-avatar'
import { cn } from '@/lib/utils'

export interface AvatarStackPerson {
  id: string
  name: string
  imageUrl?: string
}

export interface AvatarStackProps {
  people: AvatarStackPerson[]
  /** How many avatars to show before collapsing the rest into a "+N" chip. */
  max?: number
  size?: 'sm' | 'md'
  className?: string
}

/**
 * ⚠ Tolerates a missing name, and that is not defensive clutter.
 *
 * `AvatarStackPerson.name` is typed `string`, so this read looks safe — but the
 * type is only a promise about the *caller's* data, and a caller filling it
 * from an API response inherits whatever that response actually contained. A
 * ticket whose `assignee` arrived as a bare numeric id rather than the
 * contract's `UserRef` reached here as `{name: undefined}`, `.split()` threw,
 * and because nothing in this app is wrapped in an error boundary the whole
 * page unmounted to a white screen — a blank browser window as the symptom of
 * one absent field.
 *
 * A shared primitive that every stream renders should degrade to an empty
 * badge rather than take the route down with it. The caller is still wrong and
 * still gets fixed; this decides how loudly that wrongness fails.
 */
function initials(name: string | null | undefined) {
  return (name ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')
}

const sizeClasses = { sm: 'h-6 w-6 text-[10px]', md: 'h-8 w-8 text-xs' }

// Avatar stacks for watchers — blueprint §12.3.
export function AvatarStack({ people, max = 4, size = 'sm', className }: AvatarStackProps) {
  const visible = people.slice(0, max)
  const overflow = people.length - visible.length

  return (
    <div className={cn('flex items-center -space-x-2', className)} role="group" aria-label="Watchers">
      {visible.map((person) => (
        <AvatarPrimitive.Root
          key={person.id}
          title={person.name}
          className={cn(
            'inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full border-2 border-surface bg-primary-soft font-semibold text-primary',
            sizeClasses[size],
          )}
        >
          {person.imageUrl && <AvatarPrimitive.Image src={person.imageUrl} alt={person.name} className="h-full w-full object-cover" />}
          <AvatarPrimitive.Fallback delayMs={person.imageUrl ? 300 : 0}>{initials(person.name)}</AvatarPrimitive.Fallback>
        </AvatarPrimitive.Root>
      ))}
      {overflow > 0 && (
        <span
          title={people.slice(max).map((p) => p.name).join(', ')}
          className={cn(
            'inline-flex shrink-0 items-center justify-center rounded-full border-2 border-surface bg-subtle font-semibold text-content-muted',
            sizeClasses[size],
          )}
        >
          +{overflow}
        </span>
      )}
    </div>
  )
}
