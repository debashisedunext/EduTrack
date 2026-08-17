import { useState } from 'react'
import { AlertCircle, Bookmark, Loader2, Trash2 } from 'lucide-react'

import {
  useDeleteImportMappingPreset,
  useListImportMappingPresets,
  useSaveImportMappingPreset,
  getListImportMappingPresetsQueryKey,
} from '@/api/generated/imports/imports'
import type { ImportMappingPreset, ImportSchemaField } from '@/api/generated/model'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useQueryClient } from '@tanstack/react-query'

import { applyPreset, type ColumnMapping } from './columnMapping'
import { type ImportSchemaKey } from './importQueries'

/**
 * B-033 · §4B.3's "Mapping presets can be saved and reused for the next import."
 *
 * ## Why applying one is not just an assignment
 *
 * A preset is saved from one export and applied to the next. `applyPreset` drops
 * the entries whose column this file does not have, and this component says how
 * many it dropped — because a user who picks *CRM export* and watches three
 * fields fill in out of eleven otherwise cannot tell a preset that was always
 * partial from one whose columns have since been renamed.
 *
 * ## Saving replaces, and says so before it does
 *
 * The server upserts on `(schema, name)`, which is what makes correcting a preset
 * possible at all — see the migration. That is worth surfacing rather than
 * discovering: naming an existing preset changes the button's own label to
 * *Replace*, so the destructive reading of Save is the one on screen when it is
 * the one that applies.
 */
