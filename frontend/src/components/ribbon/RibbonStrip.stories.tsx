import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonStrip, type SelectedSegment } from './RibbonStrip'

const meta: Meta<typeof RibbonStrip> = {
  title: 'Ribbon/RibbonStrip',
  component: RibbonStrip,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'C-051 lays out B-050\'s segment tiles in order; C-052 wires what a click does and the ' +
          'current segment\'s contextual action. The cycle selector (C-053) and the iteration chip ' +
          '(C-054) still sit outside this component.',
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

/**
 * C-052 · click a tile and it filters. `TicketDetailPage` owns the selection
 * state and narrows History/Effort below it in the real page; here the story
 * just proves a click marks the right tile — `RibbonSegment`'s own
 * `aria-pressed` and ring are what a caller relies on.
 */
export const Interactive: Story = {
  render: () => {
    const segments = [
      seg({ stageCode: 'INTAKE', displayName: 'Intake', sequence: 1 }),
      seg({
        stageCode: 'TRIAGE', displayName: 'Triage / Planning', sequence: 2,
        owner: { id: 2, displayName: 'Meera Prasad' }, ownerRole: 'PM',
      }),
      seg({
        stageCode: 'DEVELOPMENT', displayName: 'Development', sequence: 3, state: SegmentState.CURRENT,
        owner: { id: 7, displayName: 'Ravi Kumar' }, ownerRole: 'DEVELOPER',
        durationMins: null, exitedAt: null,
      }),
    ]
    const [selected, setSelected] = React.useState<SelectedSegment | undefined>(undefined)
    return (
      <RibbonStrip
        ribbon={{ cycleNo: 1, iterationNo: 1, isSealed: false, canAdvance: false, segments }}
        selectedSegment={selected}
        onSelectSegment={(segment) =>
          setSelected((prev) =>
            prev?.stageCode === segment.stageCode ? undefined : { stageCode: segment.stageCode ?? '' },
          )
        }
      />
    )
  },
}

/** `ribbon.canAdvance` — the golden rule, resolved server-side — puts C-052's
 * honestly-disabled handoff placeholder on the current segment. `C-044`'s
 * dialog replaces it; until then it names the task that will. */
export const WithHandoffAction: Story = {
  args: {
    ribbon: {
      cycleNo: 1,
      iterationNo: 1,
      isSealed: false,
      currentStageCode: 'DEVELOPMENT',
      canAdvance: true,
      segments: [
        seg({ stageCode: 'INTAKE', displayName: 'Intake', sequence: 1 }),
        seg({
          stageCode: 'DEVELOPMENT', displayName: 'Development', sequence: 2, state: SegmentState.CURRENT,
          owner: { id: 7, displayName: 'Ravi Kumar' }, ownerRole: 'DEVELOPER',
          durationMins: null, exitedAt: null,
        }),
        seg({
          stageCode: 'QA', displayName: 'QA', sequence: 3, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'QA',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined, loopBackCount: 0,
        }),
      ],
    },
  },
}

/**
 * B-052 · §4A.3's "fully keyboard-navigable" ribbon at the width it is
 * actually hard: eight stages, the whole standard flow.
 *
 * Tab once to enter the strip — focus lands on **Deployment**, the stage the
 * ticket is in, not on an Intake finished four days ago. `←`/`→` move between
 * segments, `Home`/`End` jump to the ends and both wrap. Tab again leaves the
 * strip entirely: eight segments are one stop on the way down the page, which
 * is the whole point, because History, Effort, Chat and the roll-up grid all
 * sit below the ribbon on S-20.
 *
 * Moving focus deliberately does **not** select. Selecting filters those tabs
 * below to one stage and iteration, so reading the ribbon with the arrow keys
 * would otherwise refilter three panels eight times; activation is `Enter` or
 * `Space`.
 */
export const KeyboardNavigation: Story = {
  args: {
    ribbon: {
      cycleNo: 1,
      iterationNo: 2,
      isSealed: false,
      currentStageCode: 'DEPLOY',
      canAdvance: true,
      segments: [
        seg({ stageCode: 'INTAKE', displayName: 'Intake', sequence: 1 }),
        seg({
          stageCode: 'TRIAGE', displayName: 'Triage', sequence: 2,
          owner: { id: 3, displayName: 'Meera Prasad' }, ownerRole: 'PM',
          durationMins: 200, effortHrs: 1,
        }),
        seg({
          stageCode: 'DEV', displayName: 'Development', sequence: 3, state: SegmentState.REWORKED,
          owner: { id: 7, displayName: 'Ravi Kumar' }, ownerRole: 'DEVELOPER',
          durationMins: 2940, effortHrs: 14.5, loopBackCount: 2, iterationNo: 2,
        }),
        seg({
          stageCode: 'QA', displayName: 'QA', sequence: 4,
          owner: { id: 9, displayName: 'Anil Sharma' }, ownerRole: 'QA',
          durationMins: 400, effortHrs: 5.5, iterationNo: 2,
        }),
        seg({
          stageCode: 'DEPLOY', displayName: 'Deployment', sequence: 5, state: SegmentState.CURRENT,
          owner: { id: 11, displayName: 'Karan Desai' }, ownerRole: 'DEPLOYMENT',
          durationMins: null, exitedAt: null, effortHrs: 1.5, iterationNo: 2,
        }),
        seg({
          stageCode: 'VERIFY', displayName: 'Verification', sequence: 6, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'DEVELOPER',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined,
        }),
        seg({
          stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 7, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'PM',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined,
        }),
        seg({
          stageCode: 'CLOSED', displayName: 'Closed', sequence: 8, state: SegmentState.PENDING,
          owner: undefined, ownerRole: 'SUPPORT',
          enteredAt: null, exitedAt: null, durationMins: null, effortHrs: undefined,
        }),
      ],
    },
  },
}

/**
 * B-052 · the same keyboard, on a ribbon with nothing to activate.
 *
 * S-13 tab 3's live preview, S-30's designer preview and a sealed past cycle
 * all render without `onSelectSegment`, so their tiles are `<div role="group">`
 * rather than buttons. They still take the roving tab stop — otherwise C-052's
 * tooltip (entered, exited, owner, note, effort, idle-vs-active) would have no
 * keyboard route on any of the three. Tab in, arrow across, and `Enter` does
 * nothing, which is correct: there is nothing here to press.
 */
export const ReadOnlyStillNavigable: Story = {
  args: {
    ribbon: {
      cycleNo: 1,
      iterationNo: 1,
      isSealed: true,
      currentStageCode: undefined,
      canAdvance: false,
      segments: [
        seg({ stageCode: 'INTAKE', displayName: 'Intake', sequence: 1 }),
        seg({
          stageCode: 'DEV', displayName: 'Development', sequence: 2,
          owner: { id: 7, displayName: 'Ravi Kumar' }, ownerRole: 'DEVELOPER',
          durationMins: 2940, effortHrs: 14.5,
        }),
        seg({
          stageCode: 'QA', displayName: 'QA', sequence: 3,
          owner: { id: 9, displayName: 'Anil Sharma' }, ownerRole: 'QA',
          durationMins: 400, effortHrs: 3.5,
        }),
      ],
    },
    onSelectSegment: undefined,
  },
}
