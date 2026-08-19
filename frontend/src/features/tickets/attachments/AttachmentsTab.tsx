import * as React from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'

import type { Attachment } from '@/api/generated/model'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { titleCase } from '../stageDisplay'
import { AttachmentGallery } from './AttachmentGallery'
import { serverToItem } from './useTicketAttachments'

/**
 * The Attachments tab — C-060, blueprint §7: "gallery of every file on the
 * ticket: image thumbnails with a lightbox, documents as rows with size and
 * uploader, filterable by client-visible, grouped by cycle and stage."
 *
 * Everything that draws a tile, a chip, a tombstone or opens the lightbox is
 * `AttachmentGallery` — C-026 built exactly that arrangement for the strip
 * above the tabs, and its own header says this tab would reuse it. What this
 * component owns is new: the client-visible toggle and the two-level grouping
 * `AttachmentGallery` has no notion of.
 *
 * ## Grouped by cycle, most recent first; by stage, in the order it happened
 *
 * Same convention `HistoryTab` set for C-059: a ticket reopened three times
 * should not bury this week's files under three cycles of old ones, so only
 * the latest cycle starts open. **Stage has no fixed order to sort by** — the
 * workflow ribbon (C-051) that would know a template's real stage sequence
 * does not exist yet, and `stageDisplay.ts`'s own note already declines to
 * fake one for a one-line caption. So a cycle's stages are ordered by when its
 * first file arrived, which is the order `AttachmentService.list` already
 * hands back (`createdAt asc`) and needs no re-sorting to get.
 */
export function AttachmentsTab({
  attachments,
  isLoading,
  loadError,
  clientVisibleOnly,
  onClientVisibleOnlyChange,
}: {
  attachments: Attachment[]
  isLoading: boolean
  loadError: string | null
  clientVisibleOnly: boolean
  onClientVisibleOnlyChange: (value: boolean) => void
}) {
  const filtered = React.useMemo(
    () => (clientVisibleOnly ? attachments.filter((a) => a.isClientVisible) : attachments),
    [attachments, clientVisibleOnly],
  )
  const groups = React.useMemo(() => groupByCycleAndStage(filtered), [filtered])
  const latestCycle = groups[0]?.cycleNo

  return (
    <div className="flex flex-col gap-4">
      <label className="flex w-fit items-center gap-2 text-caption text-content-muted">
        <input
          type="checkbox"
          checked={clientVisibleOnly}
          onChange={(event) => onClientVisibleOnlyChange(event.target.checked)}
          className="h-4 w-4 rounded-control border-border"
        />
        Client-visible only
      </label>

      {isLoading ? (
        <div className="flex flex-col gap-3" aria-busy="true">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      ) : loadError ? (
        <p role="alert" className="text-caption text-danger-text">
          {loadError}
        </p>
      ) : groups.length === 0 ? (
        <EmptyState
          title={clientVisibleOnly ? 'No client-visible files' : 'No attachments yet'}
          description={
            clientVisibleOnly
              ? 'Nothing on this ticket is flagged client-visible.'
              : 'Files attached to this ticket will appear here, grouped by cycle and stage.'
          }
        />
      ) : (
        groups.map((group) => (
          <CycleGroup key={group.cycleNo} group={group} defaultOpen={group.cycleNo === latestCycle} />
        ))
      )}
    </div>
  )
}

interface StageBucket {
  stageCode: string | null
  live: Attachment[]
  tombstones: Attachment[]
}

interface CycleBucket {
  cycleNo: number
  stages: StageBucket[]
}

/**
 * Undefined `cycleNo` buckets under a labelled 0, the same convention
 * `HistoryTab.groupByCycle` uses — a file is never dropped by grouping just
 * because it predates the cycle column being stamped on every write.
 */
function groupByCycleAndStage(attachments: Attachment[]): CycleBucket[] {
  const byCycle = new Map<number, Map<string | null, StageBucket>>()

  for (const row of attachments) {
    const cycleNo = row.cycleNo ?? 0
    const stageCode = row.stageCode ?? null

    let stages = byCycle.get(cycleNo)
    if (!stages) {
      stages = new Map()
      byCycle.set(cycleNo, stages)
    }

    let bucket = stages.get(stageCode)
    if (!bucket) {
      bucket = { stageCode, live: [], tombstones: [] }
      stages.set(stageCode, bucket)
    }

    if (row.isDeleted) bucket.tombstones.push(row)
    else bucket.live.push(row)
  }

  return [...byCycle.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([cycleNo, stages]) => ({ cycleNo, stages: [...stages.values()] }))
}

function CycleGroup({ group, defaultOpen }: { group: CycleBucket; defaultOpen: boolean }) {
  const [open, setOpen] = React.useState(defaultOpen)
  const headingId = `attachments-cycle-${group.cycleNo}-heading`
  const total = group.stages.reduce((sum, s) => sum + s.live.length + s.tombstones.length, 0)

  return (
    <section className="rounded-card border border-border bg-surface" aria-labelledby={headingId}>
      <button
        type="button"
        id={headingId}
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
        className={cn(
          'flex w-full items-center gap-2 rounded-card px-3 py-2 text-left text-body font-medium text-content',
          'hover:bg-subtle focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
        )}
      >
        {open ? (
          <ChevronDown aria-hidden="true" className="h-4 w-4 shrink-0" />
        ) : (
          <ChevronRight aria-hidden="true" className="h-4 w-4 shrink-0" />
        )}
        {group.cycleNo === 0 ? 'Before cycle 1' : `Cycle ${group.cycleNo}`}
        <span className="ml-auto text-caption font-normal text-content-muted">
          {total} {total === 1 ? 'file' : 'files'}
        </span>
      </button>

      {open && (
        <div className="flex flex-col gap-3 border-t border-border px-3 py-3">
          {group.stages.map((stage) => (
            <StageGroup key={stage.stageCode ?? '__none__'} stage={stage} />
          ))}
        </div>
      )}
    </section>
  )
}

function StageGroup({ stage }: { stage: StageBucket }) {
  const label = stage.stageCode ? titleCase(stage.stageCode) : 'No stage recorded'
  const items = React.useMemo(() => stage.live.map(serverToItem), [stage.live])
  const uploaderNames = React.useMemo(() => {
    const names: Record<string, string> = {}
    for (const row of stage.live) {
      if (row.id != null && row.uploadedBy?.displayName) names[String(row.id)] = row.uploadedBy.displayName
    }
    return names
  }, [stage.live])

  return (
    <div className="flex flex-col gap-1.5">
      <h3 className="text-caption font-medium text-content-muted">{label}</h3>
      <AttachmentGallery items={items} tombstones={stage.tombstones} uploaderNames={uploaderNames} />
    </div>
  )
}
