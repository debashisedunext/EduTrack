import { useState } from 'react'
import { AlertCircle, ArrowLeft, Check, Download, FileSpreadsheet, Loader2, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import {
  IMPORT_PROBLEM,
  formatBytes,
  rejectionReason,
  useDownloadImportTemplate,
  useUploadImportFile,
  type StagedUploadSummary,
} from './importQueries'
import { UploadDropzone } from './UploadDropzone'

/**
 * The five steps of blueprint §4B.3, named on screen from the start.
 *
 * The rail shows all five while the first two work, and that is the honest shape
 * rather than a placeholder: a user about to hand over a spreadsheet of four
 * hundred clients is entitled to know, before they start, that nothing is written
 * until they have seen a per-row preview. That promise is the reason the wizard
 * has five steps instead of one upload button, and it is worth making at step 1
 * rather than at step 4.
 */
const STEPS = [
  { title: 'Download template', detail: 'Pre-formatted .xlsx with the exact columns' },
  { title: 'Upload', detail: '.xlsx or .csv, up to 5 MB and 5,000 rows' },
  { title: 'Map columns', detail: 'Auto-matched by heading, overridable per column' },
  { title: 'Validate', detail: 'A dry run — every row’s outcome, nothing written' },
  { title: 'Commit', detail: 'Imports in the background, with an error report' },
] as const

/**
 * S-34 Client Import Wizard — steps 1 and 2. B-031, B-032.
 *
 * Blueprint §4B.3, and the engine behind it is B-030's schema registry: this
 * screen names clients exactly once, in the route it is mounted at and in the
 * schema it asks for. B-038 registers resources and this page is what it reuses.
 *
 * ## Steps 3 to 5 are visible and disabled, not hidden
 *
 * Hiding them would make the screen look finished and leave the user to discover
 * at the end of step 2 that there is no step 3. Showing them greyed says what is
 * coming, in the order it comes, and the Continue button says why it is disabled
 * rather than being mysteriously inert.
 *
 * ## The uploaded File is held, not just the response
 *
 * That is what makes the sheet selector work. Choosing another sheet re-posts
 * the same file naming it, along with the `uploadId` it supersedes so the server
 * can release that staging slot. The alternative — asking the server to re-read a
 * copy it kept — means holding the bytes of every open upload for the staging
 * TTL, and the browser is already holding this one.
 */
export function ClientImportPage() {
  const download = useDownloadImportTemplate()
  const upload = useUploadImportFile('clients')

  /** The chosen file, kept so a sheet change can re-post it. */
  const [file, setFile] = useState<File | null>(null)
  const [staged, setStaged] = useState<StagedUploadSummary | null>(null)
  /** A refusal this screen made itself, before anything was sent. */
  const [rejected, setRejected] = useState<string | null>(null)

  /**
   * Step 2 becomes current once a file is staged, and does not move on to step 3
   * — because step 3 does not exist yet. Advancing the rail past what works
   * would put the marker on a step the user cannot reach.
   */
  const currentStep = staged ? 1 : 0

  function send(chosen: File, sheet?: string, replaces?: string) {
    upload.mutate(
      { file: chosen, sheet, replaces },
      { onSuccess: (response) => setStaged(response.data ?? null) },
    )
  }

  function chooseFile(chosen: File) {
    const reason = rejectionReason(chosen)
    setRejected(reason)
    setStaged(null)
    setFile(reason ? null : chosen)
    if (!reason) {
      send(chosen)
    }
  }

  function chooseSheet(sheet: string) {
    if (file && sheet !== staged?.sheet) {
      send(file, sheet, staged?.uploadId)
    }
  }

  function startOver() {
    setFile(null)
    setStaged(null)
    setRejected(null)
    upload.reset()
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-content">Import clients from Excel</h1>
        <div className="ml-auto">
          <Button asChild variant="secondary" size="sm">
            <Link to="/masters/clients">
              <ArrowLeft className="h-4 w-4" />
              Back to clients
            </Link>
          </Button>
        </div>
      </div>

      {/* ── the five steps ─────────────────────────────────────────────── */}
      <ol className="grid gap-2 sm:grid-cols-5" aria-label="Import steps">
        {STEPS.map((step, index) => {
          const current = index === currentStep
          const done = index < currentStep
          return (
            <li
              key={step.title}
              aria-current={current ? 'step' : undefined}
              className={cn(
                'rounded-card border p-3',
                current
                  ? 'border-primary bg-primary-soft'
                  : 'border-border bg-surface text-content-muted',
              )}
            >
              <p className="text-xs font-medium uppercase tracking-wide text-content-muted">
                Step {index + 1}
                {done && <span className="sr-only"> (done)</span>}
              </p>
              <p className={cn('text-sm font-medium', current ? 'text-content' : undefined)}>
                {step.title}
              </p>
              <p className="mt-1 text-xs text-content-muted">{step.detail}</p>
            </li>
          )
        })}
      </ol>

      {/* ── step 1 ─────────────────────────────────────────────────────── */}
      <section
        aria-labelledby="step-1-heading"
        className="rounded-card border border-border bg-surface p-5"
      >
        <h2 id="step-1-heading" className="text-h3 text-content">
          Download the template
        </h2>
        <p className="mt-1 max-w-2xl text-sm text-content-muted">
          Start from the template rather than from a file of your own. Its column
          headings are what the import matches on, so a file built from it maps
          itself at step 3 instead of column by column.
        </p>

        <ul className="mt-4 space-y-2 text-sm text-content">
          {[
            'Every column the client master accepts, in order.',
            'Dropdowns on Status and Support Plan — the same values the import accepts, so a chosen value is never rejected later.',
            'One filled example row. Replace it with your own data or delete it; it is imported like any other row.',
            'An Instructions sheet naming the required columns and what Client Code does: a code that already exists updates that client, it never creates a second one.',
          ].map((line) => (
            <li key={line} className="flex gap-2">
              <Check className="mt-0.5 h-4 w-4 shrink-0 text-success" aria-hidden="true" />
              <span>{line}</span>
            </li>
          ))}
        </ul>

        <div className="mt-5 flex flex-wrap items-center gap-3">
          <Button
            onClick={() => download.mutate('clients')}
            disabled={download.isPending}
            aria-describedby={download.isError ? 'template-error' : undefined}
          >
            {download.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            ) : (
              <Download className="h-4 w-4" aria-hidden="true" />
            )}
            {download.isPending ? 'Preparing…' : 'Download template'}
          </Button>
        </div>

        {download.isError && (
          <p
            id="template-error"
            role="alert"
            className="mt-4 flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
          >
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
            <span>
              The template could not be downloaded ({download.error.status}
              {download.error.problem.title ? ` — ${download.error.problem.title}` : ''}). Try
              again; if it keeps failing, an administrator can check the import schema is
              registered.
            </span>
          </p>
        )}
      </section>

      {/* ── step 2 ─────────────────────────────────────────────────────── */}
      <section
        aria-labelledby="step-2-heading"
        className="rounded-card border border-border bg-surface p-5"
      >
        <h2 id="step-2-heading" className="text-h3 text-content">
          Upload your file
        </h2>
        <p className="mt-1 max-w-2xl text-sm text-content-muted">
          Nothing is written by uploading. The file is read so the next steps can
          show you what is in it — no client changes until you have seen the
          per-row preview at step 4 and confirmed it.
        </p>

        {!staged && (
          <div className="mt-4">
            <UploadDropzone
              onFile={chooseFile}
              disabled={upload.isPending}
              describedBy={rejected || upload.isError ? 'upload-error' : undefined}
            />
          </div>
        )}

        {upload.isPending && (
          <p className="mt-4 flex items-center gap-2 text-sm text-content-muted" role="status">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            Reading the file…
          </p>
        )}

        {/*
          Two error sources, one place to look. A refusal this screen made
          (wrong type, too big) and one the server made read the same way to the
          user, because to them they are the same event — the file did not go.
        */}
        {(rejected || upload.isError) && (
          <p
            id="upload-error"
            role="alert"
            className="mt-4 flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
          >
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
            <span>{rejected ?? serverRefusal(upload.error)}</span>
          </p>
        )}

        {staged && (
          <div className="mt-4 space-y-4">
            <div className="flex flex-wrap items-center gap-3 rounded-control border border-border bg-subtle p-3">
              <FileSpreadsheet className="h-5 w-5 shrink-0 text-content-muted" aria-hidden="true" />
              <div className="min-w-0">
                {/*
                  The browser's own name for the file the user just picked, with
                  the server's echo as the fallback. Local first because it is
                  the name they chose in their own file dialog a second ago —
                  the round trip cannot improve on that, and `fileName` in the
                  response is there for the later steps, which carry an uploadId
                  and no File.
                */}
                <p className="truncate text-sm font-medium text-content">
                  {file?.name ?? staged.fileName}
                </p>
                <p className="text-sm text-content-muted">
                  {/*
                    Rows and columns, because they are the two numbers that tell
                    a user whether the right sheet was read. A file size would
                    not: 400 KB is consistent with the wrong tab entirely.
                  */}
                  {staged.rowCount?.toLocaleString()} rows · {staged.headers?.length} columns
                  {file ? ` · ${formatBytes(file.size)}` : ''}
                </p>
              </div>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                className="ml-auto"
                onClick={startOver}
              >
                <X className="h-4 w-4" aria-hidden="true" />
                Choose a different file
              </Button>
            </div>

            {/*
              §4B.3: "first sheet by default, sheet selector if the workbook has
              several". Rendered only when there are several — a selector with
              one option is a control that cannot be used, and a CSV always has
              exactly one.
            */}
            {(staged.sheets?.length ?? 0) > 1 && (
              <fieldset disabled={upload.isPending}>
                <legend className="text-sm font-medium text-content">
                  Which sheet holds the clients?
                </legend>
                <p className="mt-1 text-sm text-content-muted">
                  This workbook has {staged.sheets?.length} sheets. The first is read by
                  default; choosing another re-reads the file.
                </p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {staged.sheets?.map((sheet) => (
                    <Button
                      key={sheet}
                      type="button"
                      size="sm"
                      variant={sheet === staged.sheet ? 'primary' : 'secondary'}
                      aria-pressed={sheet === staged.sheet}
                      onClick={() => chooseSheet(sheet)}
                    >
                      {sheet}
                    </Button>
                  ))}
                </div>
              </fieldset>
            )}

            <div>
              <p className="text-sm font-medium text-content">Columns found</p>
              <ul className="mt-2 flex flex-wrap gap-2">
                {staged.headers?.map((header) => {
                  // Auto-matched columns are marked here rather than at step 3
                  // alone, so a file whose headings were renamed shows the
                  // problem now — before the user has read four hundred rows of
                  // preview looking for what went wrong.
                  const matched = Object.values(staged.suggestedMapping ?? {}).includes(header)
                  return (
                    <li
                      key={header}
                      className={cn(
                        'rounded-chip border px-2 py-1 text-xs',
                        matched
                          ? 'border-success bg-surface text-success-text'
                          : 'border-border bg-surface text-content-muted',
                      )}
                    >
                      {header}
                      <span className="sr-only">
                        {matched ? ' — matched to a client field' : ' — not matched yet'}
                      </span>
                    </li>
                  )
                })}
              </ul>
              <p className="mt-2 text-sm text-content-muted">
                {Object.keys(staged.suggestedMapping ?? {}).length} of {staged.headers?.length}{' '}
                matched a client field automatically. The rest are mapped by hand at step 3.
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              {/*
                Disabled and labelled, rather than absent. Somebody who has just
                uploaded four hundred rows has to know the mapping step is not
                there yet — and that nothing they have done has changed a client.
              */}
              <Button
                variant="secondary"
                disabled
                title="Column mapping arrives with the next step of this screen"
              >
                Continue to mapping
              </Button>
              <p className="text-sm text-content-muted">
                Mapping is not available yet. Your file has been read and nothing has been
                written — no client has changed.
              </p>
            </div>
          </div>
        )}
      </section>
    </div>
  )
}

/**
 * The server's refusal, in words the user can act on.
 *
 * Branches on `problem.type`, never on `title` or `detail` — CONVENTIONS.md §3
 * makes the type the stable half and the prose the changeable one. `detail` is
 * still what is *shown* where the server wrote something specific ("the sheet has
 * more than 5,000 rows"), because it names the actual number; the type decides
 * whether to trust it and what to add.
 */
function serverRefusal(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'The file could not be uploaded. Check your connection and try again.'
  }
  const detail = error.problem.detail
  if (
    error.is(IMPORT_PROBLEM.tooLarge) ||
    error.is(IMPORT_PROBLEM.unsupported) ||
    error.is(IMPORT_PROBLEM.unreadable)
  ) {
    return detail ?? error.problem.title
  }
  if (error.status === 403) {
    return 'You do not have permission to import clients. Importing is an administrator action.'
  }
  return `The file could not be uploaded (${error.status}${detail ? ` — ${detail}` : ''}).`
}
