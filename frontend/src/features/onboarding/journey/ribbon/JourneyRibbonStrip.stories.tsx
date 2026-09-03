import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'

import type { JourneyStep } from './types'
import { JourneyRibbonStrip } from './JourneyRibbonStrip'

const meta: Meta<typeof JourneyRibbonStrip> = {
  title: 'Onboarding/JourneyRibbonStrip',
  component: JourneyRibbonStrip,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'C-109 · lays the journey\'s steps out in order. The step panel, the journey accordion around it ' +
          'and the prerequisites strip above it are all C-110 (OB-05) — this is the ribbon alone.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof JourneyRibbonStrip>

function step(over: Partial<JourneyStep>): JourneyStep {
  return {
    id: 's', seqNo: 1, name: 'Step', status: 'PENDING', tatDays: 1, tatPercent: null, ...over,
  }
}

/** The seeded "Standard SaaS Onboarding" default template (§2) — 8 steps,
 * mid-journey, one dependency-free pair running in parallel. */
export const StandardSaaSOnboarding: Story = {
  args: {
    steps: [
      step({ id: 's1', seqNo: 1, name: 'Kickoff call', status: 'DONE', startedOn: '2026-08-01', finishedOn: '2026-08-01', closed: null, tatDays: 1 }),
      step({ id: 's2', seqNo: 2, name: 'Requirements confirmation', status: 'DONE', startedOn: '2026-08-02', finishedOn: '2026-08-04', closed: 'early', tatDays: 3, dependsOnSeqNo: 1 }),
      step({
        id: 's3', seqNo: 3, name: 'Account & environment setup', status: 'CURRENT', tatDays: 5, tatPercent: 45,
        owner: { displayName: 'Priya Nair' }, ownerRole: 'STEP_OWNER', dependsOnSeqNo: 2,
      }),
      step({
        id: 's4', seqNo: 4, name: 'Data migration', status: 'CURRENT', tatDays: 8, tatPercent: 88,
        owner: { displayName: 'Kavya Sharma' }, ownerRole: 'STEP_OWNER', dependsOnSeqNo: 2,
      }),
      step({ id: 's5', seqNo: 5, name: 'Configuration & branding', status: 'PENDING', tatDays: 6, dependsOnSeqNo: 3 }),
      step({ id: 's6', seqNo: 6, name: 'Admin & user training', status: 'PENDING', tatDays: 4, dependsOnSeqNo: 5 }),
      step({ id: 's7', seqNo: 7, name: 'UAT & issue closure', status: 'PENDING', tatDays: 5, dependsOnSeqNo: 6 }),
      step({ id: 's8', seqNo: 8, name: 'Go-live sign-off', status: 'PENDING', tatDays: 2, dependsOnSeqNo: 7 }),
    ],
  },
}

export const WithAWaitingAndABlockedStep: Story = {
  args: {
    steps: [
      step({ id: 's1', seqNo: 1, name: 'Kickoff call', status: 'DONE', startedOn: '2026-08-01', finishedOn: '2026-08-01', tatDays: 1 }),
      step({
        id: 's2', seqNo: 2, name: 'Requirements confirmation', status: 'WAITING', tatDays: 3, tatPercent: 60,
        owner: { displayName: 'Meera Iyer' }, dependsOnSeqNo: 1,
      }),
      step({
        id: 's3', seqNo: 3, name: 'Account & environment setup', status: 'BLOCKED', tatDays: 5, tatPercent: 110,
        note: 'Waiting on vendor SFTP credentials', owner: { displayName: 'Priya Nair' }, dependsOnSeqNo: 2,
      }),
      step({ id: 's4', seqNo: 4, name: 'Data migration', status: 'PENDING', tatDays: 8, dependsOnSeqNo: 3 }),
    ],
  },
}

export const NoTemplatePinnedYet: Story = {
  args: { steps: [] },
}

export const Interactive: Story = {
  render: () => {
    const steps: JourneyStep[] = [
      step({ id: 's1', seqNo: 1, name: 'Kickoff call', status: 'DONE', startedOn: '2026-08-01', finishedOn: '2026-08-01' }),
      step({ id: 's2', seqNo: 2, name: 'Requirements confirmation', status: 'CURRENT', tatPercent: 40, dependsOnSeqNo: 1 }),
      step({ id: 's3', seqNo: 3, name: 'Account setup', status: 'PENDING', dependsOnSeqNo: 2 }),
    ]
    const [selected, setSelected] = React.useState<string | undefined>(undefined)
    return (
      <JourneyRibbonStrip
        steps={steps}
        selectedStepId={selected}
        onSelectStep={(s) => setSelected((prev) => (prev === s.id ? undefined : s.id))}
      />
    )
  },
}
