import type { Meta, StoryObj } from '@storybook/react-vite'

import type { ObStepCommunication } from '@/api/generated/model/obStepCommunication'
import { CommunicationEntryRow } from './CommunicationEntryRow'

/**
 * C-112 · every shape a communication row can take, on one page.
 *
 * Storybook is the contract for anything a second stream will render, and this
 * row is one: CP-03's portal thread (C-121) and C-126's escalation flow both
 * draw the same entries from the other side of `isClientVisible`. The three
 * author shapes are the reason it is here rather than covered only by the two
 * panels' own tests — `ck_ob_comms_author` allows a staff row, a client row
 * and a row with no author at all, and a renderer that has only ever seen the
 * first is a renderer that breaks on the day the portal ships.
 */
const base: ObStepCommunication = {
  id: 1,
  stepId: 42,
  channel: 'CALL',
  occurredAt: '2026-09-02T14:00:00Z',
  createdAt: '2026-09-02T14:25:00Z',
  summary:
    'Meena asked whether the biometric devices can ship before the ERP go-live. Told her yes, subject to the gate.',
  isClientVisible: true,
  authorType: 'STAFF',
  authorName: 'Ravi Kumar',
  recordedBy: { id: 3, displayName: 'Ravi Kumar' },
}

const meta = {
  title: 'Onboarding/CommunicationEntryRow',
  component: CommunicationEntryRow,
  parameters: { layout: 'padded' },
  decorators: [
    (Story) => (
      <ul className="max-w-2xl divide-y divide-border">
        <Story />
      </ul>
    ),
  ],
} satisfies Meta<typeof CommunicationEntryRow>

export default meta
type Story = StoryObj<typeof meta>

/** Recorded by a person, published to the portal. */
export const StaffAndClientVisible: Story = {
  args: { entry: base },
}

/**
 * The default, and the one that must never be mistaken for the one above.
 * Both states are named in words — §11 and CP-03.
 */
export const StaffAndInternal: Story = {
  args: {
    entry: {
      ...base,
      id: 2,
      channel: 'OTHER',
      isClientVisible: false,
      summary:
        'Their Tally export is missing FY25 opening balances. Do not raise it until we have checked our own importer.',
    },
  },
}

/** Written through the portal. No staff user at all — `recordedBy` is null. */
export const FromTheClient: Story = {
  args: {
    entry: {
      ...base,
      id: 3,
      channel: 'COMMENT',
      authorType: 'CLIENT',
      authorName: 'Sanjay Bose',
      recordedBy: null,
      summary: 'Finance is still reviewing. We should have the signed pack to you early next week.',
    },
  },
}

/** Nobody typed it. `ck_ob_comms_author`'s third valid shape. */
export const WrittenByTheModule: Story = {
  args: {
    entry: {
      ...base,
      id: 4,
      channel: 'SYSTEM',
      authorType: 'SYSTEM',
      authorName: 'System',
      recordedBy: null,
      isClientVisible: false,
      summary: 'Service paused — waiting on the client. The TAT clock is frozen.',
    },
  },
}

/** C-126's mirror. The one entry type that gets a treatment of its own. */
export const AnEscalation: Story = {
  args: {
    entry: {
      ...base,
      id: 5,
      channel: 'ESCALATION',
      authorType: 'CLIENT',
      authorName: 'Meena Raghavan',
      recordedBy: null,
      summary: 'Data migration has slipped twice with no revised date. Please escalate.',
    },
  },
}

/**
 * The stitched view's extra line. A Friday call typed on Monday also shows
 * both dates — the prominent one is when it happened, because a communication
 * audit ordered by entry time misreports every backdated entry.
 */
export const OnTheStitchedTimeline: Story = {
  args: {
    entry: {
      ...base,
      id: 6,
      channel: 'MEETING',
      occurredAt: '2026-08-04T10:30:00Z',
      createdAt: '2026-08-07T15:10:00Z',
      summary: 'Kickoff call with Meena and Sanjay. Scope agreed, SSO confirmed in phase 1.',
    },
    context: 'ERP Suite · 1. Kickoff & Requirement Sign-off',
  },
}
