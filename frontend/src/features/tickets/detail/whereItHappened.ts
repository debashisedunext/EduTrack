import type { Module } from '@/api/generated/model/module'
import type { Ticket } from '@/api/generated/model/ticket'
import { isRichTextEmpty, sanitizeRichText } from '@/components/ui/rich-text'

/**
 * C-069 · the four §7.5 fields on S-20, as pure functions.
 *
 * ## These decide what is *shown*, never what is *allowed*
 *
 * The same division `commentPermissions.ts` and `levelChange.ts` already set
 * out. `TicketWriteService` refuses a deactivated module with a 400 keyed on
 * `moduleId`, sanitises both rich-text fields on the write path, and writes one
 * `FIELD_CHANGED` row per field that genuinely changed. Nothing here can grant
 * anything; what it does is stop the page offering an edit that will be refused
 * and stop it sending a request that would change nothing.
 */

/**
 * The name to render for a ticket's module — **resolved against every row the
 * master returns, active or not.**
 *
 * `GET /masters/modules` includes deactivated rows on purpose (D-060), and this
 * is the reason: a ticket raised last year against a module that has since been
 * retired still has to show its name. Filtering to active rows here would leave
 * that cell blank, which reads as missing data rather than as a retirement.
 *
 * @returns the name, or `undefined` when the master has not loaded or has no
 *          such row — the caller renders an em dash, never the raw id
 */
export function moduleName(modules: readonly Module[] | undefined, moduleId: number | null | undefined) {
  if (moduleId == null) return undefined
  return modules?.find((m) => m.id === moduleId)?.name
}

/**
 * What the Module editor may offer: the active rows, **plus the ticket's own
 * module when it has been retired since.**
 *
 * The create form filters retired modules out flatly (C-068) and is right to:
 * nothing should be *raised* against a retired module, and `ModuleGuard` refuses
 * it with a 400. Editing an existing ticket is the case that rule cannot cover.
 * A ticket already on `Transport` opened for an unrelated edit — a typo in the
 * screen name — would find its Module dropdown showing an empty trigger, and
 * saving anything would look like it had silently dropped the module. Keeping
 * the current row in the list means the editor opens showing what the ticket
 * actually says, and a retired module can be moved *off* but never *onto*.
 */
export function moduleOptions(
  modules: readonly Module[] | undefined,
  currentModuleId: number | null | undefined,
): Module[] {
  return (modules ?? []).filter((m) => m.isActive !== false || m.id === currentModuleId)
}

/**
 * A short free-text field on its way to `PATCH /tickets`.
 *
 * **Empty means `null`, not `''`.** All four columns are nullable and "not
 * recorded" is their only empty state (§7.5: "Every column is nullable"), so an
 * empty string would store a second, indistinguishable kind of blank that every
 * report then has to know about. Clearing a screen name has to be expressible,
 * and this is how.
 */
export function normalizeText(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/** Rich text on its way out — sanitised, and `null` when the editor holds nothing. */
export function normalizeRichText(value: string): string | null {
  if (isRichTextEmpty(value)) return null
  const clean = sanitizeRichText(value).trim()
  return clean === '' ? null : clean
}

/**
 * Whether a field is worth sending at all.
 *
 * **A no-op edit must not be sent**, and this is the one rule here with teeth.
 * `TicketWriteService` compares before it writes and would record nothing, so
 * the server is not at risk — but `ticket_history` is append-only and a row
 * written there cannot be taken back, so the client not sending a no-op is the
 * cheaper half of the same guarantee. It also keeps the panel from flashing a
 * save that changed nothing.
 *
 * Compared after normalising, so `'Fees '` → `'Fees'` and `<p>a</p>` reformatted
 * by the editor into identical sanitised markup both count as unchanged.
 */
export function isUnchanged(current: string | null | undefined, next: string | null): boolean {
  return (current ?? null) === next
}

/** The four fields as the panel reads them off a ticket. */
export function whereItHappened(ticket: Pick<Ticket, 'moduleId' | 'screenName' | 'feature' | 'stepsToGenerate'>) {
  return {
    moduleId: ticket.moduleId ?? null,
    screenName: ticket.screenName ?? null,
    feature: ticket.feature ?? null,
    stepsToGenerate: ticket.stepsToGenerate ?? null,
  }
}
