import type { Meta, StoryObj } from '@storybook/react-vite'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from './tooltip'
import { Button } from './button'

const meta: Meta<typeof TooltipContent> = {
  title: 'UI/Tooltip',
  component: TooltipContent,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'C-052 · the rich-hover primitive over `@radix-ui/react-tooltip`. Reaches keyboard focus as ' +
          'well as pointer hover, unlike a native `title`. First consumer is the Workflow Ribbon segment.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof TooltipContent>

export const Default: Story = {
  render: () => (
    <TooltipProvider delayDuration={0}>
      <Tooltip defaultOpen>
        <TooltipTrigger asChild>
          <Button size="sm" variant="secondary">
            Hover or focus me
          </Button>
        </TooltipTrigger>
        <TooltipContent>Entered 1 Aug, 09:00 · Owner Ravi Kumar</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  ),
}

/** Several lines of structured detail — the ribbon's own use, not a one-liner. */
export const RichContent: Story = {
  render: () => (
    <TooltipProvider delayDuration={0}>
      <Tooltip defaultOpen>
        <TooltipTrigger asChild>
          <Button size="sm" variant="secondary">
            Development
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
            <dt className="text-content-muted">Entered</dt>
            <dd>1 Aug 2026, 09:00</dd>
            <dt className="text-content-muted">Exited</dt>
            <dd>3 Aug 2026, 13:00</dd>
            <dt className="text-content-muted">Owner</dt>
            <dd>Ravi Kumar</dd>
            <dt className="text-content-muted">Effort</dt>
            <dd>14.5 h</dd>
            <dt className="text-content-muted">Idle</dt>
            <dd>1d 10h of 2d 1h — mostly waiting</dd>
          </dl>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  ),
}
