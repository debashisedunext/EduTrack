import * as React from 'react'
import { useParams } from 'react-router-dom'
import { RotateCcw } from 'lucide-react'

import type { SlaPolicyCell } from '@/api/generated/model/slaPolicyCell'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { toast } from '@/components/ui/use-toast'

import { ProjectTabs } from './ProjectTabs'
import {
  buildOverrides,
  cellKey,
  draftFor,
  draftsFor,
  groupByTaskType,
  isDirty,
  sourceLabel,
  sourceVariant,
  summarise,
  validate,
  type CellDraft,
} from './slaMatrix'
import { useReplaceSlaMatrix, useSlaMatrix } from './slaMatrixQueries'

/**
 * S-10 Project Master — the SLA tab. B-018.
 *
 * "Per task type × level → response hrs, resolution hrs, L1/L2 escalation
 * targets", which is forty-four editable cells behind one wholesale `PUT`.
 *
 * <h2>Inherited figures are shown, and saying so is the whole screen</h2>
 *
 * `sla_policies` is layered: a project may have its own row for a cell, or fall
 * through to a project-level default, the org-wide default, or a master's. Every
 * cell therefore has a figure a ticket would really be measured against, and a
 * grid that showed only this project's rows would be almost entirely blank for
 * a project that works perfectly well. So each row shows what it resolves to and
 * **where that came from** — an `override` chip when this project set it, plain
 * text otherwise.
 *
 * <h2>A Save button, unlike the Team tab next door</h2>
 *
 * B-017 saves on change and has no Save button, which is right for it: each
 * edit is one `PATCH` of one field. This operation is a **replace**, so
 * save-on-change would mean forty-four wholesale replaces while somebody types,
 * each racing the last. One button, one `If-Match`, one transaction.
 *
 * The consequence is a dirty state, and the thing that has to be got right with
 * it is what a *cleared* box means: emptying a resolution target removes the
 * override, so the cell goes back to inheriting. There is no separate delete
 * control, because "no target here" and "inherit" are the same request as far as
 * the row is concerned and two controls for one outcome is how they disagree.
 *
 * <h2>What is deliberately not here</h2>
 *
 * - **Editing the project-level default** (`taskTypeId: null`, §6's rung 2).
 *   A task type × level grid has no cell for "any task type"; it is rendered as
 *   the source of the cells it answers and left alone. Its own editor, if one is
 *   ever wanted, is a different screen.
 * - **A read-only mode for non-Admins.** The write is `master.write`, which only
 *   Admin holds, and the frontend has no capability check to hang this on yet —
 *   `/me` carries `permissions[]` but nothing reads it for gating. A PM sees the
 *   inputs and meets a `403` in the banner. **Flagged for Stream A**: this is the
 *   first screen in masters whose read and write have different roles, so it is
 *   the first place the gap is visible rather than theoretical.
 */
export function SlaMatrixPage() {
  const params = useParams<{ projectId?: string }>()
  const projectId = params.projectId ? Number(params.projectId) : null

  const { data, isPending, isError } = useSlaMatrix(projectId)

  if (projectId == null) {
    return <p className="p-6 text-sm text-danger-text">No project selected.</p>
  }

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 p-6">
      <ProjectTabs active="SLA" />

      {isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">This project’s SLA matrix could not be loaded.</p>
      ) : (
        <MatrixForm
          projectId={projectId}
          cells={data.cells}
          etag={data.etag}
          // Remounts the form when the server's grid changes underneath it —
          // after a save, or after another tab's edit invalidates the query.
          // Without it the drafts would keep the values the user was editing
          // and quietly disagree with the `isOverride` flags they are compared
          // against, which is how a cleared cell reappears as an override.
          key={data.etag ?? 'no-etag'}
        />
      )}
    </div>
  )
}

