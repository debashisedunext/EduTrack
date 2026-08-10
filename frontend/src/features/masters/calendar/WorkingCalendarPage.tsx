import * as React from 'react'

import { useGetWorkingCalendar } from '@/api/generated/masters/masters'
import { ApiError } from '@/api/http'
import type { Holiday } from '@/api/generated/model/holiday'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import { WeeklyOffPicker } from './WeeklyOffPicker'
import {
  useCreateHoliday,
  useCreateLeave,
  useDeleteHoliday,
  useDeleteLeave,
  useResourceLeaves,
  useUpdateWorkingWeek,
  useWorkingWeek,
} from './calendarQueries'
import {
  DAY_ABBREVIATIONS,
  toTimeInput,
  validateWorkingWeek,
  workingDayLength,
  type IsoDay,
} from './workingWeek'

/**
 * S-14 Working Calendar & Holiday Master — B-023.
 *
 * Three sections, because S-14 is three things that feed one answer: the
 * working week, org holidays, and per-resource leave. Every SLA, duration and
 * utilisation figure in the system is computed against what this screen sets,
 * which is why the working week is the first thing on it and why saving it
 * says so.
 */
export function WorkingCalendarPage() {
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-8 p-6">
      <header>
        <h1 className="text-2xl font-semibold text-slate-900">Working calendar</h1>
        <p className="mt-1 text-sm text-slate-600">
          Weekends, org holidays and resource leave. Every SLA and duration in EduTrack is
          measured against this — a Friday 18:00 ticket with a four-hour target must not
          breach on Saturday morning.
        </p>
      </header>

      <WorkingWeekSection />
      <HolidaySection />
      <LeaveSection />
    </div>
  )
}

// ── the working week ────────────────────────────────────────────────────────

function WorkingWeekSection() {
  const { data, isPending, isError } = useWorkingWeek()
  const update = useUpdateWorkingWeek()

  const [weeklyOff, setWeeklyOff] = React.useState<IsoDay[]>([])
  const [start, setStart] = React.useState('')
  const [end, setEnd] = React.useState('')
  const [dirty, setDirty] = React.useState(false)

  // Seed the form from the server once, and again after a save — but never
  // over the top of an edit in progress.
  React.useEffect(() => {
    if (data && !dirty) {
      setWeeklyOff([...data.week.weeklyOff] as IsoDay[])
      setStart(toTimeInput(data.week.workDayStart))
      setEnd(toTimeInput(data.week.workDayEnd))
    }
  }, [data, dirty])

  const error = React.useMemo(
    () => validateWorkingWeek({ weeklyOff, workDayStart: start, workDayEnd: end }),
    [weeklyOff, start, end],
  )

  if (isPending) return <Section title="The working week"><Skeleton className="h-24 w-full" /></Section>
  if (isError || !data) {
    return (
      <Section title="The working week">
        <p className="text-sm text-red-700">The working week could not be loaded.</p>
      </Section>
    )
  }

  const onSave = () => {
    if (error) return
    update.mutate(
      {
        data: {
          weeklyOff,
          workDayStart: start,
          workDayEnd: end,
          timezone: data.week.timezone,
        },
        etag: data.etag,
      },
      {
        onSuccess: () => {
          setDirty(false)
          toast({
            title: 'Working week saved',
            description: 'New SLA calculations use it from now on. Figures already recorded do not change.',
          })
        },
        onError: (e: ApiError) => {
          // 412 is the case worth naming. Anything else is a generic failure;
          // this one tells the user precisely what to do about it.
          const stale = e.status === 412
          toast({
            variant: 'danger',
            title: stale ? 'Somebody else changed the working week' : 'Could not save the working week',
            description: stale
              ? 'Reload the page and reapply your change, so their edit is not lost.'
              : e.problem.detail ?? 'Please try again.',
          })
        },
      },
    )
  }

  return (
    <Section
      title="The working week"
      description={`Days off, and the hours a working day covers in ${data.week.timezone}.`}
    >
      <div className="flex flex-col gap-4">
        <div>
          <span className="mb-2 block text-sm font-medium text-slate-800">Non-working days</span>
          <WeeklyOffPicker
            value={weeklyOff}
            onChange={(days) => {
              setWeeklyOff(days)
              setDirty(true)
            }}
            disabled={update.isPending}
            errorId={error ? 'working-week-error' : undefined}
          />
        </div>

        <div className="flex flex-wrap items-end gap-4">
          <label className="flex flex-col gap-1 text-sm font-medium text-slate-800">
            Day starts
            <Input
              type="time"
              value={start}
              aria-label="Working day starts"
              onChange={(e) => {
                setStart(e.target.value)
                setDirty(true)
              }}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm font-medium text-slate-800">
            Day ends
            <Input
              type="time"
              value={end}
              aria-label="Working day ends"
              onChange={(e) => {
                setEnd(e.target.value)
                setDirty(true)
              }}
            />
          </label>
          <p className="pb-2 text-sm text-slate-600">
            {workingDayLength(start, end)} per working day
          </p>
        </div>

        {error && (
          <p id="working-week-error" role="alert" className="text-sm text-red-700">
            {error}
          </p>
        )}

        <div>
          <Button onClick={onSave} disabled={!!error || !dirty || update.isPending}>
            {update.isPending ? 'Saving…' : 'Save working week'}
          </Button>
        </div>
      </div>
    </Section>
  )
}

// ── holidays ────────────────────────────────────────────────────────────────

