import * as React from 'react'
import { AlertTriangle, Download, FileSpreadsheet } from 'lucide-react'

import { ApiError } from '@/api/http'
import type { ObJourneyTaskImportPreview } from '@/api/generated/model/obJourneyTaskImportPreview'
import type { ObJourneyTaskImportInvalidProblem } from '@/api/generated/model/obJourneyTaskImportInvalidProblem'
import {
  useCommitObJourneyTaskImport,
  usePreviewObJourneyTaskImport,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'

import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'

import { useDownloadTaskImportTemplate, useInvalidateAfterTaskImport } from './taskImportQueries'

interface TaskImportDialogProps {
  templateId: number
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * OB-07 · "Import from spreadsheet" on the designer — download the Tasks /
 * Task List / Document Checklist template, upload it back, preview the tree
 * it describes, then confirm.
 *
 * <h2>Two round trips, not one</h2>
 *
 * <p>The confirm step re-sends the same {@link File} to `POST task-import`
 * rather than relying on the {@link ObJourneyTaskImportPreview} already on
 * screen: the server writes nothing on preview, so a stale preview and a
 * server-side change (someone editing this template's stages in another tab)
 * are only caught by validating again at commit time. `422` there carries the
 * identical row errors, and this dialog treats it exactly like a failed
 * preview rather than a generic error toast.
 *
 * <h2>Why not the generic Import Wizard</h2>
 *
 * <p>`feature/imports`' wizard (mapping step, batch history, Reverse) is
 * built for a flat entity upserted on one natural key. This workbook has no
 * such key and replaces a draft's whole task tree instead — see
 * `ObJourneyTaskImportService`'s own javadoc on the backend. So this is a
 * small purpose-built flow: download, upload, preview, confirm — not a fifth
 * schema registration.
 */
export function TaskImportDialog({ templateId, open, onOpenChange }: TaskImportDialogProps) {
  const [file, setFile] = React.useState<File | null>(null)
  const [preview, setPreview] = React.useState<ObJourneyTaskImportPreview | null>(null)

  const download = useDownloadTaskImportTemplate()
  const previewImport = usePreviewObJourneyTaskImport()
  const commitImport = useCommitObJourneyTaskImport()
  const invalidate = useInvalidateAfterTaskImport()

  React.useEffect(() => {
    if (!open) {
      setFile(null)
      setPreview(null)
      previewImport.reset()
      commitImport.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function pickFile(next: File | null) {
    setFile(next)
    setPreview(null)
    previewImport.reset()
    commitImport.reset()
  }

  async function runPreview() {
    if (!file) return
    try {
      const response = await previewImport.mutateAsync({ templateId, data: { file } })
      setPreview(response.data)
    } catch (error) {
      toast({ title: 'Could not read that file', description: problemDetail(error), variant: 'danger' })
    }
  }

  async function confirmCommit() {
    if (!file) return
    try {
      const response = await commitImport.mutateAsync({ templateId, data: { file } })
      await invalidate(templateId)
      toast({
        title: 'Task tree replaced',
        description: `${response.data.taskCount} task${response.data.taskCount === 1 ? '' : 's'}, `
          + `${response.data.itemCount} Task List item${response.data.itemCount === 1 ? '' : 's'}, `
          + `${response.data.docCount} document${response.data.docCount === 1 ? '' : 's'}.`,
      })
      onOpenChange(false)
    } catch (error) {
      const invalid = invalidProblem(error)
      if (invalid) {
        // The re-uploaded file stopped validating between preview and confirm
        // (someone edited the stages, or the file changed on disk) — read
        // exactly like a failed preview rather than a bare toast.
        setPreview({ valid: false, errors: invalid.errors, tasks: [] })
        return
      }
      toast({ title: 'Import failed', description: problemDetail(error), variant: 'danger' })
    }
  }

  const busy = previewImport.isPending || commitImport.isPending

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>Import tasks from a spreadsheet</ModalTitle>
          <ModalDescription>
            Replaces this draft&rsquo;s entire Tasks, Task List and Document Checklist with what
            the file describes. Nothing is written until you confirm the preview below.
          </ModalDescription>
        </ModalHeader>

        <div className="space-y-4 px-6 py-2">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => download.mutate(templateId)}
            disabled={download.isPending}
          >
            <Download className="mr-2 h-4 w-4" aria-hidden="true" />
            {download.isPending ? 'Preparing template…' : 'Download template'}
          </Button>

          <label className="block text-sm font-medium text-content">
            Filled-in spreadsheet
            <div className="mt-1 flex items-center gap-3">
              <FileSpreadsheet className="h-5 w-5 text-content-muted" aria-hidden="true" />
              <input
                type="file"
                accept=".xlsx"
                disabled={busy}
                onChange={(event) => pickFile(event.target.files?.[0] ?? null)}
                className="text-sm text-content-muted file:mr-3 file:rounded-control file:border-0 file:bg-subtle file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-content"
              />
            </div>
          </label>

          {file && !preview && (
            <Button type="button" size="sm" onClick={runPreview} disabled={busy}>
              {previewImport.isPending ? 'Checking file…' : 'Preview'}
            </Button>
          )}

          {preview && !preview.valid && (
            <div className="rounded-card border border-danger bg-surface p-3 text-sm text-danger-text">
              <div className="flex items-center gap-2 font-medium">
                <AlertTriangle className="h-4 w-4" aria-hidden="true" />
                {preview.errors.length === 1
                  ? '1 row needs fixing'
                  : `${preview.errors.length} rows need fixing`}
              </div>
              <ul className="mt-2 max-h-48 space-y-1 overflow-y-auto">
                {preview.errors.map((rowError, index) => (
                  <li key={index}>
                    {rowError.rowNumber > 0 ? `${rowError.sheet} — row ${rowError.rowNumber}: ` : ''}
                    {rowError.message}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {preview && preview.valid && (
            <div className="rounded-card border border-border bg-subtle p-3 text-sm">
              <p className="font-medium text-content">
                {preview.tasks.length} task{preview.tasks.length === 1 ? '' : 's'} ready to import
              </p>
              <ul className="mt-2 max-h-56 space-y-1 overflow-y-auto text-content-muted">
                {preview.tasks.map((task) => (
                  <li key={task.name}>
                    {task.name}
                    {task.stageGroupName ? ` (${task.stageGroupName})` : ' (Ungrouped)'}
                    {' — '}
                    {task.items.length} item{task.items.length === 1 ? '' : 's'},{' '}
                    {task.docs.length} doc{task.docs.length === 1 ? '' : 's'}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <ModalFooter>
          <Button type="button" variant="secondary" size="sm" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={confirmCommit}
            disabled={!preview?.valid || busy}
          >
            {commitImport.isPending ? 'Importing…' : 'Confirm import'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

function invalidProblem(error: unknown): ObJourneyTaskImportInvalidProblem | null {
  if (!(error instanceof ApiError)) return null
  const problem = error.problem as Partial<ObJourneyTaskImportInvalidProblem>
  return Array.isArray(problem.errors) ? (problem as ObJourneyTaskImportInvalidProblem) : null
}

function problemDetail(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Reload the page and try again.'
  return error.problem.detail ?? error.problem.title ?? 'Reload the page and try again.'
}
