import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Settings2 } from 'lucide-react'

import { useDashboardWidgetPreferencesStore } from './dashboardWidgetPreferencesStore'
import { FULL_KEYS, OWN_WORK_KEYS } from './DashboardWidgets'
import { useDashboardVariant } from './useDashboardVariant'
import { WIDGET_CATALOG } from './widgetCatalog'

/**
 * The dashboard's own "⚙ Widgets" settings — pick which of the charts below
 * the KPI row actually render. Same shape as the ticket list's
 * `ColumnChooserMenu`, deliberately: both are a per-user display preference
 * behind a settings button in the screen's own header, not a modal or a
 * separate page.
 *
 * The KPI cards above are not in this list. They are the organisation's six
 * headline figures rather than one of the optional widgets, and §S-05 does
 * not offer hiding them.
 *
 * <h2>The list is scoped to the reader's own dashboard variant</h2>
 *
 * A Developer, QA or Deployment role's dashboard renders only two of the
 * fourteen widgets (`useDashboardVariant`'s `own-work` layout) — the rest ask
 * a question with no per-resource answer and are never requested at all. This
 * menu offers exactly the keys that variant renders, so every checkbox here
 * does something: there is no control for a widget the reader's dashboard
 * cannot show regardless, which would be indistinguishable from a broken
 * toggle.
 */
export function DashboardWidgetChooserMenu() {
  const hiddenWidgets = useDashboardWidgetPreferencesStore((s) => s.hiddenWidgets)
  const toggleWidget = useDashboardWidgetPreferencesStore((s) => s.toggleWidget)
  const variant = useDashboardVariant()

  const availableKeys: readonly string[] = variant === 'own-work' ? OWN_WORK_KEYS : FULL_KEYS
  const offeredWidgets = WIDGET_CATALOG.filter((widget) => availableKeys.includes(widget.key))

  return (
    <PopoverPrimitive.Root>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          className="flex h-9 items-center gap-1.5 rounded-control border border-border bg-surface px-3 text-sm text-content hover:bg-subtle focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
        >
          <Settings2 className="h-4 w-4" />
          Widgets
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="end"
          sideOffset={4}
          className="z-50 w-64 rounded-control border border-border bg-surface p-2 shadow-modal"
        >
          <p className="px-2 pb-1.5 pt-1 text-caption font-medium uppercase tracking-wide text-content-muted">
            Dashboard components
          </p>
          <ul className="flex max-h-80 flex-col overflow-y-auto">
            {offeredWidgets.map((widget) => (
              <li key={widget.key}>
                <label className="flex cursor-pointer items-center gap-2 rounded-control px-2 py-1.5 text-sm text-content hover:bg-subtle">
                  <input
                    type="checkbox"
                    checked={!hiddenWidgets.includes(widget.key)}
                    onChange={() => toggleWidget(widget.key)}
                    className="h-4 w-4 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
                  />
                  {widget.label}
                </label>
              </li>
            ))}
          </ul>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}