function MatrixForm({
  projectId,
  cells,
  etag,
}: {
  projectId: number
  cells: SlaPolicyCell[]
  etag: string | null
}) {
  const [drafts, setDrafts] = React.useState<Record<string, CellDraft>>(() => draftsFor(cells))
  const [bannerError, setBannerError] = React.useState<string | null>(null)

  const replace = useReplaceSlaMatrix(projectId)
  const problems = validate(cells, drafts)
  const summary = summarise(cells, drafts)
  const groups = groupByTaskType(cells)

  const problemByKey = new Map(problems.map((p) => [p.key, p.message]))

  function update(key: string, patch: Partial<CellDraft>) {
    setDrafts((current) => ({ ...current, [key]: { ...current[key], ...patch } }))
  }

  function save() {
    setBannerError(null)
    replace.mutate(
      { overrides: buildOverrides(cells, drafts), etag },
      {
        onSuccess: (saved) =>
          toast({
            title:
              saved.cells.filter((c) => c.isOverride).length === 0
                ? 'Every cell now follows the defaults'
                : 'SLA matrix saved',
          }),
        onError: (error: ApiError) =>
          setBannerError(
            // A 412 is the one refusal with a specific next step, and the
            // remount on the refetched tag is what makes "reload" work.
            error.status === 412
              ? 'Somebody else changed this matrix while you were editing it. Reload the page and reapply your changes.'
              : Object.values(error.fieldErrors)[0]?.[0]
                ?? error.problem.detail
                ?? 'The SLA matrix could not be saved.',
          ),
      },
    )
  }

  return (
    <>
      {bannerError ? (
        <div
          role="alert"
          className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          {bannerError}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col gap-1">
          <p className="text-sm text-content-muted">
            {summary.overrideCount === 0
              ? `All ${summary.total} combinations follow the organisation and master defaults.`
              : `${summary.overrideCount} of ${summary.total} combinations are set on this project.`}
          </p>
          {/*
            Stated because it is the one state on this screen with a
            consequence somewhere else: a cell nothing has a figure for gives
            its tickets no planned close date, which removes them from the
            breach sweep, the pre-breach warning and the delayed KPI — silently.
          */}
          {summary.unsetCount > 0 ? (
            <p className="text-sm text-warning-text">
              {summary.unsetCount} {summary.unsetCount === 1 ? 'combination has' : 'combinations have'} no
              target at all. Tickets raised there get no planned close date.
            </p>
          ) : null}
        </div>

        <Button
          type="button"
          onClick={save}
          disabled={summary.dirtyCount === 0 || problems.length > 0 || replace.isPending}
        >
          {replace.isPending
            ? 'Saving…'
            : summary.dirtyCount === 0
              ? 'Saved'
              : `Save ${summary.dirtyCount} change${summary.dirtyCount === 1 ? '' : 's'}`}
        </Button>
      </div>

      {groups.map((group) => (
        <section key={group.taskTypeId} className="flex flex-col gap-2">
          <h2 className="text-sm font-semibold text-content">{group.taskTypeName}</h2>

          <TableContainer>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Level</TableHead>
                  <TableHead scope="col">Response (working hrs)</TableHead>
                  <TableHead scope="col">Resolution (working hrs)</TableHead>
                  <TableHead scope="col">Escalate L1</TableHead>
                  <TableHead scope="col">Escalate L2</TableHead>
                  <TableHead scope="col">Source</TableHead>
                  <TableHead scope="col">
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {group.cells.map((cell) => (
                  <CellRow
                    key={cellKey(cell.taskTypeId, cell.level)}
                    cell={cell}
                    taskTypeName={group.taskTypeName}
                    draft={drafts[cellKey(cell.taskTypeId, cell.level)]}
                    problem={problemByKey.get(cellKey(cell.taskTypeId, cell.level))}
                    onChange={(patch) => update(cellKey(cell.taskTypeId, cell.level), patch)}
                    disabled={replace.isPending}
                  />
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </section>
      ))}
    </>
  )
}

// ---------------------------------------------------------------------------
// one cell
// ---------------------------------------------------------------------------

function CellRow({
  cell,
  taskTypeName,
  draft,
  problem,
  onChange,
  disabled,
}: {
  cell: SlaPolicyCell
  taskTypeName: string
  draft: CellDraft
  problem: string | undefined
  onChange: (patch: Partial<CellDraft>) => void
  disabled: boolean
}) {
  const where = `${taskTypeName} at ${cell.level}`
  const dirty = isDirty(cell, draft)
  const errorId = problem ? `sla-error-${cell.taskTypeId}-${cell.level}` : undefined

  return (
    <TableRow>
      <TableCell>
        <span className="font-medium text-content">{levelLabel(cell.level)}</span>
        {problem ? (
          <p id={errorId} role="alert" className="text-caption text-danger-text">
            {problem}
          </p>
        ) : null}
      </TableCell>

      <TableCell>
        <Input
          type="number"
          min={0}
          step="0.25"
          value={draft.responseHrs}
          disabled={disabled}
          onChange={(e) => onChange({ responseHrs: e.target.value })}
          aria-label={`Response hours for ${where}`}
          aria-invalid={problem ? true : undefined}
          aria-describedby={errorId}
          // Empty is a real value: a policy that only targets resolution is a
          // real one and the column is nullable for it.
          placeholder="—"
          className="w-24"
        />
      </TableCell>

      <TableCell>
        <Input
          type="number"
          min={0}
          step="0.25"
          value={draft.resolutionHrs}
          disabled={disabled}
          onChange={(e) => onChange({ resolutionHrs: e.target.value })}
          aria-label={`Resolution hours for ${where}`}
          aria-invalid={problem ? true : undefined}
          aria-describedby={errorId}
          placeholder="—"
          className="w-24"
        />
      </TableCell>

      <TableCell>
        <EscalationToggle
          checked={draft.escalateToL1}
          disabled={disabled}
          onChange={(escalateToL1) => onChange({ escalateToL1 })}
          label={`Escalate ${where} to the reporting manager on breach`}
        />
      </TableCell>

      <TableCell>
        <EscalationToggle
          checked={draft.escalateToL2}
          disabled={disabled}
          onChange={(escalateToL2) => onChange({ escalateToL2 })}
          label={`Escalate ${where} to the reporting manager’s manager after 48 working hours`}
        />
      </TableCell>

      <TableCell>
        {/*
          Only an override is chipped. Four coloured chips for four rungs would
          turn the one distinction this screen exists to make into a palette.
          "Unsaved" wins over the source, because until the save lands the
          source shown is about figures that are no longer on screen.
        */}
        {dirty ? (
          <Chip variant="warning">unsaved</Chip>
        ) : cell.isOverride || cell.source === 'NONE' ? (
          <Chip variant={sourceVariant(cell.source)}>{sourceLabel(cell.source)}</Chip>
        ) : (
          <span className="text-sm text-content-muted">{sourceLabel(cell.source)}</span>
        )}
      </TableCell>

      <TableCell>
        {/*
          Reverting an override empties both boxes, which is how the save drops
          it — the same request as "inherit". Offered only where there is
          something to revert: on an inherited cell it would do nothing, and a
          button that does nothing is one somebody clicks twice.
        */}
        <button
          type="button"
          onClick={() =>
            onChange(cell.isOverride && !dirty
              ? { responseHrs: '', resolutionHrs: '' }
              : draftFor(cell))}
          disabled={disabled || (!cell.isOverride && !dirty)}
          aria-label={
            cell.isOverride && !dirty
              ? `Stop overriding ${where} and inherit the default`
              : `Discard the unsaved change to ${where}`
          }
          title={
            cell.isOverride && !dirty
              ? 'Clear this override and inherit the default'
              : 'Discard this change'
          }
          className="rounded-control p-1.5 text-content-muted hover:bg-subtle hover:text-content disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <RotateCcw className="h-4 w-4" aria-hidden />
        </button>
      </TableCell>
    </TableRow>
  )
}

/**
 * A flag, not a recipient.
 *
 * Blueprint §6 fixes who each level means — L1 the assignee's reporting
 * manager, L2 that manager's manager — so there is nobody to pick here, and a
 * resource dropdown would be asking the administrator to restate the reporting
 * chain the org already holds. `title` is where that is said, because a
 * checkbox labelled "L1" and nothing else is a checkbox nobody can answer.
 */
function EscalationToggle({
  checked,
  disabled,
  onChange,
  label,
}: {
  checked: boolean
  disabled: boolean
  onChange: (next: boolean) => void
  label: string
}) {
  return (
    <input
      type="checkbox"
      checked={checked}
      disabled={disabled}
      onChange={(e) => onChange(e.target.checked)}
      aria-label={label}
      title={label}
      className="h-4 w-4 rounded-control border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:opacity-40"
    />
  )
}

/** Title case, because `CRITICAL` in a table cell reads as shouting. */
const levelLabel = (level: string) => level.charAt(0) + level.slice(1).toLowerCase()