function HolidaySection() {
  const { data, isPending } = useGetWorkingCalendar()
  const create = useCreateHoliday()
  const remove = useDeleteHoliday()

  const [date, setDate] = React.useState('')
  const [name, setName] = React.useState('')
  const [isRecurring, setIsRecurring] = React.useState(false)

  const holidays = (data?.data?.holidays ?? []) as Holiday[]

  const onAdd = (e: React.FormEvent) => {
    e.preventDefault()
    if (!date || !name.trim()) return
    create.mutate(
      { date, name: name.trim(), isRecurring },
      {
        onSuccess: () => {
          setDate('')
          setName('')
          setIsRecurring(false)
          toast({ title: 'Holiday added' })
        },
        onError: (err: ApiError) =>
          toast({
            variant: 'danger',
            title: err.status === 409 ? 'That day is already a holiday' : 'Could not add the holiday',
            description: err.problem.detail ?? undefined,
          }),
      },
    )
  }

  return (
    <Section
      title="Org holidays"
      description="A recurring holiday is stored once and applies every year — do not add it again next year."
    >
      <form onSubmit={onAdd} className="mb-4 flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm font-medium text-slate-800">
          Date
          <Input type="date" value={date} aria-label="Holiday date" onChange={(e) => setDate(e.target.value)} />
        </label>
        <label className="flex min-w-52 flex-1 flex-col gap-1 text-sm font-medium text-slate-800">
          Name
          <Input
            value={name}
            aria-label="Holiday name"
            placeholder="Independence Day"
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label className="flex items-center gap-2 pb-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={isRecurring}
            onChange={(e) => setIsRecurring(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300"
          />
          Repeats annually
        </label>
        <Button type="submit" disabled={!date || !name.trim() || create.isPending}>
          Add holiday
        </Button>
      </form>

      {isPending ? (
        <Skeleton className="h-20 w-full" />
      ) : holidays.length === 0 ? (
        <p className="text-sm text-slate-600">No holidays yet. Weekends still apply.</p>
      ) : (
        <ul className="divide-y divide-slate-200 rounded-md border border-slate-200">
          {holidays.map((holiday) => (
            <li key={holiday.id} className="flex items-center justify-between gap-3 px-3 py-2">
              <span className="text-sm text-slate-900">
                <span className="font-medium">{holiday.date}</span>{' '}
                <span className="text-slate-700">{holiday.name}</span>
                {holiday.isRecurring && (
                  <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">
                    every year
                  </span>
                )}
              </span>
              <Button
                variant="ghost"
                aria-label={`Remove ${holiday.name}`}
                onClick={() =>
                  remove.mutate(holiday.id, {
                    onSuccess: () => toast({ title: 'Holiday removed' }),
                  })
                }
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

// ── resource leave ──────────────────────────────────────────────────────────

function LeaveSection() {
  const { data, isPending } = useResourceLeaves()
  const create = useCreateLeave()
  const remove = useDeleteLeave()

  const [userId, setUserId] = React.useState('')
  const [from, setFrom] = React.useState('')
  const [to, setTo] = React.useState('')

  const leaves = data?.data ?? []

  const onAdd = (e: React.FormEvent) => {
    e.preventDefault()
    if (!userId || !from || !to) return
    create.mutate(
      { userId: Number(userId), startDate: from, endDate: to },
      {
        onSuccess: () => {
          setUserId('')
          setFrom('')
          setTo('')
          toast({ title: 'Leave recorded' })
        },
        onError: (err: ApiError) =>
          toast({
            variant: 'danger',
            title: 'Could not record the leave',
            description: err.problem.detail ?? undefined,
          }),
      },
    )
  }

  return (
    <Section
      title="Resource leave"
      description="Approved leave stops the SLA clock for that resource. A pending request does not."
    >
      <form onSubmit={onAdd} className="mb-4 flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm font-medium text-slate-800">
          Resource ID
          <Input
            type="number"
            value={userId}
            aria-label="Resource ID"
            onChange={(e) => setUserId(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-slate-800">
          From
          <Input type="date" value={from} aria-label="Leave starts" onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-slate-800">
          To
          <Input type="date" value={to} aria-label="Leave ends" onChange={(e) => setTo(e.target.value)} />
        </label>
        <Button type="submit" disabled={!userId || !from || !to || create.isPending}>
          Record leave
        </Button>
      </form>

      {isPending ? (
        <Skeleton className="h-16 w-full" />
      ) : leaves.length === 0 ? (
        <p className="text-sm text-slate-600">No leave recorded.</p>
      ) : (
        <ul className="divide-y divide-slate-200 rounded-md border border-slate-200">
          {leaves.map((leave) => (
            <li key={leave.id} className="flex items-center justify-between gap-3 px-3 py-2">
              <span className="text-sm text-slate-900">
                Resource {leave.userId} · {leave.startDate} → {leave.endDate}
                {leave.isHalfDay && <span className="ml-2 text-xs text-slate-600">half day</span>}
                <span className="ml-2 text-xs text-slate-600">{leave.status}</span>
              </span>
              <Button
                variant="ghost"
                aria-label={`Remove leave for resource ${leave.userId}`}
                onClick={() => remove.mutate(leave.id)}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

// ── layout ──────────────────────────────────────────────────────────────────

function Section({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: React.ReactNode
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5">
      <h2 className="text-lg font-medium text-slate-900">{title}</h2>
      {description && <p className="mb-4 mt-1 text-sm text-slate-600">{description}</p>}
      {!description && <div className="mb-4" />}
      {children}
    </section>
  )
}

/** Re-exported for the sidebar's summary chip. */
export { DAY_ABBREVIATIONS }
