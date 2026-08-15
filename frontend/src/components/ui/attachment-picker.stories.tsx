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
          'Drag-and-drop, file picker and clipboard paste over §4B.4’s allow-list and limits. All three routes run through the same validation before anything reaches `onAdd`.',
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
 * Clipboard paste — C-024.
 *
 * The one story that needs a real browser to mean anything, and Storybook is
 * one: take a screenshot with Snipping Tool (or `Win`+`Shift`+`S`), click
 * anywhere on this page, and press `Ctrl`+`V`. The listener is on `document`,
 * not on the control — nothing ever focuses a drop zone, so the whole screen has
 * to be the target.
 *
 * Two things to watch, because they are the substance of the task:
 *
 * 1. **Paste three in a row, quickly.** The OS clipboard holds one image, so
 *    several screenshots means pasting several times — and every capture reaches
 *    the browser as `image.png`, faster than the one-second stamp can separate
 *    them. All three land, under `screenshot-…`, `-2` and `-3`.
 * 2. **Paste text into the field below.** It types normally. A file on the
 *    clipboard is not on its own evidence the user meant to attach anything —
 *    copying an image from a web page brings an `<img>` tag along as
 *    `text/html`, and in a text field the text wins.
 */
export const ClipboardPaste: Story = {
  render: () => (
    <div className="flex max-w-xl flex-col gap-3">
      <label className="flex flex-col gap-1 text-caption text-content-muted">
        Paste text here — it is left alone
        <input
          className="rounded-control border border-border bg-surface px-3 py-2 text-sm text-content"
          placeholder="Ctrl+V some text"
        />
      </label>
      <Stateful />
    </div>
  ),
}

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

/**
 * Clicking a thumbnail opens it full-screen — C-026.
 *
 * The surface this exists for is the **create form**, where the file has not
 * been uploaded yet and a 28px square is not enough to tell two captures of the
 * same screen apart. The images below are inline SVG data URIs so the story
 * works offline and in CI; a real staged file supplies an object URL instead,
 * and `useTicketAttachments` puts it in both `thumbnailUrl` and `downloadUrl`
 * because the local blob is the only copy that exists.
 *
 * The last row is a document — no preview, because there is nothing to show.
 */
const swatch = (label: string, background: string) =>
  `data:image/svg+xml;utf8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="900" height="600"><rect width="900" height="600" fill="${background}"/>` +
      `<text x="50%" y="50%" fill="#fff" font-family="sans-serif" font-size="72" text-anchor="middle" dominant-baseline="middle">${label}</text></svg>`,
  )}`

export const OpensAPreview: Story = {
  render: () => (
    <Stateful
      initial={[
        {
          id: '1', name: 'fees-screen-error.png', sizeBytes: 184_320, contentType: 'image/png',
          status: 'ready', thumbnailUrl: swatch('1', '#1F6FEB'), downloadUrl: swatch('1', '#1F6FEB'),
        },
        {
          id: '2', name: 'stack-trace.png', sizeBytes: 96_180, contentType: 'image/png',
          status: 'ready', thumbnailUrl: swatch('2', '#8250DF'), downloadUrl: swatch('2', '#8250DF'),
        },
        { id: '3', name: 'error-log.txt', sizeBytes: 12_884, contentType: 'text/plain', status: 'ready' },
      ]}
    />
  ),
}
