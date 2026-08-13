import type { Permission } from '@/api/generated/model/permission'

/**
 * B-015 · how S-09's capability list becomes a matrix.
 *
 * Pure functions, no React and no network, because this is the part of the
 * screen with rules in it — what groups with what, what a section header says,
 * and whether a save actually changes anything.
 *
 * **The blueprint's matrix and this one are not the same shape.** S-09 describes
 * `module × create/read/update/delete/approve`. The seeded vocabulary is
 * eighteen dotted capability strings across six categories, and it is already
 * load-bearing — the JWT `permissions[]` claim and A-033's `@PreAuthorize`
 * expressions both name these exact codes, so re-cutting them to fit a grid
 * would be a cross-stream breaking change made for a layout. Rendering them as
 * `category × capability` is the deviation, recorded in `RoleService`'s javadoc
 * and in this feature's README.
 */

/** One module's worth of the matrix, in the order the section renders. */
export interface PermissionGroup {
  /** `permissions.category` — `ticket`, `history`, `admin`, … */
  category: string
  /** Title case for the section heading: `ticket` → `Ticket`. */
  label: string
  permissions: Permission[]
}

/**
 * Section headings for the six seeded categories.
 *
 * Falls back to the raw category for anything a later migration adds, so a
 * nineteenth permission in a seventh category renders under a plain heading
 * rather than under `undefined`.
 */
const CATEGORY_LABELS: Record<string, string> = {
  admin: 'Administration',
  ticket: 'Tickets',
  history: 'History & ribbon',
  reports: 'Reports',
  master: 'Master data',
  audit: 'Audit',
}

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category.charAt(0).toUpperCase() + category.slice(1)
}

/**
 * Groups the catalogue into matrix sections.
 *
 * The server already returns `(category, code)` order, so this preserves the
 * order it was given rather than re-sorting: a second sort here is a second
 * opinion about the order, and the two drift.
 */
export function groupByCategory(permissions: Permission[]): PermissionGroup[] {
  const groups: PermissionGroup[] = []
  const byCategory = new Map<string, PermissionGroup>()

  for (const permission of permissions) {
    let group = byCategory.get(permission.category)
    if (!group) {
      group = { category: permission.category, label: categoryLabel(permission.category), permissions: [] }
      byCategory.set(permission.category, group)
      groups.push(group)
    }
    group.permissions.push(permission)
  }
  return groups
}

/**
 * Toggles one checkbox, returning a new set.
 *
 * An ungrantable permission cannot be ticked here at all — the checkbox is
 * disabled and the server refuses it with a 422, and this is the third place
 * that rule holds. Belt and braces is right for the one that guards the
 * append-only history.
 */
export function toggle(selected: Set<string>, permission: Permission): Set<string> {
  if (!permission.isGrantable) return selected

  const next = new Set(selected)
  if (next.has(permission.code)) {
    next.delete(permission.code)
  } else {
    next.add(permission.code)
  }
  return next
}

/** Ticks or clears a whole section at once — every grantable row in it. */
export function toggleGroup(
  selected: Set<string>,
  group: PermissionGroup,
  grant: boolean,
): Set<string> {
  const next = new Set(selected)
  for (const permission of group.permissions) {
    if (!permission.isGrantable) continue
    if (grant) {
      next.add(permission.code)
    } else {
      next.delete(permission.code)
    }
  }
  return next
}

/**
 * Whether a section's header checkbox reads on, off or indeterminate.
 *
 * Ungrantable rows are excluded from the count. Counting them would leave the
 * History section permanently indeterminate — its `history.edit_delete` can
 * never be ticked — and a control that can never reach "all" is a control
 * nobody trusts.
 */
export function groupState(
  selected: Set<string>,
  group: PermissionGroup,
): 'none' | 'some' | 'all' {
  const grantable = group.permissions.filter((p) => p.isGrantable)
  if (grantable.length === 0) return 'none'

  const ticked = grantable.filter((p) => selected.has(p.code)).length
  if (ticked === 0) return 'none'
  return ticked === grantable.length ? 'all' : 'some'
}

/**
 * Whether the matrix differs from what was loaded.
 *
 * Order-insensitive, because the server returns `(category, code)` order and
 * the user ticks in whatever order they like — comparing the arrays directly
 * would report every screen as dirty the moment anything was touched and
 * untouched again.
 */
export function hasChanges(selected: Set<string>, saved: readonly string[]): boolean {
  if (selected.size !== saved.length) return true
  return saved.some((code) => !selected.has(code))
}

/**
 * The body for `PUT /masters/roles/{roleId}/permissions`.
 *
 * Sorted so that two saves of the same selection produce byte-identical
 * requests — which makes a diff in a network log mean something.
 */
export function toRequest(selected: Set<string>): string[] {
  return [...selected].sort()
}
