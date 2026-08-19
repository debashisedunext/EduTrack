import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowLeft, Download } from 'lucide-react'

import {
  getListReportSchedulesQueryKey,
  useCancelReportSchedule,
  useListReportSchedules,
} from '@/api/generated/reports/reports'
import type { ReportSchedule, ReportScheduleRun } from '@/api/generated/model'
import { BASE, getAccessToken } from '@/api/http'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * A-065 · the screen the scheduled-report email links to.
 *
 * <h2>Why this exists rather than a link straight to the file</h2>
 *
 * The mail carries no attachment and no signed URL — a link that downloaded on
 * sight would be a credential sitting in a mail archive, valid for anybody it
 * was forwarded to, long after the recipient's access was taken away. So the
 * mail points here, the reader signs in, and the download is authorised at the
 * moment they press it.
 *
 * That makes this screen the whole management surface as well: what is
 * scheduled, when it next runs, what each run produced, and why one failed.
 *
 * <h2>Cancelled schedules stay</h2>
 *
 * Greyed rather than removed. "Why did this stop arriving" is the question this
 * screen exists to answer, and a row that vanishes on cancel answers it with
 * silence — the same argument the reports hub makes for listing unbuilt
 * reports instead of hiding them.
 */
export function ScheduledReportsPage() {
  const schedules = useListReportSchedules()
  const rows = schedules.data?.data ?? []

  return (
    <div className="p-6">
      <Link
        to="/reports"
        className="mb-4 inline-flex items-center gap-1.5 text-caption text-content-muted hover:text-content"
      >
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
        Reports
      </Link>

      <header className="mb-4">
        <h1 className="text-h2 font-semibold text-content">Scheduled reports</h1>
        <p className="mt-1 text-sm text-content-muted">
          Reports emailed on a schedule. Each run shows only the rows your role allows at the time
          it runs, so what arrives narrows if your access does.
        </p>
      </header>

      {schedules.isLoading && <p className="text-sm text-content-muted">Loading…</p>}

      {schedules.isError && (
        <p role="alert" className="text-sm text-danger-text">
          Your scheduled reports could not be loaded. Refresh to try again.
        </p>
      )}

      {!schedules.isLoading && !schedules.isError && rows.length === 0 && (
        <EmptyState
          title="Nothing scheduled"
          description="Open a report, set the filters you want, and choose “Schedule by email”."
        />
      )}

      <ul className="flex flex-col gap-3">
        {rows.map((schedule) => (
          <ScheduleCard key={schedule.id} schedule={schedule} />
        ))}
      </ul>
    </div>
  )
}

function ScheduleCard({ schedule }: { schedule: ReportSchedule }) {
  const queryClient = useQueryClient()
  const cancel = useCancelReportSchedule({
    mutation: {
      onSuccess: () =>
        void queryClient.invalidateQueries({ queryKey: getListReportSchedulesQueryKey() }),
    },
  })

  return (
    <li
      className={`rounded-card border border-border bg-surface p-4 ${
        schedule.active ? '' : 'opacity-60'
      }`}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-body font-medium text-content">
            {schedule.reportTitle ?? schedule.reportKey}
          </h2>
          <p className="mt-0.5 text-caption text-content-muted">
            {cadenceLabel(schedule.cadence)} · {schedule.format.toUpperCase()} ·{' '}
            {schedule.recipients.length} recipient{schedule.recipients.length === 1 ? '' : 's'}
          </p>
          <p className="mt-0.5 text-caption text-content-muted">
            {schedule.active ? (
              <>Next run {formatDateTime(schedule.nextRunAt)}</>
            ) : (
              // Stated rather than implied by the greying, which is a visual
              // signal a screen reader does not get and a colour-blind reader
              // may not either.
              <>Cancelled — no further runs</>
            )}
          </p>

          {/*
            Somebody else's schedule that you are on. Named rather than left to
            be inferred from the missing Cancel button: "why can I not stop
            this" has an answer, and it is a person to ask.
          */}
          {!schedule.ownedByMe && (
            <p className="mt-0.5 text-caption text-content-muted">
              Sent to you by {schedule.createdByName ?? 'another user'} — ask them to change or
              stop it.
            </p>
          )}
        </div>

        {schedule.active && schedule.ownedByMe && (
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={cancel.isPending}
            onClick={() => cancel.mutate({ id: schedule.id })}
          >
            {cancel.isPending ? 'Cancelling…' : 'Cancel'}
          </Button>
        )}
      </div>

      {schedule.recentRuns && schedule.recentRuns.length > 0 && (
        <ul className="mt-3 flex flex-col gap-2 border-t border-border pt-3">
          {schedule.recentRuns.map((run) => (
            <RunRow key={run.id} scheduleId={schedule.id} run={run} />
          ))}
        </ul>
      )}
    </li>
  )
}

