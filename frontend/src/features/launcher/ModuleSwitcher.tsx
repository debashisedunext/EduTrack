import { ArrowLeftRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuthStore } from '@/features/auth/authStore'

/**
 * A-116 · the other half of the launcher — getting back out of a module.
 *
 * <h2>It renders nothing for almost everybody</h2>
 *
 * Onboarding plan §2.2 gives the switcher to dual-module users, and A-116 says
 * single-module users skip the launcher entirely. Somebody holding one module
 * has nowhere to switch to, so a control offering it would be a button that
 * leads to a chooser with one option — which is the same non-choice the landing
 * rule already exists to avoid.
 *
 * So: fewer than two modules, no element at all. Not a disabled button, which
 * would occupy the top bar of every single-module user in the product to
 * advertise something they cannot use.
 *
 * <h2>A link to the launcher, not a menu</h2>
 *
 * The obvious richer version is a dropdown that jumps straight to the other
 * module. This goes back to the launcher instead, and the prototype does the
 * same — its top bar carries one "⇄ Switch module" button to `launcher`.
 *
 * The reason is that the launcher is not only a router: it carries each
 * module's live counts, and those are the thing somebody switching actually
 * wants to see before deciding. A dropdown would skip the numbers and land them
 * on a dashboard they then have to read to learn what a chip would have told
 * them.
 *
 * <h2>Deliberately not module-aware</h2>
 *
 * It does not grey out the module you are already in, because the launcher is
 * reachable from both and re-entering the one you came from is a legitimate
 * thing to do — the counts may be why you went there.
 */
export function ModuleSwitcher() {
  const modules = useAuthStore((s) => s.user?.modules) ?? []

  if (modules.length < 2) return null

  return (
    <Link
      to="/launcher"
      title="Switch module"
      className="flex items-center gap-1.5 rounded-control border border-border px-2.5 py-1.5
                 text-xs font-medium text-content-muted transition-colors
                 hover:bg-subtle hover:text-content
                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <ArrowLeftRight aria-hidden="true" className="size-3.5" />
      Switch module
    </Link>
  )
}
