import * as React from 'react'
import { Check, Pencil, X } from 'lucide-react'

import { useUpdateTicket } from '@/api/generated/tickets/tickets'
import type { Module } from '@/api/generated/model/module'
import type { TicketPatchRequest } from '@/api/generated/model/ticketPatchRequest'
import { updateTicketBodyStepsToGenerateMax } from '@/api/generated/zod/tickets/tickets.zod'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import { RichTextView } from '@/components/ui/rich-text-view'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { toast } from '@/components/ui/use-toast'
import { ensureRichText } from '@/components/ui/rich-text'
import { isUnchanged, moduleName, moduleOptions, normalizeRichText, normalizeText } from './whereItHappened'

/**
 * C-069 · §7.5's four "where it happened" fields, inline-editable on S-20.
 *
 * Blueprint line 1083 places them exactly: "Module, Screen Name and Feature sit
 * in the summary panel directly under Type; **Steps to Generate** renders below
 * the description in the Details pane, where the person about to reproduce the
 * bug is already looking."
 *
 * ## Who may edit — and why there is no role gate
 *
 * The blueprint says "inline-editable by the roles that may edit the
 * description". `PATCH /tickets/{ticketId}` is `ticket.update_progress`, which
 * **all six roles hold** — `PermissionMatrix` carries the twelve rows C-067
 * added, taken off blueprint §2 rather than off whatever made a test pass. So
 * this is the same situation `TicketLinksControl` is in one row below, and it
 * takes the same shape: no `canEdit` prop, because there is no role to withhold
 * it from. What still gates every one of these is row scope, server-side: a
 * ticket outside the caller's scope is a 404 on the read, so the page never
 * renders at all.
 *
 * ## Why an earlier cycle does not disable these
 *
 * The comment box and the attachment strip go read-only on a sealed cycle, and
 * `TicketLevelControl` refuses too — a level is a fact about the current cycle's
 * SLA clock, so editing one while reading cycle 1 would perform the right act
 * under the wrong label. **None of that applies here.** `tickets.module_id`,
 * `screen_name`, `feature` and `steps_to_generate` are ticket columns with no
 * `cycle_no`; there is exactly one value and every cycle shows it. This is
 * `TicketLinksControl`'s argument verbatim, and it is right for the same reason:
 * where a bug happened does not become a different fact when the ticket reopens.
 *
 * ## No `If-Match`, and it is not silently ignored
 *
 * CONVENTIONS.md asks a `PATCH` to carry `If-Match` so a lost update is a 412
 * rather than a silent overwrite, and the contract declares the 412. Neither
 * end implements it: orval drops header parameters (the same gap C-010 found on
 * `useCreateTicket` and C-064 on the link create), and `TicketWriteController`
 * does not read the header. The exposure here is two people editing the same
 * short field within one another's page load, and the loser's value is
 * recoverable — every change writes a `FIELD_CHANGED` row carrying the old
 * value, so nothing is lost, only overwritten. Flagged in the folder README
 * rather than worked around with a hand-rolled header that only one of the two
 * ends would honour.
 */

/** Shared shape: a read row that turns into an editor, and turns back. */
function InlineEdit({
  fieldLabel,
  editing,
  isSaving,
  onEdit,
  onCancel,
  onSave,
  display,
  children,
}: {
  fieldLabel: string
  editing: boolean
  isSaving: boolean
  onEdit: () => void
  onCancel: () => void
  onSave: () => void
  display: React.ReactNode
  children: React.ReactNode
}) {
  if (!editing) {
    return (
      <span className="group/inline flex min-w-0 items-center gap-1.5">
        <span className="min-w-0 truncate">{display}</span>
        <button
          type="button"
          onClick={onEdit}
          aria-label={`Edit ${fieldLabel}`}
          className="shrink-0 rounded-[2px] p-0.5 text-content-muted opacity-0 transition-opacity focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary group-hover/inline:opacity-100"
        >
          <Pencil className="h-3.5 w-3.5" />
        </button>
      </span>
    )
  }

  return (
    <span className="flex flex-col gap-1.5">
      {children}
      <span className="flex items-center gap-1">
        <Button type="button" size="sm" onClick={onSave} disabled={isSaving}>
          <Check className="mr-1 h-3.5 w-3.5" />
          {isSaving ? 'Saving…' : 'Save'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel} disabled={isSaving}>
          <X className="mr-1 h-3.5 w-3.5" />
          Cancel
        </Button>
      </span>
    </span>
  )
}

