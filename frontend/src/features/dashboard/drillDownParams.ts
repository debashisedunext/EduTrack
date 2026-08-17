import type { ListTicketsParams } from '@/api/generated/model'

/**
 * A-061 · turns a server-built drill-down URL into the parameters that fetch it.
 *
 * <h2>Why this is mechanical, and was not before A-060</h2>
 *
 * The dashboard builds every drill-down as a `/tickets?…` string server-side,
 * so the filter that produced a figure and the filter that opens it cannot
 * drift. The modal needs the same filter as an object rather than a URL, and
 * since A-060 the emitted names are exactly `GET /tickets`'s own — enforced by
 * `DrillDownContractTest` — so this is a type conversion and nothing more. It
 * deliberately does **not** know which filters exist or what they mean; the day
 * it starts deciding that, it becomes a third opinion about drill-downs and the
 * whole point was to have one.
 *
 * <h2>Unknown keys are dropped, loudly in the type and quietly at runtime</h2>
 *
 * A key not in one of the three sets below is skipped rather than passed
 * through. That is the same silent-drop that caused A-060's defect, so it is
 * worth being explicit about why it is right *here*: the contract test already
 * guarantees the dashboard emits nothing the list does not implement, and this
 * runs in the browser where the alternative — forwarding an unknown key — would
 * simply move the silent drop one layer further out, to Spring.
 */

/** Coerced with `Number`; a value that is not numeric is dropped rather than sent as NaN. */
const NUMERIC_KEYS = [
  'projectId',
  'clientId',
  'taskTypeId',
  'moduleId',
  'assigneeId',
  'limit',
] as const

/** Present-and-true or absent. `?isDelayed=false` is not a thing the dashboard emits. */
const BOOLEAN_KEYS = [
  'isDelayed',
  'isClientRaised',
  'reopenedOnly',
  'unassigned',
  'excludeClosed',
] as const

const STRING_KEYS = [
  'q',
  'level',
  'status',
  'stage',
  'sort',
  'dueFrom',
  'dueTo',
  'closedFrom',
  'closedTo',
  'reportedFrom',
  'reportedTo',
] as const

/**
 * @param drillDown a path with a query string, as the server builds it —
 *                  `/tickets?level=CRITICAL&excludeClosed=true`.
 */
export function drillDownToParams(drillDown: string): ListTicketsParams {
  const query = drillDown.includes('?') ? drillDown.slice(drillDown.indexOf('?') + 1) : ''
  const search = new URLSearchParams(query)
  const params: Record<string, unknown> = {}

  for (const key of NUMERIC_KEYS) {
    const raw = search.get(key)
    if (raw === null || raw === '') continue
    const value = Number(raw)
    if (Number.isFinite(value)) params[key] = value
  }

  for (const key of BOOLEAN_KEYS) {
    if (search.get(key) === 'true') params[key] = true
  }

  for (const key of STRING_KEYS) {
    const raw = search.get(key)
    if (raw !== null && raw !== '') params[key] = raw
  }

  return params as ListTicketsParams
}

/**
 * A short human description of what a drill-down narrows to, for the panel's
 * subtitle.
 *
 * <p>Built from the same query string rather than passed alongside it, so a
 * caption can never describe a different filter from the one being fetched —
 * which is the identical argument for building the URL server-side in the first
 * place, applied one level down.
 */
export function describeDrillDown(drillDown: string): string {
  const params = drillDownToParams(drillDown)
  const parts: string[] = []

  if (params.level) parts.push(String(params.level).toLowerCase())
  if (params.status) parts.push(`status ${String(params.status).toLowerCase()}`)
  if (params.excludeClosed) parts.push('still open')
  if (params.isDelayed) parts.push('overdue')
  if (params.reopenedOnly) parts.push('reopened')
  if (params.unassigned) parts.push('unassigned')

  const from = params.reportedFrom
  const to = params.reportedTo
  if (from && to) parts.push(from === to ? `raised on ${from}` : `raised ${from} to ${to}`)
  else if (to) parts.push(`raised on or before ${to}`)
  else if (from) parts.push(`raised on or after ${from}`)

  if (params.closedFrom && params.closedTo) {
    parts.push(
      params.closedFrom === params.closedTo
        ? `closed on ${params.closedFrom}`
        : `closed ${params.closedFrom} to ${params.closedTo}`,
    )
  }

  return parts.length > 0 ? parts.join(' · ') : 'all tickets in scope'
}
