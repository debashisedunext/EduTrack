import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import { getGetObJourneyQueryKey } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import type { UserRef } from '@/api/generated/model/userRef'
import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { BLOCK_REASONS } from '@/features/onboarding/journey/clientDetail/blockReasons'
import { cn } from '@/lib/utils'

import { ObTaskCommunications } from './ObTaskCommunications'
import type { ProjectTask } from './useProjectTasks'
import { useUploadObJourneyStepAttachment } from './uploadObJourneyStepAttachment'

/**
 * The four forms behind {@link ObTaskActionBar}, and the timeline behind its
 * fifth button.
 *
 * <h2>Why these are not `BlockStepDialog`</h2>
 *
 * <p>`journey/clientDetail/BlockStepDialog` is the same shape and says
 * <em>"Block this service"</em> — it blocks a journey, and its every noun is
 * the service. Pointed at a task it would put the wrong word on the screen in
 * its title, its description and its confirm button. Editing it to take a noun
 * would be editing Stream C's directory, which needs their sign-off, and would
 * make one component serve two screens for the sake of sixty lines.
 *
 * <p>So the block dialog is written here, in this feature's own words. What it
 * does keep from theirs is the part worth keeping: the reason is a
 * <em>code</em> from `blockReasons.ts` rather than free text, because the
 * reason exists to answer "where is work stuck" across the whole module and
 * twenty owners typing the same obstacle produce twenty categories.
 *
 * <h2>Blocked and Waiting on client are not interchangeable</h2>
 *
 * <p>Plan §5.7: an internal `BLOCKED` keeps the TAT clock <b>running</b> and
 * charges the delay to us; `WAITING_ON_CLIENT` <b>pauses</b> it and attributes
 * the wait to the client. An owner who picks the wrong one is not making a
 * cosmetic mistake — they are moving a breach onto the wrong party's account.
 * Every dialog here that blocks says so in its own description.
 */

const FIELD =
  'w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ' +
  'disabled:cursor-not-allowed disabled:bg-subtle disabled:text-content-muted'

const LABEL = 'block text-sm font-medium text-content'

/* ── Block ──────────────────────────────────────────────────────────────── */

export interface ObTaskBlockDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  isPending: boolean
  onConfirm: (reasonCode: string, note: string | null) => void
}

export function ObTaskBlockDialog({
  open,
  onOpenChange,
  isPending,
  onConfirm,
}: ObTaskBlockDialogProps) {
  const [reasonCode, setReasonCode] = React.useState('')
  const [note, setNote] = React.useState('')
  const selectId = React.useId()
  const noteId = React.useId()

  // Cleared on open rather than on close, so a refusal leaves what was typed
  // on screen for the reader to correct.
  React.useEffect(() => {
    if (open) {
      setReasonCode('')
      setNote('')
    }
  }, [open])

  const selected = BLOCK_REASONS.find((r) => r.code === reasonCode)

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent data-testid="ob-task-block-dialog">
        <ModalHeader>
          <ModalTitle>Block this task</ModalTitle>
          <ModalDescription>
            The TAT clock keeps running while a task is blocked — an internal hold is our delay, not
            the client&rsquo;s. If you are waiting on the client, close this and choose{' '}
            <strong>Waiting on client</strong> instead, which pauses the clock.
          </ModalDescription>
        </ModalHeader>

        <label htmlFor={selectId} className={LABEL}>
          What is blocking it
        </label>
        <select
          id={selectId}
          value={reasonCode}
          onChange={(e) => setReasonCode(e.target.value)}
          className={cn(FIELD, 'mt-1')}
        >
          <option value="">Choose a reason…</option>
          {BLOCK_REASONS.map((reason) => (
            <option key={reason.code} value={reason.code}>
              {reason.label}
            </option>
          ))}
        </select>
        {selected && <p className="mt-1 text-caption text-content-muted">{selected.hint}</p>}

        <label htmlFor={noteId} className={cn(LABEL, 'mt-4')}>
          Detail <span className="font-normal text-content-muted">(optional)</span>
        </label>
        <textarea
          id={noteId}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={3}
          maxLength={500}
          className={cn(FIELD, 'mt-1')}
        />

        <ModalFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={!reasonCode || isPending}
            onClick={() => onConfirm(reasonCode, note.trim() || null)}
          >
            Block task
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/* ── Waiting on another service ─────────────────────────────────────────── */

/** The reason `Waiting on service` sends. One of `BLOCK_REASONS`, not a sixth. */
export const DEPENDENCY_REASON = 'dependency-not-ready'

export interface ObTaskWaitingOnServiceDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  isPending: boolean
  onConfirm: (note: string | null) => void
}