/**
 * One `useUpdateTicket` per control rather than one shared by four.
 *
 * Each control is independently open, saving and failing, and a shared mutation
 * object would make `isPending` true on all four while one of them saved —
 * `TicketLinksControl` shares one across its rows precisely because there the
 * spinner has to be attributed by comparing `variables`, which is the more
 * awkward half of that trade and is not worth repeating for four fixed fields.
 */
function usePatchField(ticketId: string, onChanged: () => void) {
  const mutation = useUpdateTicket()

  const save = React.useCallback(
    (data: TicketPatchRequest, fieldLabel: string, onDone: () => void) => {
      mutation.mutate(
        { ticketId, data },
        {
          onSuccess: () => {
            onDone()
            onChanged()
          },
          onError: (error) => {
            toast({
              variant: 'danger',
              title: `${fieldLabel} was not saved`,
              description:
                error instanceof ApiError
                  ? error.problem.detail ?? error.problem.title
                  : 'Something went wrong. Try again.',
            })
          },
        },
      )
    },
    [mutation, ticketId, onChanged],
  )

  return { save, isSaving: mutation.isPending }
}

const Dash = () => <span className="text-content-muted">—</span>

export interface ModuleControlProps {
  ticketId: string
  moduleId: number | null | undefined
  modules: readonly Module[] | undefined
  onChanged: () => void
}

export function ModuleControl({ ticketId, moduleId, modules, onChanged }: ModuleControlProps) {
  const [editing, setEditing] = React.useState(false)
  const [draft, setDraft] = React.useState<number | null>(moduleId ?? null)
  const { save, isSaving } = usePatchField(ticketId, onChanged)

  const name = moduleName(modules, moduleId)
  const options = moduleOptions(modules, moduleId)

  const start = () => {
    setDraft(moduleId ?? null)
    setEditing(true)
  }

  const commit = () => {
    if ((moduleId ?? null) === draft) {
      setEditing(false)
      return
    }
    save({ moduleId: draft }, 'Module', () => setEditing(false))
  }

  return (
    <InlineEdit
      fieldLabel="module"
      editing={editing}
      isSaving={isSaving}
      onEdit={start}
      onCancel={() => setEditing(false)}
      onSave={commit}
      display={name ?? <Dash />}
    >
      <Select
        value={draft != null ? String(draft) : undefined}
        onValueChange={(value) => setDraft(Number(value))}
      >
        <SelectTrigger aria-label="Module" className="h-8">
          <SelectValue placeholder="Select a module" />
        </SelectTrigger>
        <SelectContent>
          {options.map((module) => (
            <SelectItem key={module.id} value={String(module.id)}>
              {module.name}
              {module.isActive === false && <span className="ml-1 text-content-muted">(retired)</span>}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </InlineEdit>
  )
}

export interface TextControlProps {
  ticketId: string
  /** Which column this edits — also what the `FIELD_CHANGED` row will name. */
  field: 'screenName' | 'feature'
  label: string
  value: string | null | undefined
  maxLength: number
  onChanged: () => void
}

export function TextControl({ ticketId, field, label, value, maxLength, onChanged }: TextControlProps) {
  const [editing, setEditing] = React.useState(false)
  const [draft, setDraft] = React.useState(value ?? '')
  const { save, isSaving } = usePatchField(ticketId, onChanged)

  const start = () => {
    setDraft(value ?? '')
    setEditing(true)
  }

  const commit = () => {
    const next = normalizeText(draft)
    // Nothing to say — and `ticket_history` cannot take a row back once it has
    // one, so not sending is cheaper than relying on the server to notice.
    if (isUnchanged(value, next)) {
      setEditing(false)
      return
    }
    save({ [field]: next }, label, () => setEditing(false))
  }

  return (
    <InlineEdit
      fieldLabel={label.toLowerCase()}
      editing={editing}
      isSaving={isSaving}
      onEdit={start}
      onCancel={() => setEditing(false)}
      onSave={commit}
      display={value || <Dash />}
    >
      <Input
        aria-label={label}
        value={draft}
        maxLength={maxLength}
        autoFocus
        className="h-8"
        onChange={(event) => setDraft(event.target.value)}
        // Enter saves and Escape abandons, because this is a one-line field in
        // a dense panel and reaching for the mouse to confirm a typo fix is the
        // whole friction inline editing exists to remove. The buttons stay:
        // a keyboard shortcut nobody is told about is not an affordance.
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            commit()
          }
          if (event.key === 'Escape') setEditing(false)
        }}
      />
    </InlineEdit>
  )
}

