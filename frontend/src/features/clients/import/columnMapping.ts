import type { ImportSchemaField } from '@/api/generated/model'

/**
 * B-033 · S-34 step 3's rules, as pure functions.
 *
 * Pure because every one of them is a decision the screen makes many times per
 * keystroke and because the interesting cases are combinatorial — a preset saved
 * against another file, a required column nobody mapped, two fields pointing at
 * one column. Asserting those through a rendered table would need a `<select>`
 * driven per case to say what one function call says.
 *
 * ## Direction, once, because it is easy to get backwards
 *
 * A mapping is **target field → source column**, matching the contract's
 * `mapping` object and `ImportMapping` on the server. Inverted it cannot express
 * the ordinary case of a workbook with two columns we ignore, and it cannot
 * express two of our fields reading one of their columns — which is legitimate:
 * Primary Email and Support Email from a single `Email` column is a real file.
 */

/** Target field name → the heading in the user's sheet. */
export type ColumnMapping = Record<string, string>

/**
 * The required fields this mapping does not cover — a non-empty answer blocks
 * Next.
 *
 * Returns the fields rather than their names so the caller can say
 * `Client Code` (what the user sees in their file) instead of `clientCode`, and
 * in the schema's own order rather than in mapping order. §4B.3's rule is
 * "unmapped required columns block the Next button", and a list ordered by
 * whatever the user happened to touch last reads like a different complaint
 * every time.
 */
export function missingRequiredFields(
  fields: ImportSchemaField[],
  mapping: ColumnMapping,
): ImportSchemaField[] {
  return fields.filter((field) => field.required && !isMapped(mapping, field.name))
}

/**
 * Columns in the file that no field reads — worth telling the user about, and
 * deliberately not an error.
 *
 * A file exported from another system carries columns this import has no home
 * for, and refusing it would be wrong. But saying nothing is how somebody
 * discovers after the commit that the Account Manager column they carefully
 * filled in was never read — there is no template column for it (see
 * `ClientImportSchema`), and the only place that can be said is here.
 */
export function unmappedSourceColumns(headers: string[], mapping: ColumnMapping): string[] {
  const used = new Set(Object.values(mapping).filter((column) => column.length > 0))
  return headers.filter((header) => !used.has(header))
}

/** §4B.3's gate on Next, as one call. */
export function isMappingComplete(
  fields: ImportSchemaField[],
  mapping: ColumnMapping,
): boolean {
  return missingRequiredFields(fields, mapping).length === 0
}

/**
 * What a preset can and cannot be applied to this file.
 *
 * **A preset entry naming a column this file does not have is dropped, and
 * reported.** This is the whole reason applying a preset is a function rather
 * than an assignment. A preset is saved from one export and applied to the next
 * one; the next one may have been renamed, or may be a different report
 * entirely. Carrying the entry over would put a heading in the mapping that is
 * in no `<select>` on screen — so the row would render as unmapped while the
 * mapping said otherwise, and Next would be enabled over a required column that
 * is not actually mapped.
 *
 * Dropping it silently is the other failure: the user picks *CRM export*, sees
 * three fields fill in out of eleven, and has no way to tell a preset that was
 * always partial from one whose columns have been renamed. So the dropped
 * entries come back with the mapping.
 *
 * A target field the schema no longer declares is dropped the same way. The
 * server refuses to *save* one (`import-unknown-field`, 422), so this covers the
 * rows that were saved before a field was renamed — the case that refusal cannot
 * reach retroactively.
 *
 * Matching is exact, for `HeaderMatcher`'s reason: normalising "Support Email"
 * onto "Email" would put the helpdesk address in the account contact field, in a
 * mapping the user was shown and skimmed.
 */
export interface AppliedPreset {
  mapping: ColumnMapping
  /** Entries dropped because the column is not in this file, as `field → column`. */
  droppedColumns: { field: string; column: string }[]
  /** Entries dropped because the schema no longer declares the field. */
  droppedFields: string[]
}

export function applyPreset(
  preset: ColumnMapping,
  headers: string[],
  fields: ImportSchemaField[],
): AppliedPreset {
  const available = new Set(headers)
  const declared = new Set(fields.map((field) => field.name))

  const mapping: ColumnMapping = {}
  const droppedColumns: AppliedPreset['droppedColumns'] = []
  const droppedFields: string[] = []

  for (const [field, column] of Object.entries(preset)) {
    if (!declared.has(field)) {
      droppedFields.push(field)
    } else if (!available.has(column)) {
      droppedColumns.push({ field, column })
    } else {
      mapping[field] = column
    }
  }

  return { mapping, droppedColumns, droppedFields }
}

/**
 * Set or clear one field's column.
 *
 * Clearing **removes the key** rather than setting it to `''`. Absence has to
 * have one representation: an empty string counts as a key in
 * `Object.keys(mapping).length`, which is what the summary counts, and the server
 * strips it on save anyway — so leaving it in would make the screen disagree with
 * the preset it just saved.
 */
export function withColumn(
  mapping: ColumnMapping,
  field: string,
  column: string,
): ColumnMapping {
  const next = { ...mapping }
  if (column.length === 0) {
    delete next[field]
  } else {
    next[field] = column
  }
  return next
}

/**
 * Which fields share a source column, keyed by column.
 *
 * **Not an error** — see the note on direction above; one `Email` column feeding
 * both email fields is a real file and the mapping can express it. It is
 * surfaced because the other way it happens is a slip: the user changes one
 * `<select>`, misreads the row, and two fields now read the same column while
 * one column goes unread. Naming it costs a line and does not block anything.
 *
 * Only columns with more than one field are returned, so the ordinary case is an
 * empty map and the caller renders nothing.
 */
export function sharedColumns(mapping: ColumnMapping): Map<string, string[]> {
  const byColumn = new Map<string, string[]>()
  for (const [field, column] of Object.entries(mapping)) {
    if (column.length === 0) continue
    byColumn.set(column, [...(byColumn.get(column) ?? []), field])
  }
  for (const [column, fields] of byColumn) {
    if (fields.length < 2) byColumn.delete(column)
  }
  return byColumn
}

function isMapped(mapping: ColumnMapping, field: string): boolean {
  const column = mapping[field]
  return typeof column === 'string' && column.length > 0
}
