import { Chip, type ChipProps } from '@/components/ui/chip'
import type { Level } from '@/api/generated/model/level'
import { cn } from '@/lib/utils'
import type { FieldAria } from './FormField'

const LEVEL_VARIANT: Record<Level, ChipProps['variant']> = {
  LOW: 'low',
  MEDIUM: 'medium',
  HIGH: 'high',
  CRITICAL: 'critical',
}

export interface LevelPickerProps extends FieldAria {
  /** Levels in priority-master order, so the master decides what exists and in what sequence. */
  levels: readonly Level[]
  value: Level | null
  onChange: (level: Level) => void
  disabled?: boolean
}

/**
 * Level (Priority) — blueprint §7.5.
 *
 * The blueprint draws this as a colour-chip dropdown. With four options a
 * radio group shows all four chips at once, which is the information the
 * colour is carrying in the first place, and it stays operable from the
 * keyboard without a popup. The chips use the frozen `level-*` tokens rather
 * than the hex the priority master returns — §12.1 owns those colours, and the
 * token variants are the pair that passes AA at chip text size.
 *
 * Which levels exist, and their order, still come from the priority master.
 */
export function LevelPicker({ levels, value, onChange, disabled, ...aria }: LevelPickerProps) {
  return (
    <div
      role="radiogroup"
      {...aria}
      className="flex h-10 flex-wrap items-center gap-2"
    >
      {levels.map((level) => {
        const selected = value === level
        return (
          <button
            key={level}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={disabled}
            onClick={() => onChange(level)}
            className={cn(
              'rounded-chip p-0.5 transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1',
              'disabled:cursor-not-allowed disabled:opacity-50',
              selected ? 'ring-2 ring-primary ring-offset-1' : 'opacity-60 hover:opacity-100',
            )}
          >
            <Chip variant={LEVEL_VARIANT[level]}>{level}</Chip>
          </button>
        )
      })}
    </div>
  )
}
