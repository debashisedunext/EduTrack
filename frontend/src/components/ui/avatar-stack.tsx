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

function initials(name: string) {
  return name
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
