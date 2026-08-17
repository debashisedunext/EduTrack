import { RoleCode } from '@/api/generated/model'
import { useAuthStore } from '@/features/auth/authStore'

/**
 * A-062 · which of §S-05's two dashboards this person gets.
 *
 * §S-05: "the Developer's dashboard shows only widgets 1–6, 9, 12 scoped to
 * `assignee = me`, plus My due today / this week". That is a smaller screen, not
 * a differently-filtered one, and only the client can lay it out.
 *
 * ## This is a layout decision, and never a guard
 *
 * Worth stating plainly, because the codebase argues hard against role rules
 * living in TypeScript — `authStore` refuses to hold a role→route map for
 * exactly that reason.
 *
 * The difference is what breaks if this is wrong. Forcing the organisation
 * layout as a Developer gets you the same six cards scoped to yourself, three
 * charts drawn from your own rows, and the server's `unavailableReason` on the
 * rest: `DashboardScope` decides whose rows answer, and it does so from the
 * session on the server, where nothing typed into a browser can reach it. What
 * you would not get is anybody else's numbers. So the worst outcome of a bug
 * here is a screen with six explanatory sentences on it, not a leak.
 *
 * ## Why not derive it from the widget responses instead
 *
 * Tempting, and it would need no role check at all: ask for all nine widgets and
 * hide whichever come back unavailable. Two things stop it. Nine requests fire
 * to render three charts, and the grid renders nine skeletons before collapsing
 * to three — a visible reflow on every load, on the role whose dashboard is
 * meant to be the simplest. And the filter bar cannot be derived that way at
 * all: the Resource and Project dropdowns are inert for a delivery role
 * (`?assigneeId=` is ignored by design, and `resource_daily_stats` has no
 * project dimension to filter on), which is a fact about the request, not about
 * any one widget's answer.
 *
 * So: one statement of the rule, in one file, named after what it decides.
 */

/**
 * §2's three delivery roles — the same three `DashboardScope.ownWorkOnly`
 * names on the server.
 *
 * A second copy of a server-side rule and knowingly so. It is kept honest by
 * being narrow: this list decides how many boxes to draw, and the server decides
 * every number inside them. `DashboardVariantTest` on the backend asserts the
 * two lists still name the same roles, so a fourth delivery role added to
 * `RolePermissions` fails a test rather than quietly getting the wrong layout.
 */
const OWN_WORK_ONLY: readonly string[] = [
  RoleCode.DEVELOPER,
  RoleCode.QA,
  RoleCode.DEPLOYMENT,
]

export type DashboardVariant = 'own-work' | 'organisation'

export function useDashboardVariant(): DashboardVariant {
  const role = useAuthStore((s) => s.user?.role)
  // Unknown role — including the moment before the startup refresh has answered
  // — falls back to the organisation layout. That is the safe direction for a
  // *layout* default: it draws every widget and each one is answered, or
  // refused, by the server on its own merits. Defaulting the other way would
  // hide widgets from an Admin for the first few hundred milliseconds of every
  // reload.
  return role && OWN_WORK_ONLY.includes(role) ? 'own-work' : 'organisation'
}
