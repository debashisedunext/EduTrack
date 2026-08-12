import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { format, parseISO } from 'date-fns'

import type { PlannedCloseDatePreview } from '@/api/generated/model/plannedCloseDatePreview'
import { SlaSource } from '@/api/generated/model/slaSource'

import { SlaPreview } from './SlaPreview'

/**
 * C-012 · the four things the preview says, and the one it must never say
 * silently.
 *
 * A blank where a date should be is the failure mode worth guarding: `null`
 * takes the ticket out of the breach sweep, the delayed KPI and Due Today, and
 * an empty field reads as "not computed yet".
 */

const RESOLVED: PlannedCloseDatePreview = {
  from: '2026-08-14T17:30:00.000Z',
  plannedCloseDate: '2026-08-17T12:30:00.000Z',
  firstResponseDue: '2026-08-17T10:30:00.000Z',
  responseHrs: 2,
  resolutionHrs: 8,
  source: SlaSource.PROJECT_TASK_TYPE,
  slaPolicyId: 5,
}

const state = (over: Partial<Parameters<typeof SlaPreview>[0]> = {}) => ({
  preview: RESOLVED,
  isPending: false,
  isError: false,
  isReady: true,
  ...over,
})

describe('SlaPreview', () => {
  it('asks for the two inputs it needs before it claims anything', () => {
    render(<SlaPreview {...state({ isReady: false, preview: null })} />)

    expect(screen.getByText(/pick a project and a priority/i)).toBeInTheDocument()
  })

  /**
   * The expected string is formatted rather than written out, because storage
   * is UTC and the presentation layer is the *reader's* timezone (CLAUDE.md).
   * A hardcoded `17 Aug 2026, 12:30` passes only on a machine running in UTC —
   * on the team's own machines it reads 18:00, and the test would fail for a
   * screen that is behaving exactly as specified.
   */
  it('shows the date, the target in working hours, and which rung produced it', () => {
    render(<SlaPreview {...state()} />)

    const local = format(parseISO(RESOLVED.plannedCloseDate as string), 'd MMM yyyy, HH:mm')
    expect(screen.getByText(local)).toBeInTheDocument()
    expect(screen.getByText(/8 working hours/)).toBeInTheDocument()
    expect(screen.getByText(/this project's policy for this task type and priority/)).toBeInTheDocument()
  })

  /**
   * The calendar is the reason the number is not "eight hours from now", and a
   * user who does not know that reads the date as wrong rather than as adjusted.
   */
  it('says the working calendar is what moved the date', () => {
    render(<SlaPreview {...state()} />)

    expect(screen.getByText(/weekends, holidays and approved leave/i)).toBeInTheDocument()
  })

  it('shows the response target only when the resolution carried one', () => {
    const { rerender } = render(<SlaPreview {...state()} />)
    expect(screen.getByText(/first response due/i)).toBeInTheDocument()

    rerender(
      <SlaPreview
        {...state({
          preview: { ...RESOLVED, firstResponseDue: null, responseHrs: null, source: SlaSource.PRIORITY_DEFAULT },
        })}
      />,
    )
    expect(screen.queryByText(/first response due/i)).not.toBeInTheDocument()
  })

  it('singularises one hour', () => {
    render(<SlaPreview {...state({ preview: { ...RESOLVED, resolutionHrs: 1 } })} />)

    expect(screen.getByText(/1 working hour[^s]/)).toBeInTheDocument()
  })

  /**
   * Not a blank, and not a dash. `NONE` means the ticket will carry no planned
   * close date at all, and the consequence — invisible to every delay report —
   * is the part the user has to be told.
   */
  it('warns, rather than showing nothing, when no SLA target covers the ticket', () => {
    render(
      <SlaPreview
        {...state({
          preview: { ...RESOLVED, plannedCloseDate: null, firstResponseDue: null, source: SlaSource.NONE },
        })}
      />,
    )

    expect(screen.getByText(/no planned close date/i)).toBeInTheDocument()
    expect(screen.getByText(/delay reports/i)).toBeInTheDocument()
  })

  it('degrades to a non-blocking note when the preview could not be reached', () => {
    render(<SlaPreview {...state({ isError: true, preview: null })} />)

    expect(screen.getByText(/still computed on save/i)).toBeInTheDocument()
  })

  it('announces politely, so a priority change does not interrupt a screen reader', () => {
    render(<SlaPreview {...state()} />)

    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })

  describe('when a PM has typed their own date', () => {
    it('reframes the computed date as the alternative, not the answer', () => {
      render(<SlaPreview {...state({ overrideValue: '2026-09-01T10:00' })} />)

      expect(screen.getByText(/the sla would give/i)).toBeInTheDocument()
      expect(screen.getByText(/your date will be used instead/i)).toBeInTheDocument()
    })

    /**
     * The value handed back is a `datetime-local` string built from **local**
     * components. Slicing the ISO instant instead would hand the input a UTC
     * wall-clock reading and shift what the user sees by their offset.
     */
    it('hands back a datetime-local value the input can hold', () => {
      const onUseComputed = vi.fn()
      render(<SlaPreview {...state({ overrideValue: '2026-09-01T10:00', onUseComputed })} />)

      fireEvent.click(screen.getByRole('button', { name: /use the computed date/i }))

      expect(onUseComputed).toHaveBeenCalledTimes(1)
      expect(onUseComputed.mock.calls[0][0]).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    })

    it('offers no reset button to someone who cannot override the date', () => {
      render(<SlaPreview {...state({ overrideValue: '' })} />)

      expect(screen.queryByRole('button', { name: /use the computed date/i })).not.toBeInTheDocument()
    })
  })
})
