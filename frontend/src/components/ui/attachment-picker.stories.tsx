import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { AttachmentPicker, type AttachmentItem } from './attachment-picker'

/**
 * The shared attachment control — C-023, blueprint §4B.4.
 *
 * Presentational by design: it validates, it renders, it hands `File[]` back. It
 * does not upload. `features/tickets/attachments/useTicketAttachments` owns the
 * lifecycle, which is what lets the create form stage files until a ticket
 * exists while ticket detail and quick update upload immediately.
 *
 * Storybook can only show the states, not the transitions — a real upload needs
 * the API. The statuses below are therefore driven by fixture data, which is
 * also how the unit tests reach them.
 */
const meta: Meta<typeof AttachmentPicker> = {
  title: 'UI/AttachmentPicker',
  component: AttachmentPicker,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'Drag-and-drop plus file picker over §4B.4’s allow-list and limits. Clipboard paste is C-024 and plugs into the same `onAdd`.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof AttachmentPicker>

/** Controlled wrapper — the component holds no item state of its own. */
function Stateful({ initial = [], ...props }: { initial?: AttachmentItem[] } & Partial<React.ComponentProps<typeof AttachmentPicker>>) {
  const [items, setItems] = React.useState<AttachmentItem[]>(initial)
  return (
    <div className="max-w-xl">
      <AttachmentPicker
        {...props}
        items={items}
        onAdd={(files) =>
          setItems((prev) => [
            ...prev,
            ...files.map((file) => ({
              id: `${file.name}-${prev.length}`,
              name: file.name,
              sizeBytes: file.size,
              contentType: file.type,
              status: 'ready' as const,
            })),
          ])
        }
        onRemove={(id) => setItems((prev) => prev.filter((item) => item.id !== id))}
      />
    </div>
  )
}

/** Empty, with the full drop zone. What the create form and ticket detail show. */
export const Default: Story = { render: () => <Stateful /> }

/**
 * Every status at once.
 *
 * `scanning` is the one worth looking at: §4B.4 says a file is not visible until
 * the AV scan passes, so no thumbnail renders and no download is offered — the
 * row exists to say the file arrived, not to hand it over.
 */
export const AllStatuses: Story = {
  render: () => (
    <Stateful
      initial={[
        { id: '1', name: 'gateway-500.png', sizeBytes: 184_320, contentType: 'image/png', status: 'ready' },
        { id: '2', name: 'payment-trace.log', sizeBytes: 2_411_724, contentType: 'text/plain', status: 'uploading' },
        { id: '3', name: 'qa-signoff-report.pdf', sizeBytes: 421_888, contentType: 'application/pdf', status: 'scanning' },
        {
          id: '4',
          name: 'db-dump.zip',
          sizeBytes: 48_234_496,
          contentType: 'application/zip',
          status: 'failed',
          error: 'Too large for this ticket',
        },
      ]}
    />
  ),
}

/**
 * The compact variant — one inline button, no drop zone.
 *
 * For §4B.5's comment box (`[📎]` in the S-20 wireframe) and the quick update
 * slide-over, which is too narrow to spend 96px on a drop target. Dropping onto
 * it still works; it just stops advertising that it does.
 */
export const Compact: Story = {
  render: () => (
    <Stateful
      compact
      initial={[{ id: '1', name: 'screenshot.png', sizeBytes: 91_204, contentType: 'image/png', status: 'ready' }]}
    />
  ),
}

/** Sealed cycles and in-flight saves. No control accepts input; the list still reads. */
export const Disabled: Story = {
  render: () => (
    <Stateful
      disabled
      initial={[{ id: '1', name: 'error-log.txt', sizeBytes: 8_192, contentType: 'text/plain', status: 'ready' }]}
    />
  ),
}

/**
 * At the file-count ceiling.
 *
 * Both the browse button and the native input go disabled, and the caption says
 * why — a picker that opens onto a rejection is worse than one that does not
 * open.
 */
export const AtFileLimit: Story = {
  render: () => (
    <Stateful
      limits={{ maxFileBytes: 10 * 1024 * 1024, maxTotalBytes: 50 * 1024 * 1024, maxFiles: 2 }}
      initial={[
        { id: '1', name: 'one.png', sizeBytes: 12_000, contentType: 'image/png', status: 'ready' },
        { id: '2', name: 'two.png', sizeBytes: 14_000, contentType: 'image/png', status: 'ready' },
      ]}
    />
  ),
}
