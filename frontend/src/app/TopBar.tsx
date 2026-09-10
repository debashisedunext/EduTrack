import { useState, type KeyboardEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { ProjectSwitcher } from './ProjectSwitcher'
// A-116 · Stream A's, flagged rather than dropped in quietly: this file is
// C-005's. Renders nothing unless the caller holds both modules, so the top bar
// is unchanged for every single-module user in the product.
import { ModuleSwitcher } from '@/features/launcher/ModuleSwitcher'
import { NotificationBell } from './NotificationBell'
import { ChatBadge } from './ChatBadge'
import { AvatarMenu } from './AvatarMenu'
import { ticketCodeIn, ticketPath } from '@/features/tickets/detail/entityLinks'
import { useCommandPaletteStore } from './commandPaletteStore'

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

// Top bar: global search, project switcher, notification bell, chat badge,
// avatar menu — blueprint §7.2. This search box navigates to the filtered
// ticket list; Ctrl+K's command palette (C-006) is the faster jump-to-ticket
// path and is reachable from the hint on the right of this same field.
export function TopBar() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const openPalette = useCommandPaletteStore((s) => s.setOpen)

  /*
    The bar carries two ticketing-specific controls, and under `/onboarding/**`
    they mean different things — one is retargeted, the other removed.

    **Search is retargeted, not dropped.** The field searches *ticket* IDs,
    keywords and people, and its Enter handler navigates to `/tickets` — so
    typing a school's name into it from the onboarding module leaves the
    module. The position is the right one though: a global search box at the
    top left is where anybody looks for it. In onboarding it searches clients
    by name and lands on the client list's own `?q=` filter, which is the
    module's equivalent destination — a list with filters, sorting and paging,
    exactly as `/tickets?q=` is for a keyword.

    **`ProjectSwitcher` is removed.** It picks a *ticketing project* ("Client
    CRM Platform"); onboarding is scoped by client and module role, and no
    `/onboarding` screen reads that selection. There is no onboarding
    equivalent to retarget it to.

    The `⌘K` palette hint goes with the ticketing search: C-006's palette jumps
    to tickets, so offering it beside a client search would promise the wrong
    kind of result.

    `ModuleSwitcher` stays — it is the way back out — as do the bell, chat and
    avatar, which are the application's rather than the ticketing module's.

    Path alone decides, unlike `Sidebar`'s `inOnboarding`, which also checks the
    ONBOARDING grant. The sidebar has to: showing a module's navigation to
    somebody without the grant would be the frontend disagreeing with the gate
    about what exists. Here the question is narrower — "what does this control
    mean on the page I am on" — and the answer follows from the URL however the
    reader got there.
  */
  const { pathname } = useLocation()
  const inOnboarding = pathname.startsWith('/onboarding')

  /**
   * A-072 · a pasted ticket code goes straight to the ticket.
   *
   * Blueprint gap item 9 is the whole reason: *"people share ticket IDs in
   * email all day"*. Somebody who has just pasted `CRM-26-00347` wants that
   * ticket, and sending them to a filtered list with one row in it — which is
   * what this did — makes them click again for something they had already
   * named exactly.
   *
   * Anything else is still a keyword search against the list, which is the
   * right destination for a word: a list has filters, sorting and paging, and
   * the palette's six results do not.
   */
  function onSearchKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter' || !query.trim()) return

    /*
      Onboarding has no equivalent of a ticket code — a client is named, not
      coded — so there is no "went straight to the one you named" shortcut to
      mirror here. `?q=` is `useObClientFilters`' own parameter, so this lands
      on a list already filtered and with the term still in the field, which is
      what makes a second search from the results page work.
    */
    if (inOnboarding) {
      navigate(`/onboarding/clients?q=${encodeURIComponent(query.trim())}`)
      return
    }

    const code = ticketCodeIn(query)
    if (code) {
      navigate(ticketPath(code))
      setQuery('')
      return
    }
    navigate(`/tickets?q=${encodeURIComponent(query.trim())}`)
  }

  return (
    <header className="flex h-14 shrink-0 items-center gap-4 border-b border-border bg-surface px-4">
      <div className="relative w-full max-w-sm">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={onSearchKeyDown}
          placeholder={
            inOnboarding ? 'Search client by name…' : 'Search ticket ID, keyword or person…'
          }
          // The placeholder is not an accessible name — it disappears the
          // moment anybody types — and this field's meaning changes between
          // the two modules, so it needs one that says which.
          aria-label={inOnboarding ? 'Search clients by name' : 'Search tickets'}
          className={inOnboarding ? 'pl-9' : 'pl-9 pr-16'}
        />
        {!inOnboarding && (
          <button
            type="button"
            onClick={() => openPalette(true)}
            aria-label="Open command palette"
            className="absolute right-2 top-1/2 flex -translate-y-1/2 items-center gap-0.5 rounded border border-border px-1.5 py-0.5 text-[10px] font-medium text-content-muted transition-colors hover:bg-subtle hover:text-content"
          >
            {isMac ? '⌘' : 'Ctrl'}K
          </button>
        )}
      </div>

      <div className="ml-auto flex items-center gap-2">
        <ModuleSwitcher />
        {!inOnboarding && <ProjectSwitcher />}
        <NotificationBell />
        <ChatBadge />
        <AvatarMenu />
      </div>
    </header>
  )
}
