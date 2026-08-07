import type { Meta, StoryObj } from '@storybook/react-vite'
import { Chip } from './chip'

const meta: Meta<typeof Chip> = {
  title: 'UI/Chip',
  component: Chip,
  tags: ['autodocs'],
  argTypes: {
    variant: {
      control: 'select',
      options: ['neutral', 'low', 'medium', 'high', 'critical', 'success', 'warning', 'danger', 'info'],
    },
  },
}
export default meta

type Story = StoryObj<typeof Chip>

export const Default: Story = { args: { variant: 'neutral', children: 'Unassigned' } }

// Soft-tinted background + AA-safe solid text (D-13) — never a heavy solid block.
export const LevelChips: Story = {
  render: () => (
    <div className="flex flex-wrap gap-2">
      <Chip variant="low">Low</Chip>
      <Chip variant="medium">Medium</Chip>
      <Chip variant="high">High</Chip>
      <Chip variant="critical">Critical</Chip>
    </div>
  ),
}

export const StatusChips: Story = {
  render: () => (
    <div className="flex flex-wrap gap-2">
      <Chip variant="success">On time</Chip>
      <Chip variant="warning">Delayed</Chip>
      <Chip variant="danger">Breached</Chip>
      <Chip variant="info">In progress</Chip>
    </div>
  ),
}
