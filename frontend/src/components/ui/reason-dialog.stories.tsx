import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'

import { Button } from './button'
import { ReasonDialog } from './reason-dialog'

const meta: Meta<typeof ReasonDialog> = {
  title: 'UI/ReasonDialog',
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof ReasonDialog>

function Demo(props: Partial<React.ComponentProps<typeof ReasonDialog>>) {
  const [open, setOpen] = React.useState(false)
  return (
    <>
      <Button onClick={() => setOpen(true)}>Open dialog</Button>
      <ReasonDialog
        open={open}
        onOpenChange={setOpen}
        title="Escalate to staff"
        description="This will notify the onboarding manager and the service owner immediately."
        fieldLabel="What's the problem?"
        confirmLabel="Escalate"
        onConfirm={() => setOpen(false)}
        {...props}
      />
    </>
  )
}

export const Escalate: Story = {
  render: () => <Demo />,
}

export const ResolveAndAcknowledge: Story = {
  render: () => (
    <Demo
      title="Resolve escalation"
      description="This note goes back to the client — say what was done."
      fieldLabel="Resolution note"
      confirmLabel="Resolve"
      confirmVariant="danger"
    />
  ),
}
