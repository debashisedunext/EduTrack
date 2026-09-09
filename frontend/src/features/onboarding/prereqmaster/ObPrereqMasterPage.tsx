import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { FileText, ListChecks, X } from 'lucide-react'

import {
  getGetObPrereqTemplateQueryKey,
  useAddObPrereqTemplateTask,
  useBeginObPrereqTemplateRevision,
  useGetObPrereqTemplate,
  usePublishObPrereqTemplate,
  useRemoveObPrereqTemplateTask,
  useUpdateObPrereqTemplateTask,
} from '@/api/generated/onboarding-masters/onboarding-masters'
import type { ObPrereqTemplateTask } from '@/api/generated/model/obPrereqTemplateTask'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

/**
 * B-124 · OB-14 — the prerequisites master.
 *
 * <h2>One checklist for the whole organisation, versioned rather than edited</h2>
 *
 * The API underneath is not "a list you edit". A version, once published, is
 * never written again — every client boarded against it keeps rendering exactly
 * the checklist they agreed to (plan §1.1 #2, applied to this master by name).
 * Changing anything means cloning the active version into a draft
 * (`beginObPrereqTemplateRevision`), editing the clone, and publishing it,
 * which swaps active versions in one transaction and touches no existing
 * client.
 *
 * The design draws direct editing and no revision controls, and this screen
 * matches it: the table's checkboxes and delete buttons are live, and the first
 * edit opens the draft itself (see {@link draftTasksFor}). What it will not do
 * is publish on every tick — three writes per click, a new version per
 * checkbox, and edits reaching boarded clients before anyone meant them to.
 *
 * So exactly one control is added to the design: a bar that appears once
 * something is unpublished, carrying the snapshot rule and the Publish button.
 * A draft nobody publishes is an admin's change that silently never reaches a
 * client, which is a worse outcome than a button the mockup does not have. When
 * there is nothing unpublished the bar is absent and the screen is the
 * mockup's.
 *
 * <h2>A fresh organisation is not a broken screen</h2>
 *
 * `GET /onboarding/prereq-template` with no `version` answers <b>404</b> when
 * nothing has been published and no draft is open, and
 * `ObPrereqTemplateController` gives the reason in its own words: an empty
 * `200` would say a master exists and is empty, which is a different thing
 * from one nobody has authored. So that particular 404 is the *first visit*,
 * not a failure — somebody arriving to write the first checklist — and it
 * renders as an empty state offering to start one.
 *
 * Everything else still renders as an error: 403, 5xx, a network failure, and
 * a 404 for an explicitly requested `version` (that one means a version that
 * was asked for by number is gone, which is a real fault). The distinguishing
 * fact is `draftVersion === null` — i.e. this screen asked for *the active
 * master* and was told there isn't one.
 *
 * <h2>Finding the draft again</h2>
 *
 * `GET /onboarding/prereq-template` returns the *active* version; a draft has
 * no name to ask for — it is whatever `isDraft` is true on, and only
 * `beginObPrereqTemplateRevision` returns it directly (its response is where
 * this screen learns the draft's version number). When begin answers 409
 * because a draft already exists — another admin's, or an earlier session's —
 * the screen adopts `active.version + 1`, which is the number the sequential
 * versioning gives the one outstanding draft, rather than dead-ending on an
 * error about state it could join.
 *
 * <h2>The reference-document input is disabled, with the reason beside it</h2>
 *
 * `addObPrereqTemplateTaskDoc` consumes an `attachmentId` from "the module's
 * shared attachment route" — and the only such route in the contract today is
 * client-scoped (`uploadObClientAttachment`). There is nothing this screen
 * could upload against, and fabricating an id would pass the mock and fail the
 * first real call. Disabled-with-reason over absent, on OB-08's own idiom,
 * because the design shows the field and the gap is the contract's, not the
 * screen's. Existing docs still render as chips — the read side is complete.
 */
