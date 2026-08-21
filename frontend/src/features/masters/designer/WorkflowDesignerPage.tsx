import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import type { Stage } from '@/api/generated/model/stage'

import { RibbonStrip } from '@/components/ribbon/RibbonStrip'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import { useRoles } from '../roles/roleQueries'
import {
  useCreateStage,
  useReorderStages,
  useStage,
  useStages,
  useUpdateStage,
  useWorkflowTemplates,
} from '../stages/stageQueries'
import {
  EMPTY_STAGE_FORM,
  forwardReturnPaths,
  formToCreate,
  formToPatch,
  moveStage,
  orderChanged,
  returnTargetOptions,
  stageFormErrors,
  stageToForm,
  type StageFormState,
} from '../stages/stageForm'
import { MappingPanel } from '../templates/MappingPanel'
import { buildPreviewRibbon, previewChain } from '../templates/previewRibbon'
import { useCreateTemplate, useTemplate } from '../templates/templateQueries'
import {
  arcAreaHeight,
  arcPath,
  canvasWidth,
  dropIndex,
  insertAt,
  moveAnnouncement,
  resequence,
  returnArcs,
  returnPathRefusal,
  stageVocabulary,
  type PaletteEntry,
  type ReturnArc,
} from './canvasModel'

/**
 * S-30 — the workflow template designer. B-043.
 *
 * <p>Blueprint §7.4: <em>"the visual builder inside S-13: drag stages onto a
 * canvas, set owner role and SLA per stage, draw the allowed return paths,
 * preview the rendered ribbon, then map it to project × task type."</em>
 *
 * <h2>Its own route, and S-13 reserved it</h2>
 *
 * `App.tsx` has carried the note *"a template designer gets its own route (S-30)
 * when B-043 lands"* since B-039 mounted `/masters/statuses`. This is that route.
 * It is reached from tab 3 rather than from the sidebar, because S-30 is the
 * builder *inside* S-13 and a nav entry beside "Statuses, stages & workflow"
 * would read as a second, competing master.
 *
 * <h2>Nothing new reaches the server</h2>
 *
 * Every write here is a route B-040, B-041 and B-042 already shipped: create a
 * stage, patch one, replace the order, replace the mappings, create a template
 * with `copyStagesFromTemplateId`. So this task adds **no endpoint, no
 * `PermissionMatrix` row, no contract change and no migration** — the six roles
 * are already decided on all five, and a designer that invented a bulk "save the
 * whole flow" route would be inventing a transaction the append-only core has no
 * way to honour.
 *
 * <h2>The order is staged; everything else writes when you finish it</h2>
 *
 * This is the one arrangement worth arguing about, because a canvas invites the
 * opposite. A designer that held every edit until one Save would be a batch of
 * creates, patches and a reorder across three route families with **no rollback
 * between them** — half a flow saved, and no way to tell an Admin which half.
 *
 * So: dropping a node creates it, the inspector patches on Save, drawing an arc
 * patches `canReturnTo`. Only **reordering** is staged, because the reorder route
 * is a whole-set `PUT` under one `If-Match` and that is exactly what tab 2 does
 * with its drag. One Save button, one request, one precondition.
 *
 * <p>Which leaves one honest seam, named rather than hidden: a node dropped in the
 * middle is created <em>last</em> (the create route appends — a caller-chosen
 * `seq` collides with `uq_workflow_stages_seq`), and the canvas then moves it to
 * where it was dropped as a **staged** move. So the Save button lights up and the
 * live region says so.
 *
 * <h2>Keyboard parity, not a keyboard fallback</h2>
 *
 * B-040's README makes the case and this screen has more to lose by ignoring it:
 * a canvas is the archetype of the control that ships pointer-only. Every node
 * carries Move left / Move right, the palette has Add, and return paths are drawn
 * from a `<select>` in the inspector as well as by dragging a handle. The tests
 * drive the keyboard path, so the accessible route is the one under test rather
 * than the one alongside it.
 */

