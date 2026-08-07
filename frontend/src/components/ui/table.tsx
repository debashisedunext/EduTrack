import * as React from 'react'
import { cn } from '@/lib/utils'

/** Tracks scroll position via a CSS var so the sticky header can shadow only once scrolled — §12.3. */
export const TableContainer = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, onScroll, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('relative overflow-auto rounded-card border border-border', className)}
      onScroll={(e) => {
        e.currentTarget.style.setProperty('--scrolled', e.currentTarget.scrollTop > 0 ? '1' : '0')
        onScroll?.(e)
      }}
      {...props}
    />
  ),
)
TableContainer.displayName = 'TableContainer'

export const Table = React.forwardRef<HTMLTableElement, React.TableHTMLAttributes<HTMLTableElement>>(
  ({ className, ...props }, ref) => (
    <table ref={ref} className={cn('w-full caption-bottom text-sm', className)} {...props} />
  ),
)
Table.displayName = 'Table'

export const TableHeader = React.forwardRef<HTMLTableSectionElement, React.HTMLAttributes<HTMLTableSectionElement>>(
  ({ className, style, ...props }, ref) => (
    <thead
      ref={ref}
      className={cn('sticky top-0 z-10 bg-subtle', className)}
      style={{ boxShadow: '0 1px 2px rgba(16,24,40,calc(.08 * var(--scrolled, 0)))', ...style }}
      {...props}
    />
  ),
)
TableHeader.displayName = 'TableHeader'

export const TableBody = React.forwardRef<HTMLTableSectionElement, React.HTMLAttributes<HTMLTableSectionElement>>(
  ({ className, ...props }, ref) => <tbody ref={ref} className={cn('divide-y divide-border', className)} {...props} />,
)
TableBody.displayName = 'TableBody'

/** `group` so row-scoped hover actions can use `opacity-0 group-hover:opacity-100` — §12.3. */
export const TableRow = React.forwardRef<HTMLTableRowElement, React.HTMLAttributes<HTMLTableRowElement>>(
  ({ className, ...props }, ref) => (
    <tr ref={ref} className={cn('group/row transition-colors hover:bg-subtle', className)} {...props} />
  ),
)
TableRow.displayName = 'TableRow'

export const TableHead = React.forwardRef<HTMLTableCellElement, React.ThHTMLAttributes<HTMLTableCellElement>>(
  ({ className, ...props }, ref) => (
    <th
      ref={ref}
      className={cn('h-10 px-4 text-left align-middle text-xs font-semibold text-content-muted', className)}
      {...props}
    />
  ),
)
TableHead.displayName = 'TableHead'

export const TableCell = React.forwardRef<HTMLTableCellElement, React.TdHTMLAttributes<HTMLTableCellElement>>(
  ({ className, ...props }, ref) => (
    <td ref={ref} className={cn('px-4 py-3 align-middle text-content', className)} {...props} />
  ),
)
TableCell.displayName = 'TableCell'