export function ObPrereqMasterPage() {
  const queryClient = useQueryClient()

  const [draftVersion, setDraftVersion] = React.useState<number | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const [busy, setBusy] = React.useState(false)

  // The add-a-task form. Numbers held as strings while being typed, the same
  // call ObSettingsPage makes — coercing per keystroke fights the input.
  const [title, setTitle] = React.useState('')
  const [tat, setTat] = React.useState('3')
  const [description, setDescription] = React.useState('')
  const [mandatory, setMandatory] = React.useState(true)

  const templateQuery = useGetObPrereqTemplate(
    draftVersion === null ? undefined : { version: draftVersion },
  )
  const template = templateQuery.data?.data
  const editing = Boolean(template?.isDraft)

  /*
    The adopted draft version can be wrong — published or discarded between the
    409 and this fetch. Falling back to the active version keeps the screen on
    something real rather than a permanent error about a version that is gone.
  */
  React.useEffect(() => {
    if (draftVersion !== null && templateQuery.isError) {
      setDraftVersion(null)
      setError('The draft could not be loaded — it may just have been published. Showing the active version.')
    }
  }, [draftVersion, templateQuery.isError])

  const beginRevision = useBeginObPrereqTemplateRevision()
  const publishTemplate = usePublishObPrereqTemplate()
  const addTask = useAddObPrereqTemplateTask()
  const updateTask = useUpdateObPrereqTemplateTask()
  const removeTask = useRemoveObPrereqTemplateTask()

  // Prefix key, so both the active read and the draft read refetch.
  const refresh = React.useCallback(
    () => queryClient.invalidateQueries({ queryKey: getGetObPrereqTemplateQueryKey() }),
    [queryClient],
  )

  /*
    One in-flight write at a time. There is exactly one org-wide draft, so
    every control here mutates the same resource — serialising them is what
    keeps a delete and a reorder from racing each other's sequence numbers.
  */
  const apply = React.useCallback(
    async (work: () => Promise<unknown>, describe: (caught: unknown) => string = messageFor) => {
      setError(null)
      setBusy(true)
      try {
        await work()
        await refresh()
      } catch (caught) {
        setError(describe(caught))
      } finally {
        setBusy(false)
      }
    },
    [refresh],
  )

  const begin = () =>
    apply(async () => {
      try {
        const revision = await beginRevision.mutateAsync()
        setDraftVersion(revision.data.version)
      } catch (caught) {
        /*
          Any 409 from this route is "a draft already exists" — it is the only
          conflict `beginRevision` raises. Matched on the status as well as on
          the problem type because the server's own handler types all three of
          its 409s as the generic `errors/conflict`, so the type test alone
          matches the mock and misses production.

          Versions are sequential, so the one outstanding draft is active + 1;
          with no active version at all it can only be the first, v1.
        */
        if (caught instanceof ApiError && (caught.status === 409 || caught.is('ob-prereq-draft-exists'))) {
          setDraftVersion((template?.version ?? 0) + 1)
          return
        }
        throw caught
      }
    }, beginFailureMessage)

  /**
   * The draft, opened on demand.
   *
   * <p>The design edits the checklist in place and has no "begin a revision"
   * button, while the API refuses every write that is not against a draft — so
   * the first edit opens one. Returns the draft's tasks, because opening a
   * revision <b>re-identifies every task</b>: `cloneTasks` copies the active
   * version's rows into new ones with new ids, so the id a row was drawn with
   * belongs to the published version and writing to it is refused. `sequence`
   * is carried across the clone deliberately, by both the server and the mock,
   * which makes it the one key that survives the boundary.
   */
  const draftTasksFor = async (): Promise<ObPrereqTemplateTask[]> => {
    if (template?.isDraft) return template.tasks
    try {
      const revision = await beginRevision.mutateAsync()
      setDraftVersion(revision.data.version)
      return revision.data.tasks
    } catch (caught) {
      /*
        Somebody else already has the org's one draft open. Join it — but stop
        here rather than applying this edit to it: a 409 carries no tasks, so
        there is nothing to match `sequence` against, and guessing would write
        to whichever id happened to be on screen. The screen loads their draft
        and asks for the edit again, against rows that are really there.
      */
      if (caught instanceof ApiError && (caught.status === 409 || caught.is('ob-prereq-draft-exists'))) {
        setDraftVersion((template?.version ?? 0) + 1)
        throw new DraftJoined()
      }
      throw caught
    }
  }

  /** The same task, on the draft — matched across the clone by `sequence`. */
  const onDraft = (draftTasks: ObPrereqTemplateTask[], task: ObPrereqTemplateTask) =>
    draftTasks.find((t) => t.sequence === task.sequence) ?? task

  const publish = () =>
    apply(async () => {
      const published = await publishTemplate.mutateAsync()
      setDraftVersion(null)
      toast({
        variant: 'success',
        title: `Version ${published.data.version} published`,
        description:
          'Existing clients keep the snapshot they were boarded with — the new checklist applies from the next boarding.',
      })
    })

  const submitAdd = (event: React.FormEvent) => {
    event.preventDefault()
    if (!title.trim()) return
    void apply(async () => {
      await draftTasksFor()
      await addTask.mutateAsync({
        data: {
          title: title.trim(),
          description: description.trim() ? description.trim() : undefined,
          tatDays: Math.max(1, Number(tat) || 1),
          isMandatory: mandatory,
        },
      })
      setTitle('')
      setTat('3')
      setDescription('')
      setMandatory(true)
    })
  }

  /*
    The write request is the whole task, so the flip carries every other field
    unchanged — `PATCH` with `If-Match` on the server side is what turns a
    stale copy of those fields into a 412 instead of a silent overwrite.
  */
  const toggleMandatory = (task: ObPrereqTemplateTask) =>
    apply(async () => {
      const target = onDraft(await draftTasksFor(), task)
      await updateTask.mutateAsync({
        templateTaskId: target.id,
        data: {
          title: target.title,
          description: target.description ?? undefined,
          tatDays: target.tatDays,
          isMandatory: !task.isMandatory,
          isActive: target.isActive,
        },
      })
    })

  const remove = (task: ObPrereqTemplateTask) =>
    apply(async () => {
      const target = onDraft(await draftTasksFor(), task)
      await removeTask.mutateAsync({ templateTaskId: target.id })
    })

  if (templateQuery.isPending) {
    return (
      <div className="max-w-[900px] p-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="mt-4 h-64 w-full" />
      </div>
    )
  }

  /*
    The one 404 that is not a fault: nobody has authored a master yet. Narrow
    on purpose — only the read this screen makes for *the active master*
    (`draftVersion === null`) can mean "not authored yet". A 404 on a version
    asked for by number means a version that should exist does not, which is
    the error branch below.
  */
  if (
    draftVersion === null &&
    templateQuery.isError &&
    templateQuery.error instanceof ApiError &&
    templateQuery.error.status === 404
  ) {
    return (
      <div className="max-w-[900px] p-6">
        <PageHeading />
        {error && <ErrorNote className="mt-4">{error}</ErrorNote>}
        <div className="mt-5 rounded-card border border-border bg-surface shadow-rest">
          <EmptyState
            icon={<ListChecks className="h-6 w-6" strokeWidth={1.5} />}
            title="No prerequisites checklist has been authored yet"
            description={
              'This is the checklist every newly boarded client is asked for, and nobody has written it. ' +
              'Its mandatory tasks gate every client journey, so a client boarded now would have nothing ' +
              'to clear. Starting one opens a draft — nothing reaches a client until you publish it.'
            }
            action={
              <Button onClick={begin} disabled={busy}>
                Author the first checklist
              </Button>
            }
          />
        </div>
      </div>
    )
  }

  if (templateQuery.isError || !template) {
    return (
      <div className="max-w-[900px] p-6">
        <PageHeading />
        <ErrorNote className="mt-5">{messageFor(templateQuery.error)}</ErrorNote>
      </div>
    )
  }

  const tasks = template.tasks
  const mandatoryCount = template.mandatoryCount ?? tasks.filter((t) => t.isMandatory).length

  return (
    <div className="max-w-[900px] p-6">
      <PageHeading />

      {/*
        The one thing on this screen the design does not draw, and the one that
        cannot be dropped. Editing opens a draft, and a draft nobody publishes
        is an admin's change that silently never reaches a client — worse than
        the button. It appears only once there is something unpublished, so the
        resting screen is the design's.
      */}
      {editing && (
        <div className="mt-4 flex flex-wrap items-center gap-3 rounded-card border border-warning bg-surface p-3 shadow-rest">
          <Chip variant="warning">Unpublished changes</Chip>
          <p className="flex-1 text-caption text-content-muted">
            Version {template.version} is a draft — {mandatoryCount} mandatory{' '}
            {mandatoryCount === 1 ? 'task gates' : 'tasks gate'} every journey. Existing clients
            keep the checklist they were boarded with; publishing applies this one to clients
            boarded after it.
          </p>
          <Button onClick={publish} disabled={busy}>
            Publish version {template.version}
          </Button>
        </div>
      )}

      {error && <ErrorNote className="mt-4">{error}</ErrorNote>}

      <TableContainer className="mt-5 bg-surface shadow-rest">
        <Table>
          <caption className="sr-only">
            Every task on version {template.version} of the prerequisites master
          </caption>
          <TableHeader>
            <tr>
              <TableHead scope="col">Task</TableHead>
              <TableHead scope="col" className="w-24">TAT (days)</TableHead>
              <TableHead scope="col" className="w-28">Mandatory</TableHead>
              <TableHead scope="col">Reference doc for client</TableHead>
              <TableHead scope="col" className="w-16">
                <span className="sr-only">Actions</span>
              </TableHead>
            </tr>
          </TableHeader>
          <TableBody>
            {tasks.map((task) => (
              <TableRow key={task.id}>
                <TableCell>
                  <span className="font-semibold text-content">{task.title}</span>
                  {task.description && (
                    <span className="mt-0.5 block text-caption text-content-muted">
                      {task.description}
                    </span>
                  )}
                </TableCell>
                <TableCell className="tabular-nums">{task.tatDays}</TableCell>
                <TableCell>
                  <input
                    type="checkbox"
                    checked={task.isMandatory}
                    disabled={busy}
                    aria-label={`Mandatory: ${task.title}`}
                    onChange={() => void toggleMandatory(task)}
                  />
                </TableCell>
                <TableCell>
                  {task.docs.length > 0 ? (
                    <span className="flex flex-wrap gap-1">
                      {task.docs.map((doc) => (
                        <Chip key={doc.id} variant="info">
                          <FileText className="h-3 w-3" aria-hidden />
                          {doc.label}
                        </Chip>
                      ))}
                    </span>
                  ) : (
                    <span className="text-caption text-content-muted">—</span>
                  )}
                </TableCell>
                <TableCell>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={busy}
                    aria-label={`Delete ${task.title}`}
                    onClick={() => void remove(task)}
                  >
                    <X className="h-4 w-4" aria-hidden />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {tasks.length === 0 && (
          <EmptyState
            title="No tasks on this version"
            description="Add the first task below."
          />
        )}
      </TableContainer>

      <form
        onSubmit={submitAdd}
        aria-labelledby="pm-add-heading"
        className="mt-5 rounded-card border border-border bg-surface p-5 shadow-rest"
      >
        <h2
          id="pm-add-heading"
          className="text-caption font-semibold uppercase tracking-wider text-content-muted"
        >
          Add a task
        </h2>
        <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="pm-title" className="block text-sm font-medium text-content">
              Title *
            </label>
            <Input
              id="pm-title"
              className="mt-1"
              value={title}
              maxLength={200}
              placeholder="e.g. Share GST certificate"
              disabled={busy}
              onChange={(e) => setTitle(e.target.value)}
              required
            />
          </div>
          <div>
            <label htmlFor="pm-tat" className="block text-sm font-medium text-content">
              TAT (working days)
            </label>
            <Input
              id="pm-tat"
              className="mt-1 w-32"
              type="number"
              min={1}
              max={365}
              value={tat}
              disabled={busy}
              onChange={(e) => setTat(e.target.value)}
            />
          </div>
          <div className="sm:col-span-2">
            <label htmlFor="pm-desc" className="block text-sm font-medium text-content">
              Description for the client
            </label>
            <Input
              id="pm-desc"
              className="mt-1"
              value={description}
              maxLength={4000}
              placeholder="What exactly do they need to do?"
              disabled={busy}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="pm-doc" className="block text-sm font-medium text-content">
              Reference document (attached for the client)
            </label>
            <Input
              id="pm-doc"
              className="mt-1"
              placeholder="e.g. GST_format_sample.pdf"
              disabled
              aria-describedby="pm-doc-note"
            />
            {/* See the docstring — the contract has no master-scoped upload yet. */}
            <p id="pm-doc-note" className="mt-1 text-caption text-content-muted">
              Needs the module&apos;s attachment upload, which has no master-scoped route yet.
            </p>
          </div>
          <div className="flex items-end justify-between gap-4">
            <label className="flex items-center gap-2 text-sm text-content">
              <input
                type="checkbox"
                checked={mandatory}
                disabled={busy}
                onChange={(e) => setMandatory(e.target.checked)}
              />
              Mandatory (gates the journeys)
            </label>
            <Button type="submit" disabled={busy || !title.trim()}>
              + Add to master
            </Button>
          </div>
        </div>
      </form>
    </div>
  )
}

/**
 * The title and caption, which every state of this screen carries — including
 * the two that render nothing else. An error or a first visit is still the
 * prerequisites master, and a page that answers with a bare red sentence and
 * no heading is the thing this screen was reported for.
 */
function PageHeading() {
  return (
    <div>
      <h1 className="text-lg font-semibold text-content">Prerequisites master</h1>
      <p className="mt-1 max-w-2xl text-caption text-content-muted">
        The default client-responsibility checklist — applied to every newly boarded client.
        Mandatory tasks gate every journey; existing clients keep the snapshot they were boarded
        with.
      </p>
    </div>
  )
}

function ErrorNote({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <p
      role="alert"
      className={cn('rounded-card border border-danger p-3 text-sm text-danger-text', className)}
    >
      {children}
    </p>
  )
}

/**
 * `beginRevision` needs no active version — the server opens v1 against an
 * empty master (`ObPrereqTemplateService.beginRevision` clones the active
 * version's tasks only `ifPresent`). A 404 from it therefore means the API
 * behind this screen does not do that, and the admin needs to be told the
 * draft was *not* opened rather than shown a bare "not found".
 */
function beginFailureMessage(caught: unknown): string {
  if (caught instanceof ApiError && caught.status === 404) {
    return 'No draft was opened — this API would not start a checklist from nothing. Nothing has been changed; the master needs to be seeded server-side.'
  }
  return messageFor(caught)
}

/**
 * Not a failure of the server's — the edit was stopped deliberately because
 * this session joined a draft somebody else had open. See {@link
 * ObPrereqMasterPage}'s `draftTasksFor`.
 */
class DraftJoined extends Error {}

function messageFor(caught: unknown): string {
  if (caught instanceof DraftJoined) {
    return 'Somebody else already had a revision open, so you are now editing theirs. Your change was not applied — make it again.'
  }
  if (caught instanceof ApiError) {
    if (caught.status === 403) {
      return 'The prerequisites master is OB Admin only.'
    }
    if (caught.is('ob-prereq-no-draft')) {
      return 'There is no draft to edit — begin a revision first.'
    }
    if (caught.status === 412) {
      return 'Somebody else changed this draft while you were editing. Reload the page and reapply your change.'
    }
    // 422s carry the rule in their own words — "a published checklist with no
    // mandatory task would clear its own gate at boarding" is better said by
    // the server that refuses it than paraphrased here.
    if (caught.problem.detail) {
      return caught.problem.detail
    }
  }
  return 'That did not go through. Please try again.'
}

export default ObPrereqMasterPage