export function ObTaskWaitingOnServiceDialog({
  open,
  onOpenChange,
  isPending,
  onConfirm,
}: ObTaskWaitingOnServiceDialogProps) {
  const [note, setNote] = React.useState('')
  const noteId = React.useId()

  React.useEffect(() => {
    if (open) setNote('')
  }, [open])

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent data-testid="ob-task-waiting-service-dialog">
        <ModalHeader>
          <ModalTitle>Waiting on another service</ModalTitle>
          <ModalDescription>
            Blocks this task as a dependency. The TAT clock <strong>keeps running</strong> — this is
            our delay, not the client&rsquo;s.
          </ModalDescription>
        </ModalHeader>

        <label htmlFor={noteId} className={LABEL}>
          Detail <span className="font-normal text-content-muted">(optional)</span>
        </label>
        <textarea
          id={noteId}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={3}
          maxLength={500}
          placeholder="Which service, and what you are waiting for"
          className={cn(FIELD, 'mt-1')}
        />
        {/*
          Typed rather than picked. The block endpoint carries a reason code and
          a free-text note and no field for a service, so a service picker here
          would be a control whose answer the API has nowhere to put.
        */}
        <p className="mt-1 text-caption text-content-muted">
          Named here rather than picked from a list — the block endpoint carries a reason and a note,
          and no field for a service.
        </p>

        <ModalFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button variant="danger" disabled={isPending} onClick={() => onConfirm(note.trim() || null)}>
            Mark waiting
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/* ── Reassign ───────────────────────────────────────────────────────────── */

export interface ObTaskReassignDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  isPending: boolean
  task: ProjectTask
  users: readonly UserRef[]
  ownerName: string
  onConfirm: (ownerUserId: number | null) => void
}

export function ObTaskReassignDialog({
  open,
  onOpenChange,
  isPending,
  task,
  users,
  ownerName,
  onConfirm,
}: ObTaskReassignDialogProps) {
  const [assignee, setAssignee] = React.useState<number | ''>(task.ownerUserId ?? '')
  const selectId = React.useId()

  React.useEffect(() => {
    if (open) setAssignee(task.ownerUserId ?? '')
  }, [open, task.ownerUserId])

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent data-testid="ob-task-reassign-dialog">
        <ModalHeader>
          <ModalTitle>Reassign this task</ModalTitle>
          <ModalDescription>
            {task.ownerUserId != null
              ? `Currently ${ownerName}.`
              : 'Nobody is assigned to this task.'}{' '}
            Reassigning is an OB Manager or OB Admin action — an owner cannot move work off
            themselves.
          </ModalDescription>
        </ModalHeader>

        <label htmlFor={selectId} className={LABEL}>
          Implementor
        </label>
        <select
          id={selectId}
          value={assignee}
          onChange={(e) => setAssignee(e.target.value === '' ? '' : Number(e.target.value))}
          className={cn(FIELD, 'mt-1')}
        >
          <option value="">Unassigned</option>
          {users.map((u) => (
            <option key={u.id} value={u.id}>
              {u.displayName}
            </option>
          ))}
        </select>
        {/*
          Unassigned is a real choice rather than an empty state: a task with no
          owner falls to the project's implementor, which is what
          `ownerIsInherited` records on the row.
        */}
        <p className="mt-1 text-caption text-content-muted">
          Unassigned is a real choice — the task then falls to the project&rsquo;s implementor.
        </p>

        <ModalFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={isPending || assignee === (task.ownerUserId ?? '')}
            onClick={() => onConfirm(assignee === '' ? null : assignee)}
          >
            Reassign
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/* ── Required documents ─────────────────────────────────────────────────── */

export interface ObTaskDocsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  task: ProjectTask
}

