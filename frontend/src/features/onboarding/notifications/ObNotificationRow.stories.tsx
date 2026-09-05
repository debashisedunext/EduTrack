import type { Meta, StoryObj } from '@storybook/react-vite'
import { MemoryRouter } from 'react-router-dom'

import type { ObNotification } from '@/api/generated/model'
import { ObNotificationRow } from './ObNotificationBell'

/**
 * B-112 · one OB-13 entry.
 *
 * <p>The row rather than the popover or the page, because the row is the part
 * both surfaces share and the only part with visual states worth comparing side
 * by side. The two containers fetch through react-query and would need a client
 * and a mock server in every story to render a list that says the same thing.
 *
 * <p>It also happens to be the only way to see any of this today: the bell
 * mounts on the onboarding shell B-108/B-109 will build, and until then nothing
 * in the running app renders it.
 */
const meta: Meta<typeof ObNotificationRow> = {
  title: 'Onboarding/ObNotificationRow',
  component: ObNotificationRow,
  tags: ['autodocs'],
  decorators: [
    // Every entry with a `deepLink` is a `Link`.
    (Story) => (
      <MemoryRouter>
        <div className="w-96 overflow-hidden rounded-control border border-border bg-surface">
          <Story />
        </div>
      </MemoryRouter>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'B-112 · one entry in the OB-13 bell popover and full page. The category chip is ' +
          'the tab it belongs to; UPDATE has no tab and appears only under All.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof ObNotificationRow>

function entry(over: Partial<ObNotification>): ObNotification {
  return {
    id: 1,
    eventKey: 'STEP_ASSIGNED',
    category: 'ASSIGNMENT',
    title: 'Data Migration — Northwind Technologies Pvt Ltd',
    body: 'Assigned to you, due 12 Sep 2026.',
    obClientId: 1,
    journeyId: 1,
    stepId: 3,
    isRead: false,
    createdAt: '2026-09-04T04:30:00Z',
    deepLink: '/onboarding/clients/1',
    ...over,
  }
}

/** Something is now expected of the reader. */
export const Assignment: Story = { args: { notification: entry({}), onOpen: () => {} } }

/** Something is late, refused, or has got worse. */
export const Escalation: Story = {
  args: {
    notification: entry({
      eventKey: 'TAT_BREACHED',
      category: 'ESCALATION',
      title: 'Overdue by 2 days: Data Migration',
      body: 'The onboarding for Northwind Technologies Pvt Ltd is held up until this closes.',
    }),
    onOpen: () => {},
  },
}

/** A deadline approaching that nobody has missed yet — the distinction from Escalation is the point. */
export const Reminder: Story = {
  args: {
    notification: entry({
      eventKey: 'TAT_REMINDER',
      category: 'REMINDER',
      title: 'Due 05 Sep 2026: Environment Provisioning',
      body: 'Acme Private Limited is waiting on this one.',
    }),
    onOpen: () => {},
  },
}

/** Progress worth knowing about. No tab; it appears under All. */
export const Update: Story = {
  args: {
    notification: entry({
      eventKey: 'GO_LIVE',
      category: 'UPDATE',
      title: 'Contoso Education Trust is live',
      body: 'Every journey is complete and signed off. Handover to support can begin.',
    }),
    onOpen: () => {},
  },
}

/** Read: no tint, and no "Unread" beside the timestamp. */
export const Read: Story = {
  args: { notification: entry({ isRead: true }), onOpen: () => {} },
}

/**
 * The fallback wording, which is what a reader sees when the enqueuer's payload
 * was missing a value the title needed. Shorter and still true.
 */
export const FallbackWording: Story = {
  args: {
    notification: entry({
      eventKey: 'TAT_BREACHED',
      category: 'ESCALATION',
      title: 'One of your services has passed its TAT',
      body: 'The onboarding is held up until this closes.',
    }),
    onOpen: () => {},
  },
}

/**
 * An entry that names no client renders as a button rather than a link. An
 * anchor with no `href` would sit in the tab order doing nothing, which is
 * worse for a keyboard user than a mouse one — they cannot see it goes nowhere
 * before committing to it.
 */
export const NoDestination: Story = {
  args: {
    notification: entry({
      eventKey: 'CLIENT_LOGIN_CREATED',
      category: 'UPDATE',
      title: 'A client portal login was created',
      body: 'The client can now sign in and follow their implementation.',
      obClientId: null,
      journeyId: null,
      stepId: null,
      deepLink: null,
    }),
    onOpen: () => {},
  },
}

/**
 * A long client name. The server truncates the stored title at 200 characters;
 * this is what the row does with one that still fits.
 */
export const LongTitle: Story = {
  args: {
    notification: entry({
      title: 'Requirement Sign-off & Environment Provisioning — Northwind Technologies Private Limited (Pune Campus)',
    }),
    onOpen: () => {},
  },
}