export interface StepsToGenerateSectionProps {
  ticketId: string
  steps: string | null | undefined
  onChanged: () => void
}

/**
 * §7.5's Steps to Generate, below the description — "what a developer needs in
 * order to reproduce it without going back to ask".
 *
 * A section rather than a summary row because it is the one field of the four
 * that is not a label-and-value: it is numbered steps and screenshots, and the
 * wireframe puts it where the person about to reproduce the bug is reading.
 */
export function StepsToGenerateSection({ ticketId, steps, onChanged }: StepsToGenerateSectionProps) {
  const [editing, setEditing] = React.useState(false)
  const [draft, setDraft] = React.useState(steps ?? '')
  const { save, isSaving } = usePatchField(ticketId, onChanged)

  const start = () => {
    setDraft(ensureRichText(steps ?? ''))
    setEditing(true)
  }

  const commit = () => {
    const next = normalizeRichText(draft)
    if (isUnchanged(steps, next)) {
      setEditing(false)
      return
    }
    save({ stepsToGenerate: next }, 'Steps to generate', () => setEditing(false))
  }

  return (
    <section
      aria-labelledby="ticket-steps-heading"
      className="rounded-card border border-border bg-surface p-6 shadow-rest"
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 id="ticket-steps-heading" className="text-h3 text-content">
          Steps to generate
        </h2>
        {!editing && (
          <Button type="button" size="sm" variant="ghost" onClick={start}>
            <Pencil className="mr-1 h-3.5 w-3.5" />
            Edit
          </Button>
        )}
      </div>

      {editing ? (
        <div className="flex flex-col gap-2">
          <RichTextEditor
            aria-label="Steps to generate"
            value={draft}
            onChange={setDraft}
            showCount
            maxLength={updateTicketBodyStepsToGenerateMax}
            placeholder="1. Open Fees → Receipts&#10;2. Print a receipt already printed once&#10;3. The duplicate watermark is missing"
          />
          <div className="flex items-center gap-1">
            <Button type="button" size="sm" onClick={commit} disabled={isSaving}>
              {isSaving ? 'Saving…' : 'Save'}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(false)} disabled={isSaving}>
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <RichTextView
          // Sanitised on the way in as well as on the way out — §3.9's rule is
          // that the client sanitises whatever it handles, and a row written
          // before the allow-list was last tightened is only protected if the
          // renderer applies today's list too. `RichTextView` does that itself.
          html={ensureRichText(steps ?? '')}
          emptyText="No steps were recorded. Add them and the next person can reproduce this without asking."
        />
      )}
    </section>
  )
}
