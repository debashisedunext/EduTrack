import { AlignJustify, Rows3 } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { Density } from './useListPreferences'

export interface DensityToggleProps {
  density: Density
  onChange: (density: Density) => void
}

const OPTIONS: { value: Density; label: string; icon: typeof Rows3 }[] = [
  { value: 'comfortable', label: 'Comfortable', icon: Rows3 },
  { value: 'compact', label: 'Compact', icon: AlignJustify },
]

export function DensityToggle({ density, onChange }: DensityToggleProps) {
  return (
    <div role="radiogroup" aria-label="Row density" className="flex items-center rounded-control border border-border bg-surface p-0.5">
      {OPTIONS.map(({ value, label, icon: Icon }) => {
        const selected = density === value
        return (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={selected}
            title={label}
            onClick={() => onChange(value)}
            className={cn(
              'flex h-8 w-8 items-center justify-center rounded-control transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1',
              selected ? 'bg-primary-soft text-primary' : 'text-content-muted hover:bg-subtle',
            )}
          >
            <Icon className="h-4 w-4" />
            <span className="sr-only">{label}</span>
          </button>
        )
      })}
    </div>
  )
}
