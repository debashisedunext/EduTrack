import * as React from 'react'

import { useListUsers } from '@/api/generated/users/users'
import type { User } from '@/api/generated/model/user'

/**
 * Who a project may name as its **Implementor manager**.
 *
 * <h2>Why this is not simply the directory</h2>
 *
 * <p>It was. Both project forms filled the field from `useListUsers()`, which
 * is every active person in the organisation — so a project could be handed to
 * somebody holding `OB_STEP_OWNER`, and the DPS N Project was. The screen
 * accepted it, `ob_projects.implementor_manager_user_id` stored it, the task
 * panel offered that person the review controls because the project names
 * them, and the first verdict they pressed came back
 * <em>"Your onboarding role does not permit this action."</em> —
 * `ObModuleRoleFilter` refusing the route before the service that would have
 * accepted them was ever reached.
 *
 * <p>Two layers disagreed about who a reviewer is: the screen said *whoever
 * the project names*, the platform said *whoever holds the role*. The platform
 * is the one that decides, so the picker now asks it.
 *
 * <h2>Two roles, one request</h2>
 *
 * <p>`OB_MANAGER` or `OB_ADMIN` — exactly `ADMIN_AND_MANAGER` in
 * `ObModuleRoleRules`, which is what the four review routes are gated on.
 * Asked as one repeatable parameter rather than two calls, because two
 * cursor-paged lists merged in the client is a page boundary waiting to drop
 * somebody.
 *
 * <p>This narrows what can be *offered*. It is not a permission check and
 * cannot be: `requireReviewer` narrows further to this project's own
 * `implementorManagerUserId`, and holding the role opens no project the
 * platform would otherwise have closed.
 *
 * <h2>Active only</h2>
 *
 * <p>Naming a deactivated person as the escalation path is naming nobody, and
 * it reads as an answer on the screen.
 */
export function useObManagerOptions(): {
  managers: readonly User[]
  isPending: boolean
} {
  const query = useListUsers({
    obModuleRole: ['OB_MANAGER', 'OB_ADMIN'],
    isActive: true,
    // The whole set in one page: a picker that paged would offer the first
    // hundred names and silently omit the rest.
    limit: 200,
  })

  const managers = React.useMemo(() => query.data?.data ?? [], [query.data?.data])

  return { managers, isPending: query.isPending }
}
