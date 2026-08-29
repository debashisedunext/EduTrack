import * as React from 'react';
import { cn } from '@/lib/utils';

export interface SettingsTab {
  /** Also the `?tab=` value, so a tab is a link a colleague can paste. */
  id: string;
  label: string;
  content: React.ReactNode;
}

/**
 * The settings tab strip.
 *
 * <p><strong>This is the second hand-rolled copy of the APG tabs pattern in
 * this repository</strong> — `features/tickets/detail/TicketDetailTabs.tsx` is
 * the first, and its own comment says so: "If a second screen needs tabs, that
 * is the moment to promote this into `components/ui` with a Storybook entry."
 * This is that moment, and it is deliberately not being taken here.
 *
 * `components/ui/` is Stream C's directory (TEAM-PLAN §6), and promoting the
 * component means moving C's file, giving it a Storybook entry and updating
 * C's import — three edits in C's paths inside a Stream A pull request. That
 * needs Divyansh's sign-off, not a quiet commit. The duplication is recorded
 * here so the next person finds the reason rather than a second accident, and
 * so the promotion can be raised as its own small task with both callers
 * already written.
 *
 * Keyboard behaviour follows the APG tabs pattern: one tab stop for the whole
 * strip, arrows move between tabs, Home/End jump to the ends. Selection
 * follows focus, which is safe here because no panel fetches on becoming
 * visible — `Profile` is the only one that reads, and React Query has it
 * cached by the time the strip can be arrowed through.
 */
export function SettingsTabs({
  tabs,
  activeId,
  onSelect,
}: {
  tabs: SettingsTab[];
  activeId: string;
  onSelect: (id: string) => void;
}) {
  const refs = React.useRef<Record<string, HTMLButtonElement | null>>({});
  const activeIndex = Math.max(
    0,
    tabs.findIndex((t) => t.id === activeId),
  );
  const active = tabs[activeIndex];

  function move(nextIndex: number) {
    const next = tabs[(nextIndex + tabs.length) % tabs.length];
    onSelect(next.id);
    refs.current[next.id]?.focus();
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    switch (event.key) {
      case 'ArrowRight':
        move(activeIndex + 1);
        break;
      case 'ArrowLeft':
        move(activeIndex - 1);
        break;
      case 'Home':
        move(0);
        break;
      case 'End':
        move(tabs.length - 1);
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  return (
    <div>
      <div
        role="tablist"
        aria-label="Settings"
        onKeyDown={onKeyDown}
        className="flex gap-1 overflow-x-auto border-b border-border"
      >
        {tabs.map((tab) => {
          const selected = tab.id === active.id;
          return (
            <button
              key={tab.id}
              ref={(node) => {
                refs.current[tab.id] = node;
              }}
              type="button"
              role="tab"
              id={`settings-tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`settings-panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(tab.id)}
              className={cn(
                '-mb-px whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
                selected
                  ? 'border-b-primary text-primary'
                  : 'border-b-transparent text-content-muted hover:text-content',
              )}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      <div
        role="tabpanel"
        id={`settings-panel-${active.id}`}
        aria-labelledby={`settings-tab-${active.id}`}
        tabIndex={0}
        className="pt-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
      >
        {active.content}
      </div>
    </div>
  );
}

/** The card every settings panel is built from — one heading, one description, one body. */
export function SettingsSection({
  title,
  description,
  children,
}: {
  title: string;
  description?: React.ReactNode;
  children?: React.ReactNode;
}) {
  const headingId = React.useId();
  return (
    <section
      aria-labelledby={headingId}
      className="rounded-card border border-border bg-surface p-5 shadow-rest"
    >
      <h2 id={headingId} className="text-sm font-semibold text-content">
        {title}
      </h2>
      {description ? <p className="mt-1 text-sm text-content-muted">{description}</p> : null}
      {children ? <div className="mt-4">{children}</div> : null}
    </section>
  );
}
