import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { CalendarClock } from 'lucide-react'

import {
  getListReportSchedulesQueryKey,
  useScheduleReport,
} from '@/api/generated/reports/reports'
import type { ReportScheduleRequestCadence, ReportScheduleRequestFormat } from '@/api/generated/model'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

import { filtersFrom, splitAddresses } from './scheduleRequest'

/**
 * A-065 · §7.8's "All reports schedulable by email (daily/weekly/monthly)",
 * opened from the report viewer.
 *
 * <h2>The filters come from the screen, the dates deliberately do not</h2>
 *
 * The dialog posts the viewer's current filters, because "schedule *this*" is
 * what somebody means when they press the button on a report they have just
 * narrowed. It does not post the date range, and the sentence in the dialog
 * says so out loud rather than leaving it to be discovered: a schedule's period
 * comes from its cadence, so a weekly one always covers last week. Silently
 * dropping a filter the user set is the failure this text exists to prevent —
 * the server drops it too, and a screen that implied otherwise would make the
 * first email look wrong.
 *
 * <h2>Recipients must be people who can sign in</h2>
 *
 * The mail carries a link to an authenticated download rather than an
 * attachment, so an address with no EduTrack account can never open it. The
 * server refuses those with a message naming them, which this surfaces
 * verbatim — rewriting it here would be a second copy of a rule that lives on
 * the server.
 */

const CADENCES: { value: ReportScheduleRequestCadence; label: string; covers: string }[] = [
  { value: 'DAILY', label: 'Daily', covers: 'each morning, covering the previous day' },
  { value: 'WEEKLY', label: 'Weekly', covers: 'every Monday, covering the previous week' },
  { value: 'MONTHLY', label: 'Monthly', covers: 'on the 1st, covering the previous month' },
]

const FORMATS: { value: ReportScheduleRequestFormat; label: string }[] = [
  { value: 'xlsx', label: 'Excel' },
  { value: 'csv', label: 'CSV' },
  { value: 'pdf', label: 'PDF' },
]

export function ScheduleReportDialog({
  reportKey,
  reportTitle,
  params,
}: {
  reportKey: string
  reportTitle: string

  
  /** The viewer's current filters. Dates are stripped — see the note above. */
  params: URLSearchParams
}) {
  const [open, setOpen] = useState(false)
  const [cadence, setCadence] = useState<ReportScheduleRequestCadence>('WEEKLY')
  const [format, setFormat] = useState<ReportScheduleRequestFormat>('xlsx')
  const [recipients, setRecipients] = useState('')
  const [error, setError] = useState<string | null>(null)

  const queryClient = useQueryClient()
  const schedule = useScheduleReport({
    mutation: {
      onSuccess: () => {
        // The manage screen is a different route and may already be cached, so
        // it is invalidated rather than left to go stale — somebody who
        // schedules and then navigates there should see what they just made.
        void queryClient.invalidateQueries({ queryKey: getListReportSchedulesQueryKey() })
        setOpen(false)
        setRecipients('')
        setError(null)
      },
      onError: (e: unknown) => setError(messageFrom(e)),
    },
  })

  function submit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    schedule.mutate({
      data: {
        reportKey,
        cadence,
        format,
        recipients: splitAddresses(recipients),
        parameters: filtersFrom(params),
      },
    })
  }

  return (
    <>
      <Button type="button" variant="secondary" size="sm" onClick={() => setOpen(true)}>
        <CalendarClock className="mr-1.5 h-3.5 w-3.5" aria-hidden />
        Schedule by email
      </Button>

      <Modal open={open} onOpenChange={setOpen}>
        <ModalContent aria-label={`Schedule ${reportTitle} by email`}>
          <ModalHeader>
            <ModalTitle>Schedule by email</ModalTitle>
            <ModalDescription>
              {reportTitle} will be run and emailed on your behalf. Every run shows only the rows
              your role allows at the time it runs.
            </ModalDescription>
          </ModalHeader>

          <form onSubmit={submit} className="flex flex-col gap-4">
            <fieldset className="flex flex-col gap-1.5">
              <legend className="mb-1 text-caption font-medium text-content">How often</legend>
              {CADENCES.map((option) => (
                <label key={option.value} className="flex items-start gap-2 text-sm text-content">
                  <input
                    type="radio"
                    name="cadence"
                    value={option.value}
                    checked={cadence === option.value}
                    onChange={() => setCadence(option.value)}
                    className="mt-1"
                  />
                  <span>
                    {option.label}
                    <span className="block text-caption text-content-muted">
                      Sent {option.covers}.
                    </span>
                  </span>
                </label>
              ))}
            </fieldset>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="schedule-recipients" className="text-caption font-medium text-content">
                Send to
              </label>
              <Input
                id="schedule-recipients"
                value={recipients}
                onChange={(e) => setRecipients(e.target.value)}
                placeholder="name@example.com, colleague@example.com"
                aria-describedby="schedule-recipients-help"
                required
              />
              <p id="schedule-recipients-help" className="text-caption text-content-muted">
                Comma separated. Each address must belong to an active EduTrack user — the email
                links to a download that asks the reader to sign in.
              </p>
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="schedule-format" className="text-caption font-medium text-content">
                File format
              </label>
              <select
                id="schedule-format"
                value={format}
                onChange={(e) => setFormat(e.target.value as ReportScheduleRequestFormat)}
                className="h-10 rounded-control border border-border bg-surface px-3 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                {FORMATS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>

            <p className="text-caption text-content-muted">
              The filters on this screen are saved with the schedule. The date range is not — each
              run covers its own period, so a weekly report always covers the week just finished.
            </p>

            {error && (
              <p role="alert" className="text-caption text-danger-text">
                {error}
              </p>
            )}

            <div className="flex justify-end gap-2">
              <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={schedule.isPending}>
                {schedule.isPending ? 'Scheduling…' : 'Schedule'}
              </Button>
            </div>
          </form>
        </ModalContent>
      </Modal>
    </>
  )
}


/**
 * The server's own words where there are any.
 *
 * A 400 from this endpoint is always actionable — an address with no account,
 * a report the role cannot run, too many schedules — and replacing it with
 * "Something went wrong" would throw away the only part the user can act on.
 */
function messageFrom(error: unknown): string {
  // `problem.detail`, per RFC 9457 and CONVENTIONS.md §3 — the shape `ApiError`
  // actually carries. The first version read `error.detail` and `error.message`
  // and got neither: `detail` lives one level down on `problem`, and
  // `ApiError.message` is `problem.title || "HTTP 400"`, so a refusal naming
  // the offending address was shown to the user as "HTTP 400". Caught by the
  // test that asserts the address reaches the screen.
  if (error instanceof ApiError) {
    const detail = error.problem.detail
    if (typeof detail === 'string' && detail.trim() !== '') {
      return detail
    }
  }
  return 'The schedule could not be created.'
}
