import * as React from 'react'
import { Link, useParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'

import {
  addPortalPrereqComment,
  getGetPortalPrereqTaskQueryKey,
  getListPortalPrereqCommentsQueryKey,
  submitPortalPrereqTask,
  uploadPortalPrereqAttachment,
  useGetPortalPrereqTask,
  useListPortalPrereqComments,
} from '@/api/generated/portal/portal'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

/**
 * CP-04 · prerequisite task detail — description, reference docs, comment
 * thread, uploads, Submit for verification.
 *
 * `ObClientPrereqTaskDetail`'s own contract description: "one schema for
 * both principals". This page renders exactly the fields a staff task-row
 * component would, because nothing on the row is on plan §11's never-visible
 * list — see `PortalOnboardingDtos`'s class note.
 */
export function PortalPrereqTaskDetailPage() {
  const params = useParams<{ prereqTaskId: string }>()
  const prereqTaskId = Number(params.prereqTaskId)
  const queryClient = useQueryClient()

  const { data, isPending, isError } = useGetPortalPrereqTask(prereqTaskId, {
    query: { enabled: Number.isFinite(prereqTaskId) },
  })

  const [submitting, setSubmitting] = React.useState(false)

  if (!Number.isFinite(prereqTaskId)) {
    return <EmptyState title="Task not found" />
  }

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-32 w-full rounded-card" />
        <Skeleton className="h-48 w-full rounded-card" />
      </div>
    )
  }

  if (isError || !data) {
    return <EmptyState title="Could not load this task" description="It may not belong to your account." />
  }

  const task = data.data

  const refetch = () => queryClient.invalidateQueries({ queryKey: getGetPortalPrereqTaskQueryKey(prereqTaskId) })

  const onSubmitForVerification = async () => {
    setSubmitting(true)
    try {
      await submitPortalPrereqTask(prereqTaskId, {})
      toast({ title: 'Sent for verification' })
      await refetch()
    } catch (error) {
      toast({
        title: 'Could not submit',
        description: error instanceof ApiError ? error.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  const canSubmit = task.status === 'PENDING'

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link to="/portal/onboarding" className="text-sm text-primary hover:underline">
          ← Back to onboarding
        </Link>
      </div>

      <section className="rounded-card border border-border bg-surface p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-h2 text-content">{task.title}</h1>
            <p className="mt-1 text-sm text-content-muted">
              {task.isMandatory ? 'Required' : 'Optional'} · Due{' '}
              {new Date(task.dueAt).toLocaleDateString()}
            </p>
          </div>
          <StatusChip status={task.status} isOverdue={task.isOverdue} />
        </div>

        {task.description ? (
          <p className="mt-4 whitespace-pre-wrap text-sm text-content">{task.description}</p>
        ) : null}

        {data.data.referenceDocs.length > 0 ? (
          <div className="mt-4">
            <h2 className="text-sm font-semibold text-content">Reference documents</h2>
            <ul className="mt-2 flex flex-col gap-1">
              {data.data.referenceDocs.map((doc) => (
                <li key={doc.id} className="text-sm">
                  {doc.downloadUrl ? (
                    <a href={doc.downloadUrl} className="text-primary hover:underline" target="_blank" rel="noreferrer">
                      {doc.label} — {doc.fileName}
                    </a>
                  ) : (
                    <span className="text-content-muted">{doc.label} — {doc.fileName}</span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        {task.skipReason ? (
          <p className="mt-4 text-sm text-content-muted">Waived: {task.skipReason}</p>
        ) : null}

        {canSubmit ? (
          <div className="mt-5">
            <Button onClick={onSubmitForVerification} disabled={submitting}>
              {submitting ? 'Submitting…' : 'Submit for verification'}
            </Button>
          </div>
        ) : null}
      </section>

      <UploadsCard prereqTaskId={prereqTaskId} submissions={data.data.submissions} onUploaded={refetch} />

      <CommentsCard prereqTaskId={prereqTaskId} />
    </div>
  )
}

function StatusChip({ status, isOverdue }: { status: string; isOverdue?: boolean }) {
  if (status === 'VERIFIED') return <Chip variant="success">Verified</Chip>
  if (status === 'SKIPPED') return <Chip variant="neutral">Skipped</Chip>
  if (status === 'SUBMITTED') return <Chip variant="info">Awaiting verification</Chip>
  if (isOverdue) return <Chip variant="danger">Overdue</Chip>
  return <Chip variant="warning">Pending</Chip>
}

function UploadsCard({
  prereqTaskId,
  submissions,
  onUploaded,
}: {
  prereqTaskId: number
  submissions: { attachmentId: number; fileName: string; downloadUrl?: string | null }[]
  onUploaded: () => void
}) {
  const [uploading, setUploading] = React.useState(false)
  const inputRef = React.useRef<HTMLInputElement>(null)

  const onFileChosen = async (file: File | undefined) => {
    if (!file) return
    setUploading(true)
    try {
      await uploadPortalPrereqAttachment(prereqTaskId, { file })
      onUploaded()
    } catch (error) {
      toast({
        title: 'Could not upload that file',
        description: error instanceof ApiError ? error.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setUploading(false)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <h2 className="text-h3 text-content">Your uploads</h2>

      {submissions.length === 0 ? (
        <p className="mt-2 text-sm text-content-muted">Nothing uploaded yet.</p>
      ) : (
        <ul className="mt-2 flex flex-col gap-1">
          {submissions.map((file) => (
            <li key={file.attachmentId} className="text-sm">
              {file.downloadUrl ? (
                <a href={file.downloadUrl} className="text-primary hover:underline" target="_blank" rel="noreferrer">
                  {file.fileName}
                </a>
              ) : (
                <span className="text-content-muted">{file.fileName} (scanning…)</span>
              )}
            </li>
          ))}
        </ul>
      )}

      <div className="mt-4">
        <input
          ref={inputRef}
          type="file"
          id="prereq-upload"
          className="sr-only"
          onChange={(e) => onFileChosen(e.target.files?.[0])}
          disabled={uploading}
        />
        <label htmlFor="prereq-upload">
          <Button asChild variant="secondary" disabled={uploading}>
            <span>{uploading ? 'Uploading…' : 'Add a file'}</span>
          </Button>
        </label>
      </div>
    </section>
  )
}

function CommentsCard({ prereqTaskId }: { prereqTaskId: number }) {
  const queryClient = useQueryClient()
  const { data, isPending } = useListPortalPrereqComments(prereqTaskId, undefined, {
    query: { enabled: Number.isFinite(prereqTaskId) },
  })

  const { register, handleSubmit, reset, formState: { isSubmitting } } = useForm<{ body: string }>({
    defaultValues: { body: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    if (!values.body.trim()) return
    try {
      await addPortalPrereqComment(prereqTaskId, { body: values.body })
      reset()
      await queryClient.invalidateQueries({
        queryKey: getListPortalPrereqCommentsQueryKey(prereqTaskId),
      })
    } catch (error) {
      toast({
        title: 'Could not post your comment',
        description: error instanceof ApiError ? error.problem.detail : undefined,
        variant: 'danger',
      })
    }
  })

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <h2 className="text-h3 text-content">Comments</h2>

      {isPending ? (
        <Skeleton className="mt-3 h-20 w-full" />
      ) : (
        <ul className="mt-3 flex flex-col gap-3">
          {(data?.data ?? []).map((comment) => (
            <li key={comment.id} className={comment.authorType === 'CLIENT' ? 'text-right' : ''}>
              <div
                className={
                  'inline-block max-w-[80%] rounded-card px-3 py-2 text-sm ' +
                  (comment.authorType === 'CLIENT' ? 'bg-primary-soft text-content' : 'bg-subtle text-content')
                }
              >
                <p className="text-caption font-medium text-content-muted">
                  {comment.staffAuthor?.displayName ?? comment.clientAuthor?.name ?? 'System'}
                </p>
                <p className="whitespace-pre-wrap">{comment.body}</p>
              </div>
            </li>
          ))}
          {(data?.data ?? []).length === 0 ? (
            <p className="text-sm text-content-muted">No comments yet.</p>
          ) : null}
        </ul>
      )}

      <form onSubmit={onSubmit} className="mt-4 flex flex-col gap-2">
        <textarea
          {...register('body', { required: true, maxLength: 4000 })}
          rows={3}
          placeholder="Ask a question or add a note…"
          className="w-full rounded-control border border-border bg-surface p-2.5 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        />
        <div>
          <Button type="submit" size="sm" disabled={isSubmitting}>
            {isSubmitting ? 'Posting…' : 'Post comment'}
          </Button>
        </div>
      </form>
    </section>
  )
}
