import type { Meta, StoryObj } from '@storybook/react-vite'
import { Button } from './button'
import { Toaster } from './toaster'
import { toast } from './use-toast'

const meta: Meta = {
  title: 'UI/Toast',
  tags: ['autodocs'],
  parameters: { layout: 'fullscreen' },
}
export default meta

type Story = StoryObj

// Bottom-right, fade+rise — blueprint §12.2. Toaster is normally mounted once at the app root.
export const Default: Story = {
  render: () => (
    <div className="flex min-h-[240px] items-center justify-center gap-3">
      <Button onClick={() => toast({ title: 'Saved', description: 'Ticket updated.', variant: 'success' })}>
        Fire success toast
      </Button>
      <Button
        variant="danger"
        onClick={() => toast({ title: 'Error', description: 'Something failed.', variant: 'danger' })}
      >
        Fire danger toast
      </Button>
      <Toaster />
    </div>
  ),
}
