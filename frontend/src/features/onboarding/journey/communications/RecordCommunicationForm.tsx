import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import {
  getListObClientCommunicationsQueryKey,
  getListObStepCommunicationsQueryKey,
  useCreateObStepCommunication,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { ObStepCommunicationCreateRequestChannel } from '@/api/generated/model/obStepCommunicationCreateRequestChannel'
import { Button } from '@/components/ui/button'
import { toast } from '@/components/ui/use-toast'
import { channelLook, RECORDABLE_CHANNELS } from './communicationEntry'

/**
 * C-112 · "record a call, mail or meeting against this service".
 *
 * ## Capture, not delivery
 *
 * The contract's own opening line, and it is the thing this form must not
 * imply. Nothing here sends anything: §7's outbox is what mails and messages a
 * client, and a form that read *Send* would put a note somebody typed into an
 * inbox. The submit button says **Record**, and the panel's own heading says
 * what the timeline is.
 *
 * ## Internal is the default, and it is a checkbox rather than a toggle
 *
 * `is_client_visible` defaults to 0 in the column, false in the contract and
 * false here — three layers agreeing rather than one trusting the others. The
 * DDL says why it is worth stating three times: *an internal note that reaches
 * the portal because a default went the other way is not recoverable by
 * deleting it afterwards*, because the client has already read it.
 *
 * So the control is unchecked on open, it is reset to unchecked after every
 * successful record rather than remembering the last answer, and its label
 * states the **consequence** ("Publish to the client portal") rather than the
 * field name. A toggle that remembered would mean one deliberate publish makes
 * the next five accidental ones.
 *
 * ## `occurredAt` defaults to now and is editable
 *
 * The one field whose default is a convenience rather than a safety rule.
 * Most entries are typed within minutes of the conversation; the Friday call
 * recorded on Monday is the case the field exists for, and it costs one date
 * input to serve.
 */
export function RecordCommunicationForm({ stepId, obClientId }: { stepId: number; obClientId?: number }) {
  const queryClient = useQueryClient()
  const [channel, setChannel] = React.useState<(typeof RECORDABLE_CHANNELS)[number]>('CALL')
  const [summary, setSummary] = React.useState('')
  const [occurredAtLocal, setOccurredAtLocal] = React.useState(() => nowForInput())
  const [isClientVisible, setIsClientVisible] = React.useState(false)

  const create = useCreateObStepCommunication({
    mutation: {
      onSuccess: () => {
        /*
         * Both timelines, not just this one. An entry recorded from the step
         * panel belongs on the client's stitched view as well, and the two are
         * different query keys over the same table — invalidating only the one
         * on screen is how a reader comes to see six entries here and five on
         * the tab beside it.
         */
        void queryClient.invalidateQueries({ queryKey: getListObStepCommunicationsQueryKey(stepId) })
        if (obClientId != null) {
          void queryClient.invalidateQueries({
            queryKey: getListObClientCommunicationsQueryKey(obClientId),
          })
        }
        setSummary('')
        // Back to internal, every time. See the class docstring.
        setIsClientVisible(false)
        setOccurredAtLocal(nowForInput())
        toast({ title: 'Communication recorded', variant: 'success' })
      },
      onError: () => {
        toast({
          title: 'Could not record that',
          description: 'Nothing was saved. Check the summary and try again.',
          variant: 'danger',
        })
      },
    },
  })

  const trimmed = summary.trim()
  const canSubmit = trimmed.length > 0 && !create.isPending

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    create.mutate({
      stepId,
      data: {
        channel: channel as ObStepCommunicationCreateRequestChannel,
        // The input is local wall-clock; storage is UTC everywhere (CLAUDE.md).
        occurredAt: new Date(occurredAtLocal).toISOString(),
        summary: trimmed,
        isClientVisible,
      },
    })
  }

  return (
    <form className="mt-3 flex flex-col gap-2 border-t border-border pt-3" onSubmit={submit}>
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-caption text-content-muted">
          Channel
          <select
            className="h-9 rounded-control border border-border bg-surface px-2 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
            value={channel}
            onChange={(event) =>
              setChannel(event.target.value as (typeof RECORDABLE_CHANNELS)[number])
            }
          >
            {RECORDABLE_CHANNELS.map((value) => (
              <option key={value} value={value}>
                {channelLook(value).label}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-caption text-content-muted">
          When it happened
          <input
            type="datetime-local"
            className="h-9 rounded-control border border-border bg-surface px-2 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
            value={occurredAtLocal}
            onChange={(event) => setOccurredAtLocal(event.target.value)}
          />
        </label>
      </div>

      <label className="flex flex-col gap-1 text-caption text-content-muted">
        What was said
        <textarea
          className="min-h-[72px] rounded-control border border-border bg-surface p-2 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
          value={summary}
          maxLength={4000}
          onChange={(event) => setSummary(event.target.value)}
          placeholder="Spoke to the SPOC about…"
        />
      </label>

      <label className="flex items-center gap-2 text-sm text-content">
        <input
          type="checkbox"
          className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary focus:ring-offset-1"
          checked={isClientVisible}
          onChange={(event) => setIsClientVisible(event.target.checked)}
        />
        Publish to the client portal
      </label>
      {isClientVisible && (
        <p className="m-0 text-caption text-warning-text" role="status">
          The client will be able to read this, and it cannot be unpublished.
        </p>
      )}

      <div>
        <Button type="submit" size="sm" disabled={!canSubmit}>
          {create.isPending ? 'Recording…' : 'Record'}
        </Button>
      </div>
    </form>
  )
}

/**
 * `datetime-local` wants the browser's own clock in its own format, with no
 * zone marker. Storage is UTC; this is the presentation layer, which is the
 * one place CLAUDE.md puts a user's timezone.
 */
function nowForInput(): string {
  const now = new Date()
  const offsetMs = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offsetMs).toISOString().slice(0, 16)
}
