import type { Meta, StoryObj } from '@storybook/react-vite'

import type { JourneyStep } from './types'
import { JourneyRibbonSegment } from './JourneyRibbonSegment'

const meta: Meta<typeof JourneyRibbonSegment> = {
  title: 'Onboarding/JourneyRibbonSegment',
  component: JourneyRibbonSegment,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'C-109 · one step tile of the onboarding journey ribbon — Onboarding-Module-Plan.md §9 (OB-05). ' +
          'Built fresh, not shared with `components/ribbon/RibbonSegment` — see this directory\'s README.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof JourneyRibbonSegment>

function step(over: Partial<JourneyStep> = {}): JourneyStep {
  return {
    id: 's3',
    seqNo: 3,
    name: 'Data migration',
    status: 'CURRENT',
    owner: { displayName: 'Priya Nair' },
    ownerRole: 'STEP_OWNER',
    tatDays: 8,
    tatPercent: 40,
    dependsOnSeqNo: 2,
    ...over,
  }
}

export const Pending: Story = {
  args: { step: step({ status: 'PENDING', tatPercent: null, owner: null, ownerRole: 'STEP_OWNER' }) },
}

export const InProgressOnTime: Story = {
  args: { step: step({ status: 'CURRENT', tatPercent: 40 }) },
}

export const InProgressAmber: Story = {
  args: { step: step({ status: 'CURRENT', tatPercent: 82 }) },
}

export const InProgressBreached: Story = {
  args: { step: step({ status: 'CURRENT', tatPercent: 130 }) },
}

export const DoneOnTime: Story = {
  args: {
    step: step({ status: 'DONE', tatPercent: null, startedOn: '2026-08-01', finishedOn: '2026-08-05', closed: null }),
  },
}

export const DoneEarly: Story = {
  args: {
    step: step({
      status: 'DONE', tatPercent: null, startedOn: '2026-08-01', finishedOn: '2026-08-03', closed: 'early',
    }),
  },
}

export const DoneLate: Story = {
  args: {
    step: step({
      status: 'DONE', tatPercent: null, startedOn: '2026-08-01', finishedOn: '2026-08-12', closed: 'late',
    }),
  },
}

export const WaitingOnClient: Story = {
  args: { step: step({ status: 'WAITING', tatPercent: 55 }) },
}

export const Blocked: Story = {
  args: { step: step({ status: 'BLOCKED', tatPercent: 60, note: 'Awaiting vendor SFTP credentials' }) },
}

export const BlockedAndBreached: Story = {
  args: { step: step({ status: 'BLOCKED', tatPercent: 145, note: 'Awaiting vendor SFTP credentials' }) },
}

export const ParallelStep: Story = {
  args: { step: step({ dependsOnSeqNo: null }) },
}

export const WithSubTaskGate: Story = {
  args: { step: step({ status: 'CURRENT', subTasksAnswered: 3, subTasksTotal: 5 }) },
}

/** No `onSelect` — CP-03's read-only client-portal rendering: focusable and
 * announced, nothing to activate. */
export const ReadOnly: Story = {
  args: { step: step() },
}

export const Interactive: Story = {
  args: {
    step: step(),
    onSelect: (selected) => alert(`Selected step ${selected.seqNo}: ${selected.name}`),
  },
}
