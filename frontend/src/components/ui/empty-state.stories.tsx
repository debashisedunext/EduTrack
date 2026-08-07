import type { Meta, StoryObj } from '@storybook/react-vite'
import { EmptyState } from './empty-state'
import { Button } from './button'

const meta: Meta<typeof EmptyState> = {
  title: 'UI/EmptyState',
  component: EmptyState,
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof EmptyState>

export const Default: Story = { args: { title: 'No tickets yet' } }

export const WithDescriptionAndAction: Story = {
  args: {
    title: 'No tickets yet',
    description: 'Create your first ticket to get started.',
    action: <Button>New ticket</Button>,
  },
}