export function MappingPresets({
  schema,
  fields,
  headers,
  mapping,
  onApply,
  disabled,
}: {
  schema: ImportSchemaKey
  fields: ImportSchemaField[]
  /** This file's headings — what decides which preset entries survive. */
  headers: string[]
  /** The mapping as it stands, which is what Save stores. */
  mapping: ColumnMapping
  onApply: (mapping: ColumnMapping) => void
  disabled?: boolean
}) {
  const queryClient = useQueryClient()
  const presets = useListImportMappingPresets(schema)
  const save = useSaveImportMappingPreset()
  const remove = useDeleteImportMappingPreset()

  const [name, setName] = useState('')
  const [applied, setApplied] = useState<{
    name: string
    took: number
    droppedColumns: { field: string; column: string }[]
    droppedFields: string[]
  } | null>(null)

  const saved = presets.data?.data ?? []
  const trimmed = name.trim()
  const replacing = saved.some(
    // Case-insensitive, because the table's collation is: "CRM export" and "CRM
    // Export" are one preset there, so the button must not promise otherwise.
    (preset) => preset.name.toLowerCase() === trimmed.toLowerCase(),
  )

  /** Re-read rather than patch the cache: the server decides the id and the timestamp. */
  function refresh() {
    return queryClient.invalidateQueries({
      queryKey: getListImportMappingPresetsQueryKey(schema),
    })
  }

  function choose(preset: ImportMappingPreset) {
    const result = applyPreset(preset.mapping, headers, fields)
    onApply(result.mapping)
    setApplied({
      name: preset.name,
      took: Object.keys(result.mapping).length,
      droppedColumns: result.droppedColumns,
      droppedFields: result.droppedFields,
    })
    // Pre-filled so the obvious next action — fix the two columns it could not
    // place, then save it back under the same name — is one click rather than
    // retyping a name exactly enough to hit the upsert.
    setName(preset.name)
  }

  function submit() {
    if (trimmed.length === 0) return
    save.mutate(
      { schema, data: { name: trimmed, mapping } },
      { onSuccess: () => void refresh() },
    )
  }

  return (
    <div className="rounded-control border border-border bg-subtle p-4">
      <div className="flex flex-wrap items-baseline gap-2">
        <h3 className="text-sm font-medium text-content">Mapping presets</h3>
        <p className="text-xs text-content-muted">
          Shared with everyone who imports — a preset records how one export lines
          up with these columns, so next month’s file is one click.
        </p>
      </div>

      {presets.isPending && (
        <p className="mt-3 flex items-center gap-2 text-sm text-content-muted" role="status">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          Loading presets…
        </p>
      )}

      {saved.length > 0 && (
        <ul className="mt-3 flex flex-wrap gap-2" aria-label="Saved mappings">
          {saved.map((preset) => (
            <li
              key={preset.presetId}
              className="flex items-center gap-1 rounded-chip border border-border bg-surface pl-1"
            >
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={disabled}
                onClick={() => choose(preset)}
              >
                <Bookmark className="h-4 w-4" aria-hidden="true" />
                {preset.name}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-label={`Delete the ${preset.name} preset`}
                disabled={disabled || remove.isPending}
                onClick={() =>
                  remove.mutate(
                    { schema, presetId: preset.presetId },
                    // Invalidated on settled rather than on success: a 404 means
                    // somebody else already deleted it, and the entry has to
                    // leave this list either way.
                    { onSettled: () => void refresh() },
                  )
                }
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {/*
        What a preset could not place. Reported per preset rather than folded into
        the table's own "not mapped" state, because the two say different things:
        the table says a column is unmapped, this says the preset had an opinion
        about it that no longer applies.
      */}
      {applied && (applied.droppedColumns.length > 0 || applied.droppedFields.length > 0) && (
        <p
          role="status"
          className="mt-3 flex items-start gap-2 rounded-control border border-warning bg-surface p-3 text-sm text-warning-text"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            <strong>{applied.name}</strong> mapped {applied.took}{' '}
            {applied.took === 1 ? 'column' : 'columns'}.
            {applied.droppedColumns.length > 0 && (
              <>
                {' '}
                This file has no column called{' '}
                {applied.droppedColumns.map((entry) => `“${entry.column}”`).join(', ')} — map
                those fields by hand, then save the preset again to update it.
              </>
            )}
            {applied.droppedFields.length > 0 && (
              <>
                {' '}
                It also names {applied.droppedFields.length} field
                {applied.droppedFields.length === 1 ? '' : 's'} the import no longer has.
              </>
            )}
          </span>
        </p>
      )}

      <div className="mt-3 flex flex-wrap items-end gap-2">
        <label className="flex min-w-56 flex-1 flex-col gap-1 text-sm">
          <span className="font-medium text-content">Save this mapping as</span>
          <Input
            value={name}
            maxLength={80}
            placeholder="CRM export"
            disabled={disabled}
            onChange={(event) => setName(event.target.value)}
            aria-describedby={save.isError ? 'preset-save-error' : undefined}
          />
        </label>
        <Button
          type="button"
          variant="secondary"
          disabled={disabled || trimmed.length === 0 || save.isPending}
          onClick={submit}
        >
          {save.isPending ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <Bookmark className="h-4 w-4" aria-hidden="true" />
          )}
          {replacing ? `Replace “${trimmed}”` : 'Save preset'}
        </Button>
      </div>

      {save.isError && (
        <p
          id="preset-save-error"
          role="alert"
          className="mt-3 flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>{saveRefusal(save.error)}</span>
        </p>
      )}
    </div>
  )
}

/**
 * The server's refusal, in words that name the fix.
 *
 * Branches on `problem.type`, never on `title` or `detail` — CONVENTIONS.md §3
 * makes the type the stable half. `import-unknown-field` is the one worth its own
 * sentence: it means the mapping names a field this import no longer has, which is
 * not something the user did wrong on this screen and is not fixable by retyping
 * the name.
 */
function saveRefusal(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'The preset could not be saved. Check your connection and try again.'
  }
  if (error.is(PRESET_PROBLEM.unknownField)) {
    return (
      error.problem.detail ??
      'This mapping names a column the import no longer has. Re-map that field and save again.'
    )
  }
  if (error.status === 400) {
    return error.problem.detail ?? 'Give the preset a name and map at least one column.'
  }
  if (error.status === 403) {
    return 'You do not have permission to save import presets. Importing is an administrator action.'
  }
  return `The preset could not be saved (${error.status}).`
}

/** The problem `type` this screen branches on. Never match on `title` or `detail`. */
export const PRESET_PROBLEM = {
  unknownField: 'import-unknown-field',
} as const