function RunRow({ scheduleId, run }: { scheduleId: number; run: ReportScheduleRun }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /*
    Fetched rather than linked, for ReportExportButtons' reason: the access
    token lives in memory and is attached by `http()`, so a browser-initiated
    navigation carries no Authorization header and the server answers 401 —
    which the browser then saves as a file that opens as gibberish.
  */
  async function download() {
    setBusy(true)
    setError(null)
    try {
      const token = getAccessToken()
      const response = await fetch(
        `${BASE}/reports/schedules/${scheduleId}/runs/${run.id}/download`,
        { headers: token ? { Authorization: `Bearer ${token}` } : {} },
      )
      if (!response.ok) {
        throw new Error(
          response.status === 404
            ? 'This file is no longer available.'
            : `The download failed (${response.status}).`,
        )
      }
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filenameFrom(response.headers.get('Content-Disposition'))
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The download failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="flex flex-wrap items-center justify-between gap-2 text-caption">
      <div className="text-content-muted">
        <span className="text-content">{formatPeriod(run.periodFrom, run.periodTo)}</span>
        {run.status === 'SUCCEEDED' && run.rowCount != null && <> · {run.rowCount} rows</>}
        {/*
          The scope is per run, not per schedule: the owner's role can change
          between two runs, and this line is where that becomes visible.
        */}
        {run.appliedScope && <> · {run.appliedScope}</>}
      </div>

      <div className="flex items-center gap-2">
        {run.status === 'FAILED' && (
          <span className="inline-flex items-center gap-1 text-danger-text">
            <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden />
            {run.errorText ?? 'This run failed.'}
          </span>
        )}
        {run.downloadable && (
          <button
            type="button"
            onClick={download}
            disabled={busy}
            className="inline-flex items-center gap-1.5 rounded-control border border-border px-2 py-1 font-medium text-content transition-colors hover:bg-subtle disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <Download className="h-3.5 w-3.5" aria-hidden />
            {busy ? 'Preparing…' : 'Download'}
          </button>
        )}
        {error && (
          <span role="alert" className="text-danger-text">
            {error}
          </span>
        )}
      </div>
    </li>
  )
}

function cadenceLabel(cadence: string): string {
  return cadence.charAt(0) + cadence.slice(1).toLowerCase()
}

/**
 * "1–7 Aug 2026", or one date when the period is a single day.
 *
 * The period rather than the run timestamp is what leads the row, because it is
 * what somebody scanning a list of near-identical runs is actually looking for.
 */
function formatPeriod(from: string, to: string): string {
  if (from === to) {
    return formatDate(from)
  }
  return `${formatDate(from)} – ${formatDate(to)}`
}

function formatDate(iso: string): string {
  const date = new Date(`${iso}T00:00:00Z`)
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
}

/**
 * Storage is UTC (CLAUDE.md) and this is the presentation layer, so it is shown
 * in the reader's own zone — "next run 06:00" means nothing if it is 06:00
 * somewhere else.
 */
function formatDateTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

function filenameFrom(disposition: string | null): string {
  const match = disposition?.match(/filename="?([^"';]+)"?/i)
  return match?.[1] ?? 'report'
}
