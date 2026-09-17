import type { Meta, StoryObj } from '@storybook/react-vite'

import { ObCursorPager } from './ObCursorPager'

/**
 * The four states of the onboarding pager, which are four different sentences.
 *
 * <p>There is no "page 3 of 12" here and there cannot be: `PageMeta` carries no
 * `totalCount`, because a total over a cursor-paged predicate costs a second
 * `COUNT(*)` and is stale the moment it is computed. So the end of the list is
 * only knowable once you are standing on it — which is what the last-page story
 * shows, and why it is worth looking at rather than only asserting.
 */
const meta = {
  title: 'Onboarding/ObCursorPager',
  component: ObCursorPager,
  parameters: { layout: 'padded' },
  args: {
    noun: 'projects',
    pageIndex: 0,
    rowsOnPage: 10,
    hasMore: true,
    isFetching: false,
    canGoBack: false,
    onPrevious: () => {},
    onNext: () => {},
  },
} satisfies Meta<typeof ObCursorPager>

export default meta
type Story = StoryObj<typeof meta>

/** Page one: nowhere back to, so Previous is dead rather than absent. */
export const FirstPage: Story = {}

/** Mid-list, both ways open. */
export const MiddlePage: Story = {
  args: { pageIndex: 2, canGoBack: true },
}

/**
 * The last page, which is a short one. `hasMore` false is the only honest
 * source of "there is no more" — a full page is not evidence either way.
 */
export const LastPage: Story = {
  args: { pageIndex: 2, rowsOnPage: 5, hasMore: false, canGoBack: true },
}

/**
 * A page in flight. Both controls are held, not for tidiness: the query keeps
 * the previous page on screen while the next loads, so `meta` still describes
 * the page being left and a second click would push the same cursor twice.
 */
export const Fetching: Story = {
  args: { pageIndex: 1, isFetching: true, canGoBack: true },
}

/** An empty page that still has one behind it — the way back out of it. */
export const EmptyWithHistory: Story = {
  args: { pageIndex: 3, rowsOnPage: 0, hasMore: false, canGoBack: true, noun: 'clients' },
}
