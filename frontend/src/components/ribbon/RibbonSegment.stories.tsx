import type { Meta, StoryObj } from '@storybook/react-vite'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonSegment } from './RibbonSegment'

const meta: Meta<typeof RibbonSegment> = {
  title: 'Ribbon/RibbonSegment',
  component: RibbonSegment,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'One segment of the Workflow Ribbon — blueprint §4A.3. Six states, five data points: ' +
          'stage name + state icon, owner avatar + name, time in stage, effort logged, and the ' +
          'loop-back badge. C-051 lays these out into the strip; the props are the contract ' +
          'between the two tasks.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof RibbonSegment>

function seg(over: Partial<RibbonSegmentData> = {}): RibbonSegmentData {
  return {
    stageCode: 'DEVELOPMENT',
    displayName: 'Development',
    state: SegmentState.COMPLETED,
    sequence: 3,
    owner: { id: 7, displayName: 'Ravi Kumar' },
    ownerRole: 'DEVELOPER',
    enteredAt: '2026-08-01T09:00:00Z',
    exitedAt: '2026-08-03T13:00:00Z',
    durationMins: 2940,
    effortHrs: 14.5,
    idleMins: 2070,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

export const Completed: Story = { args: { segment: seg(), isLast: true } }

/** Every state side by side — the check that colour is never the only signal. */
export const AllSixStates: Story = {
  render: () => (
    <div className="flex flex-wrap items-start gap-y-4">
      <RibbonSegment segment={seg({ displayName: 'Intake', stageCode: 'INTAKE', ownerRole: 'SUPPORT', owner: { id: 1, displayName: 'Priya Nair' }, durationMins: 70, effortHrs: 0.5 })} />
      <RibbonSegment segment={seg({ displayName: 'Triage', stageCode: 'TRIAGE', state: SegmentState.SKIPPED, ownerRole: 'PM', owner: undefined, durationMins: null, effortHrs: 0, skipReason: 'pre-triaged by the desk' })} />
      <RibbonSegment segment={seg({ state: SegmentState.REWORKED, loopBackCount: 2 })} />
      <RibbonSegment segment={seg({ displayName: 'QA', stageCode: 'QA', state: SegmentState.CURRENT, ownerRole: 'QA', owner: { id: 9, displayName: 'Anil Sharma' }, durationMins: null, exitedAt: null, effortHrs: 2, loopBackCount: 1 })} />
      <RibbonSegment segment={seg({ displayName: 'Deployment', stageCode: 'DEPLOYMENT', state: SegmentState.BLOCKED, ownerRole: 'DEPLOYMENT', owner: { id: 4, displayName: 'Karan Desai' }, durationMins: 330, effortHrs: 1.5 })} />
      <RibbonSegment segment={seg({ displayName: 'Sign-off', stageCode: 'SIGNOFF', state: SegmentState.PENDING, ownerRole: 'PM', owner: undefined, durationMins: null, exitedAt: null, effortHrs: undefined })} isLast />
    </div>
  ),
}

/**
 * The current stage. `durationMins` is null while a stage is open, so the tile
 * counts wall clock and labels it `elapsed` — the sealed segments beside it
 * carry *working* minutes from the server and are labelled `in stage`. Two
 * units, two words, on purpose.
 */
export const CurrentWithLiveTimer: Story = {
  args: {
    segment: seg({
      displayName: 'QA',
      stageCode: 'QA',
      state: SegmentState.CURRENT,
      ownerRole: 'QA',
      owner: { id: 9, displayName: 'Anil Sharma' },
      durationMins: null,
      exitedAt: null,
      effortHrs: 2,
    }),
    isLast: true,
  },
}

/** §4A.3's inline contextual action, which C-044 fills in. */
export const CurrentWithAction: Story = {
  args: {
    segment: seg({ state: SegmentState.CURRENT, durationMins: null, exitedAt: null }),
    isLast: true,
    actionSlot: (
      <button type="button" className="rounded-control bg-primary px-2 py-1 text-caption font-medium text-white">
        Hand off to QA →
      </button>
    ),
  },
}

/** A stage QA has bounced twice — the badge §4A.2 calls `↺ ×2`. */
export const Reworked: Story = {
  args: { segment: seg({ state: SegmentState.REWORKED, loopBackCount: 2, iterationNo: 3 }), isLast: true },
}

/** Struck through, with the reason on hover. Who authorised it has no field. */
export const Skipped: Story = {
  args: {
    segment: seg({
      displayName: 'Verification',
      stageCode: 'VERIFICATION',
      state: SegmentState.SKIPPED,
      owner: undefined,
      durationMins: null,
      effortHrs: 0,
      skipReason: 'hotfix, verified in production by the PM',
    }),
    isLast: true,
  },
}

/** Nobody has held this stage yet, so it names the role that will. */
export const PendingUnassigned: Story = {
  args: {
    segment: seg({
      displayName: 'Deployment',
      stageCode: 'DEPLOYMENT',
      state: SegmentState.PENDING,
      ownerRole: 'DEPLOYMENT',
      owner: undefined,
      enteredAt: null,
      exitedAt: null,
      durationMins: null,
      effortHrs: undefined,
      loopBackCount: 0,
    }),
    isLast: true,
  },
}

/** With `onSelect` the tile is a real button — focusable, Enter and Space. */
export const Selectable: Story = {
  args: { segment: seg(), isLast: true, onSelect: () => {}, isSelected: true },
}