/** Node geometry, shared by the layout and the arcs so they cannot drift. */
const GEOMETRY = { nodeWidth: 208, gap: 40, baseline: 0, laneHeight: 30 }

export function WorkflowDesignerPage() {
  const params = useParams()
  const templateId = Number(params.templateId)

  const templates = useWorkflowTemplates()
  const detail = useTemplate(Number.isFinite(templateId) ? templateId : null)
  const stages = useStages(Number.isFinite(templateId) ? templateId : null)

  if (!Number.isFinite(templateId)) {
    return <EmptyState title="No such template" description="Pick one from the workflow master." />
  }

  if (detail.isPending || stages.isPending) {
    return <Skeleton className="h-screen w-full" />
  }
  if (detail.isError || !detail.data) {
    return (
      <EmptyState
        title="Could not load this template"
        description="Reload the page to try again."
      />
    )
  }

  return (
    <Designer
      key={templateId}
      templateId={templateId}
      template={detail.data.template}
      serverStages={stages.data?.stages ?? []}
      stagesEtag={stages.data?.etag ?? null}
      allTemplates={templates.data ?? []}
    />
  )
}

function Designer({
  templateId,
  template,
  serverStages,
  stagesEtag,
  allTemplates,
}: {
  templateId: number
  template: { name?: string; description?: string | null; isDefault?: boolean; isActive?: boolean }
  serverStages: Stage[]
  stagesEtag: string | null
  allTemplates: ReturnType<typeof useWorkflowTemplates>['data'] extends (infer T)[] | undefined
    ? T[]
    : never[]
}) {
  const reorder = useReorderStages()
  const create = useCreateStage()
  const update = useUpdateStage()

  const [ordered, setOrdered] = React.useState<Stage[] | null>(null)
  const [selectedId, setSelectedId] = React.useState<number | null>(null)
  const [announcement, setAnnouncement] = React.useState('')
  const [drawingFrom, setDrawingFrom] = React.useState<string | null>(null)
  const [duplicating, setDuplicating] = React.useState(false)

  // The selected stage's own `ETag`, read here rather than inside the inspector
  // because **both** writers need it: the inspector's Save, and the canvas's draw
  // gesture, which patches `canReturnTo` on whichever node the arrow starts at.
  // A patch sent with `If-Match: *` would disable the lost-update guard for every
  // client, which is what the whole tag exists to prevent.
  const loadedSelected = useStage(templateId, selectedId)

  // The staged order is dropped whenever the server's changes rather than merged,
  // for the reason `StageList` gives: a merge would have to guess whether a row
  // that moved underneath was this drag arriving back or somebody else's edit.
  React.useEffect(() => {
    setOrdered(null)
  }, [serverStages])

  const stages = ordered ?? serverStages
  const dirty = orderChanged(stages, serverStages)
  const broken = forwardReturnPaths(stages)
  const arcs = returnArcs(stages)
  const palette = stageVocabulary(allTemplates ?? [], stages)
  const selected = stages.find((s) => s.id === selectedId) ?? null

  const move = (from: number, to: number) => {
    const next = moveStage(stages, from, to)
    if (next === stages) return
    setOrdered(next)
    setAnnouncement(moveAnnouncement(stages[from], to, next.length))
  }

  const saveOrder = async () => {
    try {
      await reorder.mutateAsync({ templateId, stageIds: stages.map((s) => s.id), etag: stagesEtag })
      setOrdered(null)
      toast({ title: 'Flow saved' })
    } catch (error) {
      toast({
        title: 'That order was refused',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  /**
   * Finish a drawn return path: the arrow started at the selected node and the
   * Admin has just clicked its target.
   *
   * <p>The refusal is checked against **`serverStages`, not the staged order**,
   * and that is not an oversight. This write goes out now, so the server
   * validates it against the order it currently holds — checking a dragged order
   * the server has not been told about would refuse arrows it would accept, and
   * accept arrows it will refuse.
   */
  const drawTo = async (toCode: string) => {
    const source = serverStages.find((s) => s.stageCode === drawingFrom)
    if (!source) return

    const refusal = returnPathRefusal(serverStages, source.stageCode, toCode)
    if (refusal) {
      setDrawingFrom(null)
      toast({
        title: 'That return path was refused',
        description: refusal.message,
        variant: 'danger',
      })
      return
    }

    try {
      await update.mutateAsync({
        templateId,
        stageId: source.id,
        data: { canReturnTo: [...source.canReturnTo, toCode] },
        etag: loadedSelected.data?.etag ?? null,
      })
      setDrawingFrom(null)
      setAnnouncement(`Return path drawn from ${source.stageCode} to ${toCode}.`)
      toast({ title: `${source.stageCode} → ${toCode} drawn` })
    } catch (error) {
      toast({
        title: 'That return path was refused',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  /**
   * Drop a palette entry onto the canvas.
   *
   * The create appends and the move is staged — see the file header. `at` is
   * where the pointer aimed, and it is honoured locally so the node appears where
   * it was dropped rather than jumping to the end and being dragged back.
   */
  const addFromPalette = async (entry: PaletteEntry, at: number) => {
    const form: StageFormState = {
      ...EMPTY_STAGE_FORM,
      stageCode: entry.stageCode,
      displayName: entry.displayName,
      ownerRole: entry.ownerRole ?? '',
    }
    try {
      const created = await create.mutateAsync({ templateId, data: formToCreate(form) })
      setSelectedId(created?.id ?? null)
      if (created && at < stages.length) {
        setOrdered(insertAt(stages, created, at))
        setAnnouncement(
          `${created.displayName} added at position ${at + 1}. Save the flow to keep it there.`,
        )
      } else {
        setAnnouncement(`${entry.displayName} added at the end.`)
      }
      toast({ title: `${entry.displayName} added` })
    } catch (error) {
      toast({ title: 'That stage was refused', description: problemDetail(error), variant: 'danger' })
    }
  }

  return (
    <div className="flex flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <nav aria-label="Breadcrumb" className="text-caption text-content-muted">
            <Link to="/masters/statuses" className="underline">
              Statuses, stages &amp; workflow
            </Link>
            {' / Designer'}
          </nav>
          <h1 className="text-h2 text-content">{template.name}</h1>
          <p className="max-w-2xl text-body-sm text-content-muted">
            {template.description || 'Build the flow, then say which tickets follow it.'}
          </p>
          <div className="mt-2 flex flex-wrap gap-2">
            {template.isDefault && <Chip>Default</Chip>}
            {!template.isActive && <Chip>Inactive</Chip>}
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {/*
            §4A.5's "versioned by copy, never edited in place". The generated
            client names *this* screen as where that rule is kept — there is no
            version column to clone into, so the copy is a new template, and the
            designer is the only place with a reason to offer it.
          */}
          <Button type="button" variant="secondary" onClick={() => setDuplicating(true)}>
            Duplicate as new version
          </Button>
          <Button type="button" disabled={!dirty || broken.length > 0 || reorder.isPending} onClick={saveOrder}>
            Save flow
          </Button>
        </div>
      </header>

      {broken.length > 0 && (
        <p role="alert" className="rounded-control bg-danger-subtle p-3 text-body-sm text-danger">
          This order would leave {broken.length} return path
          {broken.length === 1 ? '' : 's'} pointing forwards: {broken.join(', ')}. A return path
          is a backward move — clear it, or move the other stage.
        </p>
      )}

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex flex-col gap-6">
          <Canvas
            stages={stages}
            arcs={arcs}
            selectedId={selectedId}
            drawingFrom={drawingFrom}
            onSelect={(id) => {
              const target = stages.find((s) => s.id === id)
              // A click while an arrow is in flight lands the arrow rather than
              // moving the selection — the selection is the arrow's own source,
              // so changing it mid-gesture would silently redirect what is being
              // drawn.
              if (drawingFrom && target && target.stageCode !== drawingFrom) {
                void drawTo(target.stageCode)
                return
              }
              setSelectedId(id)
            }}
            onMove={move}
            onStartDraw={(code) => {
              setDrawingFrom(code)
              if (code) {
                const source = stages.find((s) => s.stageCode === code)
                if (source) setSelectedId(source.id)
              }
            }}
            onDropPalette={addFromPalette}
            palette={palette}
            templateId={templateId}
          />

          <section
            aria-labelledby="designer-preview-heading"
            className="space-y-2 rounded-card border border-line p-4"
          >
            <h2 id="designer-preview-heading" className="text-h4 text-content">
              Ribbon preview
            </h2>
            {stages.length === 0 ? (
              <EmptyState
                title="Nothing to preview yet"
                description="Add a stage from the palette. A template with no stages routes no ticket."
              />
            ) : (
              <>
                {/*
                  B-041's builder over B-050's strip, unmodified — and fed the
                  *staged* order, which is what makes §7.4's "renders as the
                  Admin edits" true of a drag as well as of a field.

                  `resequence` is not decoration: both builders sort by `seq`,
                  and a dragged row still carries the seq it was saved with, so
                  handing them the raw array sorts the drag straight back out.
                */}
                <RibbonStrip ribbon={buildPreviewRibbon(resequence(stages))} />
                <p className="text-caption text-content-muted">
                  {previewChain(resequence(stages))}
                </p>
              </>
            )}
          </section>

          {/* S-30's last clause. B-043 extracted this from tab 3 rather than
              building a second one — see MappingPanel.tsx. */}
          <MappingPanel templateId={templateId} templateName={template.name ?? ''} />
        </div>

        <aside className="flex flex-col gap-6">
          <Palette entries={palette} onAdd={(entry) => addFromPalette(entry, stages.length)} />
          {selected ? (
            <Inspector
              key={selected.id}
              templateId={templateId}
              stage={selected}
              stages={serverStages}
              loaded={loadedSelected.data ?? null}
            />
          ) : (
            <section className="rounded-card border border-line p-4">
              <h2 className="text-h4 text-content">Stage settings</h2>
              <p className="text-body-sm text-content-muted">
                Pick a stage on the canvas to set its owner role, SLA and return paths.
              </p>
            </section>
          )}
        </aside>
      </div>

      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      {drawingFrom && (
        <p role="status" className="text-body-sm text-content-muted">
          Drawing a return path from <strong>{drawingFrom}</strong> — click the stage it should
          go back to, or press Escape.
        </p>
      )}

      {duplicating && (
        <DuplicateDialog
          templateId={templateId}
          templateName={template.name ?? ''}
          onClose={() => setDuplicating(false)}
        />
      )}
    </div>
  )
}

/** The nodes, the arcs beneath them, and the drop targets between them. */
function Canvas({
  stages,
  arcs,
  selectedId,
  drawingFrom,
  palette,
  onSelect,
  onMove,
  onStartDraw,
  onDropPalette,
}: {
  stages: Stage[]
  arcs: ReturnArc[]
  selectedId: number | null
  drawingFrom: string | null
  palette: PaletteEntry[]
  templateId: number
  onSelect: (id: number) => void
  onMove: (from: number, to: number) => void
  onStartDraw: (code: string | null) => void
  onDropPalette: (entry: PaletteEntry, at: number) => void
}) {
  const [dragFrom, setDragFrom] = React.useState<number | null>(null)

  const width = canvasWidth(stages.length, GEOMETRY)
  const arcHeight = arcAreaHeight(arcs, GEOMETRY)

  const handleDrop = (overIndex: number, side: 'before' | 'after', event: React.DragEvent) => {
    event.preventDefault()
    const at = dropIndex(overIndex, side)
    const paletteCode = event.dataTransfer.getData('application/x-edutrack-palette')
    if (paletteCode) {
      const entry = palette.find((p) => p.stageCode === paletteCode)
      if (entry) onDropPalette(entry, at)
      return
    }
    if (dragFrom != null) {
      // A move to a later index shifts by one once the node is lifted out.
      onMove(dragFrom, at > dragFrom ? at - 1 : at)
      setDragFrom(null)
    }
  }

  if (stages.length === 0) {
    return (
      <section aria-label="Workflow canvas" className="rounded-card border border-line p-4">
        <EmptyState
          title="An empty canvas"
          description="Drag a stage from the palette, or use its Add button. Nothing routes to this template until it has a live stage."
        />
      </section>
    )
  }

  return (
    <section aria-label="Workflow canvas" className="rounded-card border border-line p-4">
      <div className="overflow-x-auto pb-2">
        <ol
          className="relative flex list-none items-stretch"
          style={{ width: width || undefined, gap: GEOMETRY.gap }}
        >
          {stages.map((stage, index) => (
            <li key={stage.id} style={{ width: GEOMETRY.nodeWidth }} className="shrink-0">
              <StageNode
                stage={stage}
                index={index}
                total={stages.length}
                isSelected={stage.id === selectedId}
                isDrawingSource={drawingFrom === stage.stageCode}
                drawingFrom={drawingFrom}
                onSelect={() => onSelect(stage.id)}
                onMove={onMove}
                onStartDraw={() => onStartDraw(drawingFrom === stage.stageCode ? null : stage.stageCode)}
                onDragStart={() => setDragFrom(index)}
                onDropBefore={(e) => handleDrop(index, 'before', e)}
                onDropAfter={(e) => handleDrop(index, 'after', e)}
              />
            </li>
          ))}
        </ol>

        {/*
          `aria-hidden` on purpose. Every arc drawn here is also a row of text in
          the inspector's "Returns to" list, and an SVG path announced as a path
          adds a second, worse reading of the same fact. The picture is for the
          eye; the list is the accessible copy, and it is editable, which the
          picture is not.
        */}
        {arcs.length > 0 && (
          <svg
            aria-hidden="true"
            focusable="false"
            width={width}
            height={arcHeight + 8}
            className="mt-1 block overflow-visible"
          >
            <defs>
              <marker
                id="designer-arrowhead"
                markerWidth="8"
                markerHeight="8"
                refX="4"
                refY="4"
                orient="auto"
              >
                <path d="M 0 1 L 6 4 L 0 7 z" className="fill-current" />
              </marker>
            </defs>
            {arcs.map((arc) => (
              <path
                key={`${arc.fromCode}-${arc.toCode}`}
                d={arcPath(arc, GEOMETRY)}
                fill="none"
                strokeWidth={1.5}
                markerEnd="url(#designer-arrowhead)"
                className={arc.isBroken ? 'stroke-danger text-danger' : 'stroke-border text-border'}
              />
            ))}
          </svg>
        )}
      </div>

      <p className="mt-2 text-caption text-content-muted">
        {stages.length} stage{stages.length === 1 ? '' : 's'}. Drag a node to reorder, or use the
        move buttons — nothing is saved until you press Save flow.
      </p>
    </section>
  )
}

function StageNode({
  stage,
  index,
  total,
  isSelected,
  isDrawingSource,
  drawingFrom,
  onSelect,
  onMove,
  onStartDraw,
  onDragStart,
  onDropBefore,
  onDropAfter,
}: {
  stage: Stage
  index: number
  total: number
  isSelected: boolean
  isDrawingSource: boolean
  drawingFrom: string | null
  onSelect: () => void
  onMove: (from: number, to: number) => void
  onStartDraw: () => void
  onDragStart: () => void
  onDropBefore: (event: React.DragEvent) => void
  onDropAfter: (event: React.DragEvent) => void
}) {
  return (
    <div
      draggable
      onDragStart={onDragStart}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => (e.nativeEvent.offsetX < GEOMETRY.nodeWidth / 2 ? onDropBefore(e) : onDropAfter(e))}
      className={[
        'flex h-full flex-col gap-2 rounded-card border p-3',
        isSelected ? 'border-primary bg-primary-subtle' : 'border-line bg-surface',
        stage.isDeprecated ? 'opacity-70' : '',
      ].join(' ')}
    >
      <button
        type="button"
        onClick={onSelect}
        aria-pressed={isSelected}
        className="text-left"
        aria-label={`Stage ${index + 1} of ${total}: ${stage.displayName}`}
      >
        <span className="block text-caption text-content-muted">
          {index + 1} · {stage.stageCode}
        </span>
        <span className="block text-body font-medium text-content">{stage.displayName}</span>
      </button>

      <div className="flex flex-wrap gap-1">
        <Chip>{stage.ownerRole}</Chip>
        {/*
          The SLA on the face of the node, not behind a click. It is half of what
          S-30 asks the Admin to set, and a flow whose SLAs are only visible one
          dialog at a time cannot be checked as a flow — which is the reason this
          screen exists beside tab 2's table.
        */}
        <Chip>{stage.slaHours == null ? 'No SLA' : `${stage.slaHours} h`}</Chip>
        {stage.isOptional && <Chip>Optional</Chip>}
        {stage.isDeprecated && <Chip>Deprecated</Chip>}
      </div>

      {stage.canReturnTo.length > 0 && (
        <p className="text-caption text-content-muted">Returns to {stage.canReturnTo.join(', ')}</p>
      )}

      <div className="mt-auto flex flex-wrap gap-1">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={index === 0}
          aria-label={`Move ${stage.displayName} left`}
          onClick={() => onMove(index, index - 1)}
        >
          ←
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={index === total - 1}
          aria-label={`Move ${stage.displayName} right`}
          onClick={() => onMove(index, index + 1)}
        >
          →
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-pressed={isDrawingSource}
          aria-label={
            isDrawingSource
              ? `Cancel drawing a return path from ${stage.displayName}`
              : `Draw a return path from ${stage.displayName}`
          }
          onClick={onStartDraw}
        >
          ↩
        </Button>
      </div>

      {drawingFrom && drawingFrom !== stage.stageCode && (
        <p className="text-caption text-content-muted">
          Set {drawingFrom} → {stage.stageCode} in the inspector.
        </p>
      )}
    </div>
  )
}

/** What there is to drag on. See `stageVocabulary` for why this is a vocabulary. */
function Palette({
  entries,
  onAdd,
}: {
  entries: PaletteEntry[]
  onAdd: (entry: PaletteEntry) => void
}) {
  return (
    <section aria-labelledby="palette-heading" className="space-y-3 rounded-card border border-line p-4">
      <div>
        <h2 id="palette-heading" className="text-h4 text-content">
          Stage palette
        </h2>
        <p className="text-caption text-content-muted">
          Codes your other templates already use. Dropping one creates a new stage here — it does
          not link back to theirs.
        </p>
      </div>

      {entries.length === 0 ? (
        <p className="text-body-sm text-content-muted">
          This template already uses every stage code in the organisation. Add a new one from the
          Stages tab.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {entries.map((entry) => (
            <li
              key={entry.stageCode}
              draggable
              onDragStart={(e) =>
                e.dataTransfer.setData('application/x-edutrack-palette', entry.stageCode)
              }
              className="flex items-center justify-between gap-2 rounded-control border border-line p-2"
            >
              <span>
                <span className="block text-body-sm font-medium text-content">
                  {entry.displayName}
                </span>
                <span className="block text-caption text-content-muted">
                  {entry.stageCode} · on {entry.usedBy.join(', ')}
                </span>
              </span>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                aria-label={`Add ${entry.displayName} to this template`}
                onClick={() => onAdd(entry)}
              >
                Add
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * The right-hand pane: S-30's *"set owner role and SLA per stage"*, plus the
 * editable copy of the arcs.
 *
 * Validation and both mappers are B-040's, imported rather than rewritten —
 * including `formToPatch`, whose omission of an unchanged `stageCode` is the
 * thing standing between an owner-role edit and a 409 on a frozen code.
 */
function Inspector({
  templateId,
  stage,
  stages,
  loaded,
}: {
  templateId: number
  stage: Stage
  stages: Stage[]
  loaded: { stage: Stage; etag: string | null } | null
}) {
  const roles = useRoles({ isActive: true })
  const update = useUpdateStage()

  const [form, setForm] = React.useState<StageFormState>(() => stageToForm(stage))
  const [submitted, setSubmitted] = React.useState(false)

  // Re-seeded from the server's row when it arrives, so an arrow drawn on the
  // canvas shows up ticked here rather than being reverted by the next Save.
  React.useEffect(() => {
    if (loaded) setForm(stageToForm(loaded.stage))
  }, [loaded])

  // The server's answer, never re-derived — B-040's README makes the case: a
  // second copy of the rename rule greys out a field the server would accept.
  const codeEditable = loaded?.stage.isCodeEditable ?? true
  const errors = stageFormErrors(form, { codeEditable })
  const set = <K extends keyof StageFormState>(key: K, value: StageFormState[K]) =>
    setForm((f) => ({ ...f, [key]: value }))

  /*
   * B-040's picker, unchanged — including its handling of a target retired after
   * the arrow was authored, which stays offered and ticked so that the next
   * unrelated save does not quietly clear an arrow nobody touched.
   *
   * It keys off the server's `position`, not this canvas's index, and that is
   * deliberate: a tick patches immediately, so the server validates it against
   * the order it holds rather than the one being dragged.
   *
   * <p>The fallback is **the row from the stage list, never `null`**. `null` means
   * "appended last, so everything qualifies" — right for a stage being created,
   * and wrong here: it would offer every stage on the template, forward ones
   * included, for the round trip it takes the detail read to arrive. Ticking one
   * in that window sends a patch the server refuses. The list row carries the
   * same `position` and it is already in hand.
   */
  const candidates = returnTargetOptions(
    stages,
    loaded?.stage.position ?? stage.position,
    form.canReturnTo,
  )

  const save = async (next: StageFormState) => {
    setSubmitted(true)
    if (Object.keys(stageFormErrors(next, { codeEditable })).length > 0) return
    try {
      await update.mutateAsync({
        templateId,
        stageId: stage.id,
        data: formToPatch(next, loaded?.stage ?? stage),
        etag: loaded?.etag ?? null,
      })
      toast({ title: `${next.displayName} saved` })
    } catch (error) {
      toast({ title: 'That was refused', description: problemDetail(error), variant: 'danger' })
    }
  }

  const toggleReturn = (code: string, on: boolean) => {
    const next = {
      ...form,
      canReturnTo: on
        ? [...form.canReturnTo, code]
        : form.canReturnTo.filter((c) => c !== code),
    }
    setForm(next)
    void save(next)
  }

  return (
    <section aria-labelledby="inspector-heading" className="space-y-4 rounded-card border border-line p-4">
      <div>
        <h2 id="inspector-heading" className="text-h4 text-content">
          {stage.displayName}
        </h2>
        <p className="text-caption text-content-muted">{stage.stageCode}</p>
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="designer-name" className="font-medium text-content">
          Display name
        </label>
        <Input
          id="designer-name"
          value={form.displayName}
          maxLength={50}
          aria-describedby={errors.displayName ? 'designer-name-error' : undefined}
          onChange={(e) => set('displayName', e.target.value)}
        />
        {submitted && errors.displayName && (
          <span id="designer-name-error" className="text-xs text-danger">
            {errors.displayName}
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="designer-owner" className="font-medium text-content">
          Owner role
        </label>
        <select
          id="designer-owner"
          className="h-9 rounded-control border border-border bg-surface px-2 text-sm"
          value={form.ownerRole}
          onChange={(e) => set('ownerRole', e.target.value)}
        >
          <option value="">Pick a role</option>
          {(roles.data ?? []).map((role) => (
            <option key={role.id} value={role.code}>
              {role.name}
            </option>
          ))}
        </select>
        {submitted && errors.ownerRole && (
          <span className="text-xs text-danger">{errors.ownerRole}</span>
        )}
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="designer-sla" className="font-medium text-content">
          Stage SLA (working hours)
        </label>
        <Input
          id="designer-sla"
          value={form.slaHours}
          inputMode="decimal"
          aria-describedby="designer-sla-help"
          onChange={(e) => set('slaHours', e.target.value)}
        />
        <span id="designer-sla-help" className="text-xs text-content-muted">
          Leave empty for no stage SLA. Zero would breach the moment a ticket enters.
        </span>
        {submitted && errors.slaHours && <span className="text-xs text-danger">{errors.slaHours}</span>}
      </div>

      <label className="flex items-center gap-2 text-sm text-content">
        <input
          type="checkbox"
          checked={form.isOptional}
          onChange={(e) => set('isOptional', e.target.checked)}
        />
        Optional — a ticket may skip this stage
      </label>

      <fieldset className="space-y-1">
        <legend className="text-sm font-medium text-content">Returns to</legend>
        {candidates.length === 0 ? (
          <p className="text-caption text-content-muted">
            Nothing above this stage to return to. A return path is a backward move.
          </p>
        ) : (
          candidates.map((candidate) => (
            <label
              key={candidate.id}
              className={
                candidate.isDeprecated
                  ? 'flex items-center gap-2 text-sm text-content-muted'
                  : 'flex items-center gap-2 text-sm text-content'
              }
            >
              <input
                type="checkbox"
                checked={form.canReturnTo.includes(candidate.stageCode)}
                onChange={(e) => toggleReturn(candidate.stageCode, e.target.checked)}
              />
              {candidate.displayName}
              {candidate.isDeprecated && ' — deprecated, clear this'}
            </label>
          ))
        )}
      </fieldset>

      <Button type="button" disabled={update.isPending} onClick={() => save(form)}>
        Save stage
      </Button>
    </section>
  )
}

/**
 * §4A.5's *"versioned by copy, never edited in place"*.
 *
 * There is no version column — B-040 removed the contract's `version` field
 * rather than serve a hard-coded 1 — so a new version is a **new template** whose
 * ribbon is a copy of this one. `copyStagesFromTemplateId` is the route that does
 * it, and the generated client's own note names this designer as where the rule
 * is kept.
 */
function DuplicateDialog({
  templateId,
  templateName,
  onClose,
}: {
  templateId: number
  templateName: string
  onClose: () => void
}) {
  const create = useCreateTemplate()
  const navigate = useNavigate()
  const [name, setName] = React.useState(`${templateName} v2`)

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Duplicate “{templateName}”</ModalTitle>
          <ModalDescription>
            The copy starts as an identical ribbon, including any deprecated stages — a version
            that quietly dropped them would differ from its source in a way nothing records.
            Tickets already running stay on this template.
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-1 px-6 text-sm">
          <label htmlFor="duplicate-name" className="font-medium text-content">
            New template name
          </label>
          <Input
            id="duplicate-name"
            value={name}
            maxLength={80}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <ModalFooter>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={!name.trim() || create.isPending}
            onClick={() =>
              create.mutate(
                { name: name.trim(), copyStagesFromTemplateId: templateId },
                {
                  onSuccess: (created) => {
                    onClose()
                    toast({ title: `${name.trim()} created` })
                    if (created?.id) navigate(`/masters/workflow/designer/${created.id}`)
                  },
                  onError: (error) =>
                    toast({
                      title: 'Could not duplicate',
                      description: problemDetail(error),
                      variant: 'danger',
                    }),
                },
              )
            }
          >
            Duplicate
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

function problemDetail(error: unknown): string {
  const problem = (error as { problem?: { detail?: string; title?: string } }).problem
  return problem?.detail ?? problem?.title ?? 'Reload the page and try again.'
}
