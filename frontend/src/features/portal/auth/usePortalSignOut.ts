import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

import { usePortalAuthStore } from './portalAuthStore'

/**
 * A-130 · sign out of the portal.
 *
 * Client-side only — there is no server-side logout or blacklist for portal
 * access tokens (A-130 did not build one; it issues one access token with no
 * refresh cookie and no revocation list). "Signing out" here can only clear
 * local state and stop sending the token; the token itself stays technically
 * valid until its own short expiry passes. That is a deliberate absence in
 * what A-130 shipped, not an oversight in this rewiring.
 */
export function usePortalSignOut() {
  const signOut = usePortalAuthStore((state) => state.signOut)
  const navigate = useNavigate()

  return useCallback(() => {
    signOut()
    navigate('/portal/login', { replace: true })
  }, [signOut, navigate])
}
