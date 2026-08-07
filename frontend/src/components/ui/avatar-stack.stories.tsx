import type { Meta, StoryObj } from '@storybook/react-vite'
import { AvatarStack } from './avatar-stack'

const WATCHERS = [
  { id: '1', name: 'Priya Sharma' },
  { id: '2', name: 'Meera Iyer' },
  { id: '3', name: 'Ravi Kumar' },
  { id: '4', name: 'Anil Gupta' },
  { id: '5', name: 'Karan Mehta' },
]

const meta: Meta<typeof AvatarStack> = {
  title: 'UI/AvatarStack',
  component: AvatarStack,
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof AvatarStack>

export const Default: Story = { args: { people: WATCHERS.slice(0, 3) } }

// 5 watchers, max 3 visible — overflow collapses into a "+N" chip.
export const WithOverflow: Story = { args: { people: WATCHERS, max: 3 } }

export const Medium: Story = { args: { people: WATCHERS, max: 4, size: 'md' } }