/**
 * What this task needs attached, and whether it is in.
 *
 * <p><b>Upload is not possible yet.</b> `ob_attachments.step_id` exists,
 * `ObAttachmentOwner.STEP` exists, and the completion gate already counts a
 * task's clean attachments to decide whether its required documents are
 * satisfied — but the only upload controllers are client-level and the portal's
 * prerequisite one. `POST /onboarding/journey-steps/{stepId}/attachments` is
 * not in the contract.
 *
 * <p>The dialog ships anyway because the list is the useful half: "which
 * documents does this task need, and are they in" is what holds Complete, and
 * with no dialog that question has nowhere on the screen to be asked. The
 * control that cannot work is disabled and says why, rather than being absent
 * and leaving a reader to wonder where uploading lives.
 */
export function ObTaskDocsDialog({ open, onOpenChange, task }: ObTaskDocsDialogProps) {
  const satisfied = task.docs.filter((d) => d.isSatisfied).length
  const inputId = React.useId()
  const upload = useUploadObJourneyStepAttachment()
  const queryClient = useQueryClient()
  const [file, setFile] = React.useState<File | null>(null)

  React.useEffect(() => {
    if (open) setFile(null)
  }, [open])

  const submit = () => {
    if (!file) return
    upload.mutate(
      { stepId: task.id, data: { file } },
      {
        onSuccess: () => {
          void queryClient.invalidateQueries({ queryKey: getGetObJourneyQueryKey(task.journeyId) })
          onOpenChange(false)
        },
      },
    )
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent data-testid="ob-task-docs-dialog">
        <ModalHeader>
          <ModalTitle>Required documents</ModalTitle>
          <ModalDescription>
            {task.name} — {satisfied} of {task.docs.length} satisfied. A required document with
            nothing against it refuses Mark complete.
          </ModalDescription>
        </ModalHeader>

        <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
          {task.docs.map((doc) => (
            <li key={doc.id} data-testid="ob-task-doc-row" className="flex items-center gap-2.5 text-sm">
              <span
                aria-hidden="true"
                className={cn(
                  'grid size-[18px] shrink-0 place-items-center rounded-[5px] text-[10px] font-bold',
                  doc.isSatisfied
                    ? 'bg-success text-white'
                    : 'border border-content-muted text-transparent',
                )}
              >
                ✓
              </span>
              <span className={doc.isSatisfied ? 'text-content' : 'text-content-muted'}>
                {doc.label}
              </span>
              {!doc.isRequired && (
                <span className="rounded-chip bg-subtle px-2 py-px text-[10.5px] text-content-muted">
                  optional
                </span>
              )}
              <span className="min-w-0 flex-1" aria-hidden="true" />
              <span className="text-caption text-content-muted">
                {doc.isSatisfied ? 'Attached' : 'Missing'}
              </span>
            </li>
          ))}
        </ul>

        <label htmlFor={inputId} className="mt-4 block text-sm font-medium text-content">
          Document
          <input
            id={inputId}
            type="file"
            className="mt-1 block w-full text-sm text-content-muted"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            disabled={upload.isPending}
          />
        </label>

        <ModalFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)}>
            Close
          </Button>
          <Button variant="primary" disabled={!file || upload.isPending} onClick={submit}>
            Upload
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/* ── Communication ──────────────────────────────────────────────────────── */

export interface ObTaskCommunicationsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  task: ProjectTask
}

/**
 * The timeline and its form, together.
 *
 * <p>Moving the form into a dialog without the entries would make a reader open
 * one thing to write and another to read — and the entries are how somebody
 * decides whether there is anything left to say. The whole panel goes in, which
 * is what takes five rows off the task and puts nothing back.
 *
 * <p>Its read fires when the dialog opens rather than when the task does, which
 * is the request this move was worth on its own.
 */
export function ObTaskCommunicationsDialog({
  open,
  onOpenChange,
  task,
}: ObTaskCommunicationsDialogProps) {
  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent data-testid="ob-task-communications-dialog" className="max-w-xl">
        <ModalHeader>
          <ModalTitle>Communication on this task</ModalTitle>
          <ModalDescription>
            {task.name} — append-only. Corrections are new entries, never edits.
          </ModalDescription>
        </ModalHeader>

        {/* Writable by any staff viewer: recording that a call happened is a log
            entry, not a task mutation, and `ObStepCommunication.recordedBy`
            exists because anyone might add one. */}
        {open && <ObTaskCommunications stepId={task.id} canPost />}
      </ModalContent>
    </Modal>
  )
}
