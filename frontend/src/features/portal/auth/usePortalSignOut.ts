import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

import { portalLogout } from '@/api/generated/portal/portal'

import { usePortalAuthStore } from './portalAuthStore'

/**
 * C-121 · sign out of the portal — `useSignOut`'s shape and its own ordering
 * note: the request goes first, unawaited, before the local token clear.
 */
export function usePortalSignOut() {
  const signOut = usePortalAuthStore((state) => state.signOut)
  const navigate = useNavigate()

  return useCallback(() => {
    portalLogout().catch(() => {
      /* best effort — the local clear below is what the user sees */
    })
    signOut()
    navigate('/portal/login', { replace: true })
  }, [signOut, navigate])
}
