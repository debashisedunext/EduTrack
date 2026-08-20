import type { Meta, StoryObj } from '@storybook/react-vite'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonStrip } from './RibbonStrip'

const meta: Meta<typeof RibbonStrip> = {
  title: 'Ribbon/RibbonStrip',
  component: RibbonStrip,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'C-051 · the Workflow Ribbon strip — blueprint §4A.3, B-050\'s segment tiles laid out in ' +
          'order. Read-only: interactions (C-052), the cycle selector (C-053) and the iteration chip ' +
          '(C-054) all sit outside this component.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof RibbonStrip>

function seg(over: Partial<RibbonSegmentData> = {}): RibbonSegmentData {
  return {
    stageCode: 'INTAKE',
    displayName: 'Intake',
    state: SegmentState.COMPLETED,
    sequence: 1,
    owner: { id: 1, displayName: 'Priya Nair' },
    ownerRole: 'SUPPORT',
    enteredAt: '2026-08-01T09:00:00Z',
    exitedAt: '2026-08-01T10:10:00Z',
    durationMins: 70,
    effortHrs: 0.5,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

/** The standard flow of blueprint §4A.1, mid-journey — QA has bounced it once. */
export const StandardFlow: Story = {
  args: {
    ribbon: {
      cycleNo: 1,
      iterationNo: 2,
      isSealed: false,
      currentStageCode: 'QA',
      canAdvance: false,
      segments: [
        seg(),
        seg({
          stageCode: 'TRIAGE', displayName: 'Triage / Planning', sequence: 2,
          owner: { id: 2, displayName: 'Meera Prasad' }, ownerRole: 'PM',
          enteredAt: '2026-08-01T10:10:00Z', exitedAt: '2026-08-01T13:30:00Z',
          durationMins: 200, effortHrs: 1,
        }),
        seg({
          stageCode: 'DEVELOPMENT', displayName: 'Development', sequence: 3, state: SegmentState.REWORKED,
          owner: { id: 7, displayName: 'Ravi Kumar' }, ownerRole: 'DEVELOPER',
          enteredAt: '2026-08-01T13:30:00Z', exitedAt: '2026-08-04T09:30:00Z',
          durationMins: 2940, effortHrs: 9, loopBackCount: 1,
        }),
        seg({
          stageCode: 'QA', displayName: 'QA / Testing', sequence: 4, state: SegmentState.CURRENT,
          owner: { id: 9, displayName: 'Anil Sharma' }, ownerRole: 'QA',
          enteredAt: '2026-08-05T09:00:00Z', exitedAt: null, durationMins: null, effortHrs: 2,
        }),
        seg({
          stageCode: 'DEPLOYMENT', displayName: 'Deployment', sequence: 5, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'DEPLOYMENT',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined, loopBackCount: 0,
        }),
        seg({
          stageCode: 'VERIFICATION', displayName: 'Verification', sequence: 6, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'DEVELOPER',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined, loopBackCount: 0,
        }),
        seg({
          stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 7, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'PM',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined, loopBackCount: 0,
        }),
      ],
    },
  },
}

/** A skip in the middle of the run — struck through, connector unaffected. */
export const WithASkippedStage: Story = {
  args: {
    ribbon: {
      cycleNo: 1,
      iterationNo: 1,
      isSealed: false,
      currentStageCode: 'DEPLOYMENT',
      canAdvance: false,
      segments: [
        seg(),
        seg({
          stageCode: 'TRIAGE', displayName: 'Triage / Planning', sequence: 2, state: SegmentState.SKIPPED,
          owner: undefined, durationMins: null, effortHrs: 0,
          skipReason: 'hotfix, pre-approved by the PM',
        }),
        seg({
          stageCode: 'DEVELOPMENT', displayName: 'Development', sequence: 3,
          owner: { id: 7, displayName: 'Ravi Kumar' }, ownerRole: 'DEVELOPER',
          durationMins: 480, effortHrs: 5,
        }),
        seg({
          stageCode: 'DEPLOYMENT', displayName: 'Deployment', sequence: 4, state: SegmentState.CURRENT,
          owner: { id: 4, displayName: 'Karan Desai' }, ownerRole: 'DEPLOYMENT',
          enteredAt: '2026-08-02T09:00:00Z', exitedAt: null, durationMins: null, effortHrs: 0,
        }),
      ],
    },
  },
}

/** A cycle 1 template-less ticket, or one B-050's README notes never emits: nothing to draw. */
export const NoTemplate: Story = {
  args: { ribbon: { cycleNo: 1, iterationNo: 1, isSealed: false, canAdvance: false, segments: [] } },
}
