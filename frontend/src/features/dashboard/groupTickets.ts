/**
 * S-05 tab 3 · nests a flat ticket list Client → Module → Severity.
 *
 * <h2>Counts are true; rows are what fits</h2>
 *
 * Every node carries the number of tickets that are *actually* under it, and
 * separately the rows this render includes. Past the cap those diverge, and the
 * divergence is the whole reason this is a module with tests rather than three
 * `reduce` calls in a component: a header that says 40 while its drill-down
 * returns 200 is a bug people report as "the dashboard is lying", and a header
 * that says 200 above 40 visible rows is one they never report at all — they
 * just stop trusting the screen. `count` is the number the header shows and the
 * number its drill-down must return. `rows` is presentation.
 *
 * <h2>It does not know what a module is called, or which severity is worse</h2>
 *
 * Both are supplied by the caller, and neither has a sensible default:
 *
 * - `TicketSummary` carries `moduleId` and no name — there is no `ModuleRef` in
 *   the contract — so the label has to come from somewhere else. Taking a
 *   resolver means *where* (a field added to the row later, a masters fetch)
 *   stays the caller's decision and never a rewrite of this file.
 * - `Level` is an open string in the contract, configured per organisation
 *   through the priorities master. LOW/MEDIUM/HIGH/CRITICAL are examples in the
 *   spec, not the enum. Hardcoding that order would silently mis-sort every org
 *   that configured its own, which is the kind of wrong that looks fine in a
 *   screenshot.
 *
 * <h2>It does not build drill-down URLs</h2>
 *
 * Each node exposes the fields that identify it — `clientId`, `moduleId`,
 * `level` — and stops there. The dashboard's rule is that a drill-down is built
 * once, server-side, so the filter that produced a figure and the filter that
 * opens it cannot drift; `drillDownParams.ts` makes the same argument for not
 * re-deciding what filters mean in the browser. A grouping function that also
 * minted URLs would be a third opinion about drill-downs.
 */

/** The subset of a ticket this needs. Structural, so a list row or a fixture both fit. */
export interface GroupableTicket {
  ticketId: string
  level: string
  client?: { id?: number | null; name?: string | null } | null
  moduleId?: number | null
}

/** What identifies a node, for the caller to turn into a filter. */
export interface GroupFilter {
  clientId?: number
  moduleId?: number
  level?: string
}

export interface TicketGroup<T> {
  /** Stable across recomputes — safe as a React key and as accordion open-state. */
  key: string
  label: string
  /** Tickets genuinely under this node, whether or not they were rendered. */
  count: number
  /** `count` minus the rows rendered beneath it. Zero when nothing was dropped. */
  truncated: number
  filter: GroupFilter
  children: TicketGroup<T>[]
  /** Only ever populated on severity nodes — the leaves. */
  rows: T[]
}

export interface GroupedTickets<T> {
  groups: TicketGroup<T>[]
  /** Every ticket passed in. */
  total: number
  /** Rows this result actually carries. */
  rendered: number
  /** `total - rendered`. Non-zero means the cap bit; show the notice. */
  truncated: number
}

export interface GroupTicketsOptions {
  /** `moduleId` → display name. Anything unresolved falls back to `Module {id}`. */
  moduleLabel?: (moduleId: number) => string | null | undefined
  /**
   * Severity codes worst-first, from the priorities master in `seq` order. A
   * level not in this list sorts after every level that is, alphabetically, so
   * a newly configured priority appears at the bottom rather than vanishing.
   */
  severityOrder?: readonly string[]
  /** Maximum rows across the whole result. Blueprint's cap is 200. */
  limit?: number
}

export const DEFAULT_ROW_LIMIT = 200

/** Sorted last, and labelled rather than left blank — an empty header reads as a bug. */
const NO_CLIENT = 'No client'
const NO_MODULE = 'No module'

/**
 * Sorts missing-value buckets last, then compares labels.
 *
 * `localeCompare` with an explicit locale rather than the ambient one: the
 * ambient locale differs between a developer's browser and CI, and a sort that
 * depends on it produces a snapshot test that passes for whoever wrote it.
 */
function byLabel(a: { label: string; missing: boolean }, b: { label: string; missing: boolean }) {
  if (a.missing !== b.missing) return a.missing ? 1 : -1
  return a.label.localeCompare(b.label, 'en')
}

interface Bucket<T> {
  key: string
  label: string
  missing: boolean
  filter: GroupFilter
  items: { row: T; ticket: GroupableTicket }[]
}

