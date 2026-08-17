import { AlertCircle, Check, Info, KeyRound, Loader2 } from 'lucide-react'

import { useDescribeImportSchema } from '@/api/generated/imports/imports'
import type { ImportSchemaField } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import {
  isMappingComplete,
  missingRequiredFields,
  sharedColumns,
  unmappedSourceColumns,
  withColumn,
  type ColumnMapping,
} from './columnMapping'
import { type ImportSchemaKey } from './importQueries'
import { MappingPresets } from './MappingPresets'

/**
 * S-34 step 3 — column mapping. B-033, blueprint §4B.3.
 *
 * ## One row per *our* field, not per their column
 *
 * §4B.3 asks for "a manual override dropdown per column", and the direction it
 * has to be read in is the mapping's own: target field → source column. Rendered
 * the other way round — a row per column in the file with a dropdown of our
 * fields — the screen cannot show a required column that is absent from the file
 * at all, because there is no row for a column that is not there. That is the one
 * case the step exists to catch.
 *
 * ## The field list comes from the server
 *
 * `useDescribeImportSchema`, not a constant in this file. B-030's registry is the
 * single declaration of what the client master accepts, and `required` in
 * particular is not derivable from anything step 2 returned. A copy here would be
 * a second declaration — and B-038's resource import would need a third.
 *
 * ## Native `<select>`, twenty of them
 *
 * The same call `TaskTypeListPage` records: a plain list of options with no
 * search, no grouping and no custom rendering is what the platform control
 * already does, keyboard- and screen-reader-correct for free. Twenty Radix
 * selects would also be twenty portals on one screen.
 */
export function MappingStep({
  schema,
  headers,
  rowCount,
  mapping,
  onChange,
  onBack,
  onContinue,
}: {
  schema: ImportSchemaKey
  /** The uploaded sheet's headings, in sheet order. */
  headers: string[]
  rowCount: number
  mapping: ColumnMapping
  onChange: (mapping: ColumnMapping) => void
  onBack: () => void
  /**
   * Step 4. Omitted until B-034 exists — and omitted rather than passed as a
   * no-op, so the button is disabled and says why instead of looking broken. The
   * same shape B-032 left step 2's own Continue in.
   */
  onContinue?: () => void
}) {
  const described = useDescribeImportSchema(schema)
  const fields = described.data?.data.fields ?? []

  const missing = missingRequiredFields(fields, mapping)
  const ready = fields.length > 0 && isMappingComplete(fields, mapping)
  const ignored = unmappedSourceColumns(headers, mapping)
  const shared = sharedColumns(mapping)
  const mapped = fields.filter((field) => mapping[field.name]).length

  if (described.isPending) {
    return (
      <p className="flex items-center gap-2 text-sm text-content-muted" role="status">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        Loading the columns this import accepts…
      </p>
    )
  }

  if (described.isError) {
    return (
      <p
        role="alert"
        className="flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
      >
        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
        <span>
          The columns this import accepts could not be loaded, so the mapping cannot be
          shown. Nothing has been written — your file is still uploaded. Try again, or ask
          an administrator to check the import schema is registered.
        </span>
      </p>
    )
  }

  return (
    <div className="space-y-4">
      <p className="max-w-2xl text-sm text-content-muted">
        Each row is a column this import accepts. Those whose heading matched your
        file are already filled in; change any of them, and set the ones that did
        not match. {rowCount.toLocaleString()} rows are waiting and none of them
        has been read yet.
      </p>

      <MappingPresets
        schema={schema}
        fields={fields}
        headers={headers}
        mapping={mapping}
        onApply={onChange}
      />

      {/*
        The blocking condition, above the table rather than under it. §4B.3 makes
        this the rule of the step, and a user who has scrolled twenty rows to a
        disabled button should not have to scroll back to find out why.
      */}
      {missing.length > 0 && (
        <p
          role="status"
          className="flex items-start gap-2 rounded-control border border-warning bg-surface p-3 text-sm text-warning-text"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            {missing.length === 1 ? 'One required column is' : `${missing.length} required columns are`}{' '}
            not mapped yet: {missing.map((field) => field.header).join(', ')}. Choose a
            column for {missing.length === 1 ? 'it' : 'each of them'} to continue.
          </span>
        </p>
      )}

      <div className="overflow-x-auto">
        <table className="w-full min-w-[36rem] text-sm">
          <caption className="sr-only">
            The columns this import accepts, and which column of your file each one reads
          </caption>
          <thead>
            <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-content-muted">
              <th scope="col" className="py-2 pr-4 font-medium">
                Import column
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                Column in your file
              </th>
              <th scope="col" className="py-2 font-medium">
                Notes
              </th>
            </tr>
          </thead>
          <tbody>
            {fields.map((field) => (
              <MappingRow
                key={field.name}
                field={field}
                headers={headers}
                chosen={mapping[field.name] ?? ''}
                sharedWith={sharedFieldsFor(shared, mapping[field.name], field.name)}
                onChoose={(column) => onChange(withColumn(mapping, field.name, column))}
              />
            ))}
          </tbody>
        </table>
      </div>

      <p className="text-sm text-content-muted">
        {mapped} of {fields.length} import columns mapped.
      </p>

      {/*
        Columns of theirs that nothing reads. Not an error — a file exported from
        another system carries columns this import has no home for. Said anyway,
        because the alternative is somebody discovering after the commit that the
        Account Manager column they filled in was never read, and that one is a
        real absence rather than a mapping they missed.
      */}
      {ignored.length > 0 && (
        <p className="flex items-start gap-2 text-sm text-content-muted">
          <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            {ignored.length === 1
              ? 'One column in your file will not be imported'
              : `${ignored.length} columns in your file will not be imported`}
            : {ignored.map((header) => `“${header}”`).join(', ')}. That is fine if you
            did not mean to import them.
          </span>
        </p>
      )}

      <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
        <Button type="button" variant="secondary" onClick={onBack}>
          Back to upload
        </Button>
        <Button
          type="button"
          disabled={!ready || !onContinue}
          onClick={onContinue}
          title={
            !ready
              ? 'Every required column needs a column from your file before the preview can run'
              : onContinue
                ? undefined
                : 'The dry-run preview arrives with the next step of this screen'
          }
        >
          Continue to validation
        </Button>
        <p className="text-sm text-content-muted">
          {!ready
            ? 'Nothing has been written. No client changes until you have confirmed the preview.'
            : onContinue
              ? 'The next step is a dry run — every row’s outcome, with nothing written.'
              : 'The mapping is complete. The dry-run preview is not available yet, and nothing has been written — no client has changed.'}
        </p>
      </div>
    </div>
  )
}

