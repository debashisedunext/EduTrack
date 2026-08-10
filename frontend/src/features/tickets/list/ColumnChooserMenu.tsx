import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Settings2 } from 'lucide-react'
import { TOGGLEABLE_COLUMNS, type ColumnKey } from './columns'

export interface ColumnChooserMenuProps {
  visibleColumns: ColumnKey[]
  onToggle: (key: ColumnKey) => void
}

/** The wireframe's "⚙ Columns" — ID and Description are load-bearing and never appear here. */
export function ColumnChooserMenu({ visibleColumns, onToggle }: ColumnChooserMenuProps) {
  return (
    <PopoverPrimitive.Root>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          className="flex h-9 items-center gap-1.5 rounded-control border border-border bg-surface px-3 text-sm text-content hover:bg-subtle focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
        >
          <Settings2 className="h-4 w-4" />
          Columns
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="end"
          sideOffset={4}
          className="z-50 w-56 rounded-control border border-border bg-surface p-2 shadow-modal"
        >
          <p className="px-2 pb-1.5 pt-1 text-caption font-medium uppercase tracking-wide text-content-muted">
            Visible columns
          </p>
          <ul className="flex flex-col">
            {TOGGLEABLE_COLUMNS.map((column) => (
              <li key={column.key}>
                <label className="flex cursor-pointer items-center gap-2 rounded-control px-2 py-1.5 text-sm text-content hover:bg-subtle">
                  <input
                    type="checkbox"
                    checked={visibleColumns.includes(column.key)}
                    onChange={() => onToggle(column.key)}
                    className="h-4 w-4 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
                  />
                  {column.header}
                </label>
              </li>
            ))}
          </ul>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}
