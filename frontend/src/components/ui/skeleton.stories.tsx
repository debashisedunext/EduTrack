import type { Meta, StoryObj } from '@storybook/react-vite'
import { Skeleton } from './skeleton'

const meta: Meta<typeof Skeleton> = {
  title: 'UI/Skeleton',
  component: Skeleton,
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof Skeleton>

export const Line: Story = { args: { className: 'h-4 w-64' } }
export const Avatar: Story = { args: { className: 'h-10 w-10 rounded-full' } }

// Never a spinner — tables/charts show shaped placeholders while loading (§12.2).
export const TableRowPlaceholder: Story = {
  render: () => (
    <div className="w-96 space-y-2">
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-5/6" />
      <Skeleton className="h-4 w-2/3" />
    </div>
  ),
}
