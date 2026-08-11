/**
 * Where every entity on the ticket detail page points.
 *
 * S-20's last bullet is the whole of C-019's "every entity a link": assignee →
 * resource profile, project → project dashboard, client → client 360, linked
 * ticket → that ticket, cycle → that cycle's effort logs.
 *
 * **Three of those five screens are not built yet** — the resource 360 is
 * A-069, the client 360 is Stream B's, the project dashboard is Stream A's.
 * Every path therefore lives here rather than inline in the panel, so the day
 * one of them lands its owner changes one line in one file instead of hunting
 * `to={...}` expressions across the feature. `App.tsx` registers the same
 * patterns against `ScreenPlaceholder` so a link never lands on the catch-all
 * "Not found" route, which reads as a bug rather than as a screen that has not
 * been built.
 *
 * The route *patterns* are exported alongside the builders for the same
 * reason: `App.tsx` declares routes from `RESOURCE_ROUTE`, not from a second
 * copy of the string, so a builder and its route cannot drift apart.
 */

export const TICKET_ROUTE = '/tickets/:ticketId'
export const PROJECT_ROUTE = '/projects/:projectId'
export const CLIENT_ROUTE = '/clients/:clientId'
export const RESOURCE_ROUTE = '/resources/:userId'

export const ticketPath = (ticketId: string): string => `/tickets/${ticketId}`

export const projectPath = (projectId: number): string => `/projects/${projectId}`

export const clientPath = (clientId: number): string => `/clients/${clientId}`

/** A resource's 360° profile — A-069 / S-28. */
export const resourcePath = (userId: number): string => `/resources/${userId}`

/**
 * A cycle's own effort logs.
 *
 * Both parameters matter. `cycle` re-fetches `GET /tickets/{id}/full?cycle=N`,
 * which is what makes an earlier cycle's journey render read-only rather than
 * the current one's; `tab` lands the reader on the effort list rather than on
 * whichever tab they happened to be on. A cycle link that only set `tab` would
 * silently show the *current* cycle's hours under an earlier cycle's label —
 * exactly the cross-cycle contamination PLAN.md §3.4 exists to prevent.
 */
export const cycleEffortPath = (ticketId: string, cycleNo: number): string =>
  `${ticketPath(ticketId)}?cycle=${cycleNo}&tab=effort`
