import * as PopoverPrimitive from '@radix-ui/react-popover'
import { KeyRound, LogOut, Moon, Sun, User } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useGetMe } from '@/api/generated/auth/auth'
import { useSignOut } from '@/features/auth/useSignOut'
import { useThemeStore } from './theme/themeStore'

function initials(name: string) {
  return name.split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('')
}

// Avatar menu — Profile, Change password, Logout — blueprint §7.2.
export function AvatarMenu() {
  const { data: me } = useGetMe()
  const user = me?.data
  const navigate = useNavigate()
  const signOut = useSignOut()
  const theme = useThemeStore((s) => s.theme)
  const toggleTheme = useThemeStore((s) => s.toggle)

  /*
    A-030. The placeholder this replaces cleared the access token and reloaded
    to /login, which looked right and was not: it never called
    `POST /auth/logout`, so the HttpOnly refresh cookie survived, and
    `AuthProvider`'s startup refresh signed the user straight back in on the
    next load. `useSignOut` ends both halves.

    `navigate` rather than `window.location.assign` for the same reason — a full
    page load would re-run the startup refresh against a cookie the server has
    just dropped, so the user would watch a redirect resolve into a 401 they did
    not cause. `RequireAuth` sends them to /login the moment the store goes
    anonymous, so this only has to leave the protected route.
  */
  function logout() {
    signOut()
    navigate('/login', { replace: true })
  }

  return (
    <PopoverPrimitive.Root>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          aria-label="Account menu"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-soft text-xs font-semibold text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          {user ? initials(user.displayName) : '…'}
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="end"
          sideOffset={8}
          className="z-50 w-56 overflow-hidden rounded-control border border-border bg-surface p-1 shadow-modal"
        >
          {user && (
            <div className="border-b border-border px-3 py-2">
              <p className="truncate text-sm font-medium text-content">{user.displayName}</p>
              <p className="truncate text-xs text-content-muted">{user.role}</p>
            </div>
          )}
          <MenuItem icon={User} label="Profile" />
          {/* S-03's voluntary variant. The forced one is reached by redirect. */}
          <MenuItem
            icon={KeyRound}
            label="Change password"
            onClick={() => navigate('/change-password')}
          />
          {/*
            D-15 · the theme switch.

            Labelled with the action rather than the state — "Dark mode" beside
            a moon is ambiguous about whether it reports what is on or offers
            what it will do, and a menu item is read as a verb. So the label
            says where the press lands, and `aria-pressed` carries the current
            state for a screen reader, which the wording alone cannot.
          */}
          <MenuItem
            icon={theme === 'dark' ? Sun : Moon}
            label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            onClick={toggleTheme}
            pressed={theme === 'dark'}
          />
          <MenuItem icon={LogOut} label="Logout" onClick={logout} />
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

function MenuItem({
  icon: Icon,
  label,
  onClick,
  pressed,
}: {
  icon: typeof User
  label: string
  onClick?: () => void
  /** Omitted for plain actions; set only where the item has an on/off state. */
  pressed?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      // Undefined rather than false when absent: `aria-pressed="false"` on
      // Profile or Logout would announce them as toggle buttons that are off.
      aria-pressed={pressed}
      className="flex w-full items-center gap-2 rounded-control px-3 py-2 text-left text-sm text-content transition-colors hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <Icon className="h-4 w-4 text-content-muted" />
      {label}
    </button>
  )
}
