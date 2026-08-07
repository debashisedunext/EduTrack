import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

// Soft-tinted background + solid text, never a heavy solid block — blueprint §12.1.
const chipVariants = cva(
  'inline-flex items-center gap-1 rounded-chip px-2.5 py-0.5 text-xs font-medium',
  {
    variants: {
      variant: {
        neutral: 'bg-subtle text-content-muted',
        low: 'bg-level-low-soft text-level-low-text',
        medium: 'bg-level-medium-soft text-level-medium-text',
        high: 'bg-level-high-soft text-level-high-text',
        critical: 'bg-level-critical-soft text-level-critical-text',
        success: 'bg-level-low-soft text-success-text',
        warning: 'bg-level-high-soft text-warning-text',
        danger: 'bg-level-critical-soft text-danger-text',
        info: 'bg-level-medium-soft text-info-text',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
)

export interface ChipProps extends React.HTMLAttributes<HTMLSpanElement>, VariantProps<typeof chipVariants> {}

export function Chip({ className, variant, ...props }: ChipProps) {
  return <span className={cn(chipVariants({ variant }), className)} {...props} />
}
