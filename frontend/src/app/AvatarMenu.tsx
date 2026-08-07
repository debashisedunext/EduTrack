import * as PopoverPrimitive from '@radix-ui/react-popover'
import { KeyRound, LogOut, User } from 'lucide-react'
import { useGetMe } from '@/api/generated/auth/auth'
import { setAccessToken } from '@/api/http'

function initials(name: string) {
  return name.split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('')
}

// Avatar menu — Profile, Change password, Logout — blueprint §7.2.
export function AvatarMenu() {
  const { data: me } = useGetMe()
  const user = me?.data

  function logout() {
    setAccessToken(null)
    window.location.assign('/login')
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
          <MenuItem icon={KeyRound} label="Change password" />
          <MenuItem icon={LogOut} label="Logout" onClick={logout} />
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

function MenuItem({ icon: Icon, label, onClick }: { icon: typeof User; label: string; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-2 rounded-control px-3 py-2 text-left text-sm text-content transition-colors hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <Icon className="h-4 w-4 text-content-muted" />
      {label}
    </button>
  )
}
