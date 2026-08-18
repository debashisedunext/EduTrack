/**
 * A-071 · how S-16 renders the two open-ended fields the server sends.
 *
 * <h2>Why a client-side list at all, when the reports hub argues against one</h2>
 *
 * `ReportsHubPage` fetches its catalogue from the server precisely so the
 * frontend does not hold a second copy of a server vocabulary. This file looks
 * like the thing that argues against, and the difference is worth stating.
 *
 * The audit vocabulary is not a list the server maintains — it is **derived**
 * from the route table (`AuditActions`), so it grows silently whenever anybody
 * in any stream adds an endpoint, and there is no catalogue endpoint to fetch
 * because there is no catalogue. What is below is therefore not a copy of a
 * list; it is a set of nicer labels for values that arrive as
 * `MASTERS`/`ROLES_UPDATED`, and **every unknown value still renders**, tidied
 * rather than hidden. That is the property that makes the duplication safe: a
 * module added next month appears in the table on the day it is first used, and
 * the only thing it lacks is a friendlier caption in one dropdown.
 *
 * The alternative — a `/audit-logs/vocabulary` endpoint — would mean a contract
 * change and a round trip to learn that `tickets` is spelled "Tickets".
 */

/** A module, as `entity_type` holds it, with the caption S-16's filter shows. */
export interface AuditModule {
  value: string
  label: string
}

/**
 * The modules worth offering in the filter, in the order the sidebar lists
 * them — the first static path segment of every route group that mutates
 * anything. Not exhaustive, and not required to be: the filter is a
 * convenience, and a row whose module is missing here is still listed, still
 * searchable and still exported.
 */
export const AUDIT_MODULES: AuditModule[] = [
  { value: 'tickets', label: 'Tickets' },
  { value: 'users', label: 'Sign-in & accounts' },
  { value: 'masters', label: 'Masters' },
  { value: 'projects', label: 'Projects' },
  { value: 'clients', label: 'Clients' },
  { value: 'import_batches', label: 'Imports' },
  { value: 'notifications', label: 'Notifications' },
  { value: 'chat', label: 'Chat' },
  { value: 'audit_logs', label: 'Audit log' },
]

/**
 * `ROLES_UPDATED` as "Roles updated".
 *
 * Title case rather than a lookup table, so a term nobody has seen before
 * still reads as English. A map would have to be edited every time a route is
 * added anywhere in the product — by whoever added it, in Stream A's
 * directory, which is the arrangement that guarantees it is never edited.
 */
export function actionLabel(action: string | undefined): string {
  if (!action) return '—'
  const words = action.toLowerCase().split('_').filter(Boolean)
  if (words.length === 0) return action
  return words
    .map((word, index) => (index === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(' ')
}

/** `import_batches` as "Imports", falling back to the raw value tidied. */
export function moduleLabel(module: string | undefined): string {
  if (!module) return '—'
  const known = AUDIT_MODULES.find((m) => m.value === module)
  return known ? known.label : actionLabel(module)
}

/**
 * True for the terms that mean somebody was refused something.
 *
 * Used only to tint the row, and the tint is the point: a screenful of ordinary
 * activity must not hide the three lines somebody opened this screen for.
 *
 * Listed rather than pattern-matched on a prefix, because the list is short and
 * closed — these are the terms written by name in `AuditActions`, not the
 * derived ones. A new refusal term added there has to be added here too, and
 * the alternative (matching `LOGIN_` and `ACCESS_`) would tint `LOGIN_SUCCESS`,
 * which is the opposite of what the tint means.
 */
export function isRefusal(action: string | undefined): boolean {
  if (!action) return false
  return (
    action === 'ACCESS_DENIED' ||
    action === 'LOGIN_FAILED' ||
    action === 'LOGIN_THROTTLED' ||
    action === 'LOGIN_LOCKED_OUT' ||
    action === 'LOGIN_2FA_FAILED'
  )
}
