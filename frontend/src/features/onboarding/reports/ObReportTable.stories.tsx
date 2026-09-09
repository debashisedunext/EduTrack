import type { Meta, StoryObj } from '@storybook/react-vite'

import { ObReportTable } from './ObReportTable'

/**
 * B-122 · the states of OB-10's table that are worth looking at rather than
 * asserting.
 *
 * <p>All three are about the same thing: a cell whose value is *absent* must
 * not read as a cell whose value is zero. On a reporting screen those are
 * "nothing could be measured" and "nobody managed it", and the difference is
 * the whole reason the TAT report carries two denominators.
 */
const meta = {
  title: 'Onboarding/ObReportTable',
  component: ObReportTable,
  parameters: { layout: 'padded' },
} satisfies Meta<typeof ObReportTable>

export default meta
type Story = StoryObj<typeof meta>

/** The stuck-and-aging shape: a health chip, a working-hours duration, a date. */
export const StuckAndAging: Story = {
  args: {
    columns: [
      { key: 'client', label: 'Client', type: 'string' },
      { key: 'service', label: 'Service', type: 'string' },
      { key: 'rag', label: 'Health', type: 'rag' },
      { key: 'state', label: 'State', type: 'string' },
      { key: 'waitingSince', label: 'In this state since', type: 'date' },
      { key: 'overdueBy', label: 'Overdue by', type: 'duration' },
      { key: 'reason', label: 'Reason', type: 'string' },
    ],
    rows: [
      {
        client: 'Horizon Group',
        service: 'Data migration',
        rag: 'RED',
        state: 'Blocked',
        waitingSince: '2026-08-28',
        overdueBy: '18.50',
        reason: 'AWAITING_DATA — Client has not sent the fee heads',
      },
      {
        client: 'Crestwood Public School',
        service: 'Branding & configuration',
        rag: 'AMBER',
        state: 'Waiting on client',
        waitingSince: '2026-09-02',
        overdueBy: null,
        reason: 'Waiting on client input',
      },
      {
        // Nothing to colour, and nothing overdue. Two em dashes rather than a
        // grey chip and a zero — the first would read as a fourth health state
        // and the second as a deadline that passed this instant.
        client: 'Meridian Institute',
        service: 'Kick-off',
        rag: null,
        state: 'Not started',
        waitingSince: null,
        overdueBy: null,
        reason: null,
      },
    ],
  },
}

/**
 * The TAT report's two denominators. `measured` under `completed` is what lets
 * a reader see that a percentage rests on very little — and the null row is
 * what the whole table looks like until C-105's clock starts filling
 * `due_at`.
 */
export const TatComplianceWithUnmeasurableRows: Story = {
  args: {
    columns: [
      { key: 'service', label: 'Service', type: 'string' },
      { key: 'owner', label: 'Implementor', type: 'string' },
      { key: 'completed', label: 'Completed', type: 'number' },
      { key: 'measured', label: 'With a TAT', type: 'number' },
      { key: 'onTime', label: 'On time', type: 'number' },
      { key: 'onTimePct', label: 'On time', type: 'percent' },
    ],
    rows: [
      { service: 'Data migration', owner: 'Meera Nair', completed: 10, measured: 10, onTime: 5, onTimePct: 50 },
      { service: 'Kick-off', owner: 'Ravi Kumar', completed: 12, measured: 12, onTime: 11, onTimePct: 92 },
      { service: 'UAT', owner: 'Unassigned', completed: 4, measured: 0, onTime: 0, onTimePct: null },
    ],
  },
}

/** A wide table scrolls inside its own container rather than moving the page. */
export const Empty: Story = {
  args: {
    columns: [
      { key: 'product', label: 'Product', type: 'string' },
      { key: 'journeys', label: 'Journeys here', type: 'number' },
    ],
    rows: [],
  },
}