/**
 * One field's row.
 *
 * The empty option is `— not imported —` rather than blank, because a blank
 * option in a list of headings is indistinguishable from a heading that happens
 * to be empty — and B-032 does produce those, suffixed, for a sheet with a stray
 * formatted column.
 */
function MappingRow({
  field,
  headers,
  chosen,
  sharedWith,
  onChoose,
}: {
  field: ImportSchemaField
  headers: string[]
  chosen: string
  /** Other fields reading the same column, if any. */
  sharedWith: string[]
  onChoose: (column: string) => void
}) {
  const unmappedRequired = field.required && chosen.length === 0
  const selectId = `map-${field.name}`

  return (
    <tr className="border-b border-border align-top">
      <th scope="row" className="py-2 pr-4 text-left font-normal">
        <label htmlFor={selectId} className="font-medium text-content">
          {field.header}
        </label>
        {field.required && (
          <span className="ml-1 text-danger-text" aria-hidden="true">
            *
          </span>
        )}
        {field.required && <span className="sr-only"> (required)</span>}
        {/*
          The natural key gets its own line rather than sharing the required
          asterisk. "Rows are matched on this column" is a different fact from
          "this column cannot be blank", and it is the one that decides whether
          the import creates four hundred clients or updates them.
        */}
        {field.naturalKey && (
          <span className="mt-0.5 flex items-center gap-1 text-xs text-content-muted">
            <KeyRound className="h-3 w-3 shrink-0" aria-hidden="true" />
            Rows are matched on this column
          </span>
        )}
      </th>
      <td className="py-2 pr-4">
        <select
          id={selectId}
          className={cn(
            'h-10 w-full min-w-40 rounded-control border bg-surface px-3 text-sm text-content',
            unmappedRequired ? 'border-danger' : 'border-border',
          )}
          value={chosen}
          aria-invalid={unmappedRequired || undefined}
          onChange={(event) => onChoose(event.target.value)}
        >
          <option value="">— not imported —</option>
          {headers.map((header) => (
            <option key={header} value={header}>
              {header}
            </option>
          ))}
        </select>
      </td>
      <td className="py-2 text-xs text-content-muted">
        {chosen.length > 0 && sharedWith.length === 0 && (
          <span className="flex items-center gap-1 text-success-text">
            <Check className="h-3 w-3 shrink-0" aria-hidden="true" />
            Mapped
          </span>
        )}
        {sharedWith.length > 0 && (
          // Allowed, not an error: one Email column feeding both email fields is
          // a real file. Named because the other way it happens is a misread row.
          <span className="text-warning-text">
            Also read by {sharedWith.length} other field
            {sharedWith.length === 1 ? '' : 's'}
          </span>
        )}
        {chosen.length === 0 && field.required && (
          <span className="text-danger-text">Required — pick a column</span>
        )}
        {chosen.length === 0 && !field.required && <span>Left out</span>}
        {field.allowedValues && field.allowedValues.length > 0 && (
          <span className="mt-1 block">
            Accepts {field.allowedValues.join(', ')}
          </span>
        )}
      </td>
    </tr>
  )
}

function sharedFieldsFor(
  shared: Map<string, string[]>,
  column: string | undefined,
  field: string,
): string[] {
  if (!column) return []
  return (shared.get(column) ?? []).filter((other) => other !== field)
}
