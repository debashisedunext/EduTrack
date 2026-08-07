import type { Meta, StoryObj } from '@storybook/react-vite'
import {
  SlideOver,
  SlideOverContent,
  SlideOverHeader,
  SlideOverTitle,
  SlideOverBody,
  SlideOverFooter,
  SlideOverTrigger,
} from './slide-over'
import { Button } from './button'
import { Input } from './input'

const meta: Meta<typeof SlideOver> = {
  title: 'UI/SlideOver',
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof SlideOver>

// Two clicks, no reload — the resource's daily driver (blueprint §7's Quick Update).
export const QuickUpdate: Story = {
  render: () => (
    <SlideOver>
      <SlideOverTrigger asChild>
        <Button>Open Quick Update</Button>
      </SlideOverTrigger>
      <SlideOverContent>
        <SlideOverHeader>
          <SlideOverTitle>Quick Update</SlideOverTitle>
        </SlideOverHeader>
        <SlideOverBody className="space-y-4">
          <Input placeholder="Work note" />
          <Input placeholder="Effort logged (hrs)" />
        </SlideOverBody>
        <SlideOverFooter>
          <Button>Save</Button>
        </SlideOverFooter>
      </SlideOverContent>
    </SlideOver>
  ),
}
