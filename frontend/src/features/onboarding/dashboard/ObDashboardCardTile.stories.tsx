import type { Meta, StoryObj } from '@storybook/react-vite'

import { ObDashboardCardTile } from './ObDashboardCardTile'

/**
 * B-121 · one card on the OB-02 board.
 *
 * <p>The tile rather than the page, because the tile is where every visual
 * state lives and the page fetches through react-query — which would need a
 * client and a mock server in each story to render a board that says the same
 * thing.
 *
 * <p>The three states below are the reason this component is not a number in a
 * box. Two of them exist because the pre-aggregated table behind OB-02 cannot
 * answer every question put to it, and the board says so rather than guessing;
 * see the feature's backend README for what would actually fix each.
 */
const meta: Meta<typeof ObDashboardCardTile> = {
  title: 'Onboarding/ObDashboardCardTile',
  component: ObDashboardCardTile,
  tags: ['autodocs'],
  decorators: [
    (Story) => (
      <div className="w-64">
        <Story />
      </div>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'B-121 · one card on the OB-02 board. Three states: a figure, a figure that may ' +
          'overstate (≈), and a sentence where the caller’s scope has no table that can ' +
          'answer the card at all.',
      },
    },
  },
}

export default meta
type Story = StoryObj<typeof ObDashboardCardTile>

export const Default: Story = {
  args: { card: { key: 'ongoing-projects', count: 42, deltaFromYesterday: 3 } },
}

/** A card whose rise is bad news takes the warning accent; the arrow still only states direction. */
export const AtRisk: Story = {
  args: { card: { key: 'at-risk', count: 11, deltaFromYesterday: 2 } },
}

export const Overdue: Story = {
  args: { card: { key: 'overdue-clients', count: 6, deltaFromYesterday: -2 } },
}

/** The first day a deployment has data: a number, and nothing to compare it with. */
export const NoPreviousDay: Story = {
  args: { card: { key: 'todays-delivery', count: 8, deltaFromYesterday: null } },
}

/**
 * The client-counted cards on the all-products board. `ob_dashboard_summary`
 * stores them per product, so a client who bought ERP and Biometric is counted
 * in both rows — the figure is a ceiling and says so rather than being three
 * times too large in silence.
 */
export const UpperBound: Story = {
  args: {
    card: { key: 'live', count: 12, deltaFromYesterday: 1, countIsUpperBound: true },
  },
}

/**
 * A Step Owner or a Sales user. The summary table has no scope dimension, so
 * their board is not merely unfiltered — it is unanswerable from the only
 * source CLAUDE.md permits. The sentence replaces the number; a zero would
 * claim nothing is overdue, which is false.
 */
export const Unavailable: Story = {
  args: {
    card: {
      key: 'overdue-clients',
      count: 0,
      unavailableReason:
        'The board counts journeys containing your services, and the summary it reads is ' +
        'stored per product with no scope dimension — so this card cannot be narrowed to ' +
        'you yet. The lists below are scoped correctly.',
    },
  },
}

/** With B-127's slide-over wired, the tile becomes a button. */
export const Interactive: Story = {
  args: {
    card: { key: 'client-escalations', count: 3, deltaFromYesterday: 1 },
    onOpen: () => {},
  },
}
