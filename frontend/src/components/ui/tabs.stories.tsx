import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { Tabs, type TabItem } from './tabs'

const meta: Meta<typeof Tabs> = {
  title: 'UI/Tabs',
  component: Tabs,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'The shared APG tab strip — arrows move between tabs, Home/End jump to the ends, ' +
          'selection follows focus. Promoted from the ticket detail page once a second screen ' +
          '(the dashboard) needed the same control.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof Tabs>

const TABS: TabItem[] = [
  { id: 'today', label: "Today's Progress", content: <p>Today tab content.</p> },
  { id: 'overview', label: 'Ticket Overview', content: <p>Overview tab content.</p> },
  { id: 'weekly', label: 'Weekly Progress', content: <p>Weekly tab content.</p> },
  { id: 'analytics', label: 'Analytics', content: <p>Analytics tab content.</p> },
]

export const Default: Story = {
  render: () => {
    const [activeId, setActiveId] = React.useState('today')
    return <Tabs tabs={TABS} activeId={activeId} onSelect={setActiveId} ariaLabel="Dashboard" />
  },
}

/** Two instances on one page — each needs its own ids, hence `useId` internally. */
export const TwoInstances: Story = {
  render: () => {
    const [left, setLeft] = React.useState('today')
    const [right, setRight] = React.useState('overview')
    return (
      <div className="flex gap-4">
        <Tabs tabs={TABS} activeId={left} onSelect={setLeft} ariaLabel="Left" className="flex-1" />
        <Tabs tabs={TABS} activeId={right} onSelect={setRight} ariaLabel="Right" className="flex-1" />
      </div>
    )
  },
}