function bucketBy<T>(
  items: { row: T; ticket: GroupableTicket }[],
  identify: (t: GroupableTicket) => { id: string; label: string; missing: boolean; filter: GroupFilter },
): Bucket<T>[] {
  const out = new Map<string, Bucket<T>>()
  for (const item of items) {
    const { id, label, missing, filter } = identify(item.ticket)
    let bucket = out.get(id)
    if (!bucket) {
      bucket = { key: id, label, missing, filter, items: [] }
      out.set(id, bucket)
    }
    bucket.items.push(item)
  }
  return [...out.values()].sort(byLabel)
}

/**
 * @param tickets a flat list, already filtered and scoped by the caller
 * @param select  pulls the groupable fields off whatever row type the caller has
 */
export function groupTickets<T>(
  tickets: readonly T[],
  select: (row: T) => GroupableTicket,
  options: GroupTicketsOptions = {},
): GroupedTickets<T> {
  const { moduleLabel, severityOrder = [], limit = DEFAULT_ROW_LIMIT } = options

  const items = tickets.map((row) => ({ row, ticket: select(row) }))
  const total = items.length

  const severityRank = new Map(severityOrder.map((level, i) => [level, i]))
  const bySeverity = (a: Bucket<T>, b: Bucket<T>) => {
    const ra = severityRank.get(a.filter.level ?? '') ?? Number.MAX_SAFE_INTEGER
    const rb = severityRank.get(b.filter.level ?? '') ?? Number.MAX_SAFE_INTEGER
    if (ra !== rb) return ra - rb
    return a.label.localeCompare(b.label, 'en')
  }

  const groups: TicketGroup<T>[] = bucketBy(items, (t) => {
    const id = t.client?.id
    return id == null
      ? { id: 'client:none', label: NO_CLIENT, missing: true, filter: {} }
      : { id: `client:${id}`, label: t.client?.name?.trim() || `Client ${id}`, missing: false, filter: { clientId: id } }
  }).map((clientBucket) => {
    const modules = bucketBy(clientBucket.items, (t) => {
      const id = t.moduleId
      return id == null
        ? { id: 'module:none', label: NO_MODULE, missing: true, filter: { ...clientBucket.filter } }
        : {
            id: `module:${id}`,
            label: moduleLabel?.(id)?.trim() || `Module ${id}`,
            missing: false,
            filter: { ...clientBucket.filter, moduleId: id },
          }
    }).map((moduleBucket) => {
      const severities = bucketBy(moduleBucket.items, (t) => ({
        id: `level:${t.level}`,
        label: t.level,
        missing: false,
        filter: { ...moduleBucket.filter, level: t.level },
      }))
        .sort(bySeverity)
        .map((severityBucket) => ({
          key: `${clientBucket.key}/${moduleBucket.key}/${severityBucket.key}`,
          label: severityBucket.label,
          count: severityBucket.items.length,
          truncated: 0,
          filter: severityBucket.filter,
          children: [] as TicketGroup<T>[],
          // Stable within a leaf, so two renders of the same data agree.
          rows: severityBucket.items
            .slice()
            .sort((a, b) => a.ticket.ticketId.localeCompare(b.ticket.ticketId, 'en'))
            .map((i) => i.row),
        }))

      return {
        key: `${clientBucket.key}/${moduleBucket.key}`,
        label: moduleBucket.label,
        count: moduleBucket.items.length,
        truncated: 0,
        filter: moduleBucket.filter,
        children: severities,
        rows: [] as T[],
      }
    })

    return {
      key: clientBucket.key,
      label: clientBucket.label,
      count: clientBucket.items.length,
      truncated: 0,
      filter: clientBucket.filter,
      children: modules,
      rows: [] as T[],
    }
  })

  const rendered = applyLimit(groups, limit)
  return { groups, total, rendered, truncated: total - rendered }
}

/**
 * Trims rows to `limit` in display order and records what each node lost.
 *
 * Walks the tree exactly as it is drawn, so the rows that survive are the ones
 * at the top of the screen — a cap that dropped an arbitrary subset would make
 * "the first 200" mean something different on every render. Counts are never
 * touched: a node whose rows are all gone still reports how many it has, which
 * is what its header and its drill-down agree on.
 */
function applyLimit<T>(groups: TicketGroup<T>[], limit: number): number {
  let budget = Math.max(0, limit)
  let rendered = 0

  const visit = (node: TicketGroup<T>): number => {
    if (node.children.length === 0) {
      const keep = Math.min(node.rows.length, budget)
      if (keep < node.rows.length) node.rows = node.rows.slice(0, keep)
      budget -= keep
      rendered += keep
      node.truncated = node.count - keep
      return keep
    }
    let kept = 0
    for (const child of node.children) kept += visit(child)
    node.truncated = node.count - kept
    return kept
  }

  for (const group of groups) visit(group)
  return rendered
}
