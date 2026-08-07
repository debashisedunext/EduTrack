import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

// Skeleton loaders, never spinners, on tables and charts — blueprint §12.2.
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('animate-pulse rounded-control bg-subtle motion-reduce:animate-none', className)}
      {...props}
    />
  )
}
