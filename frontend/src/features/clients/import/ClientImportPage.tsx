import { useState } from 'react'
import { AlertCircle, ArrowLeft, Check, Download, FileSpreadsheet, Loader2, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useCommitImport, useValidateImport } from '@/api/generated/imports/imports'
import type { ImportPreviewResponseData } from '@/api/generated/model'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { CommitStep } from './CommitStep'
import { type ColumnMapping } from './columnMapping'
import {
  IMPORT_PROBLEM,
  commitRefusal,
  formatBytes,
  previewRefusal,
  rejectionReason,
  useDownloadImportTemplate,
  useUploadImportFile,
  type StagedUploadSummary,
} from './importQueries'
import { MappingStep } from './MappingStep'
import { UploadDropzone } from './UploadDropzone'
import { ValidationStep } from './ValidationStep'

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
 * S-34 Client Import Wizard — all five steps. B-031, B-032, B-033, B-034, B-035.
 *
 * Blueprint §4B.3, and the engine behind it is B-030's schema registry: this
 * screen names clients exactly once, in the route it is mounted at and in the
 * schema it asks for. B-038 registers resources and this page is what it reuses.
 *
 * ## Step 5 is where the wizard stops being reversible
 *
 * Every step before it can be gone back to, because none of them wrote anything.
 * Once the commit is accepted there is a batch running against the client master
 * and a Back button would be a lie — so the earlier sections come off the screen
 * and what is left is the progress and two ways forward. `startOver` is the only
 * route back, and it starts a genuinely new import rather than resuming this one.
 *
 * ## The uploaded File is held, not just the response
 *
 * That is what makes the sheet selector work. Choosing another sheet re-posts
 * the same file naming it, along with the `uploadId` it supersedes so the server
 * can release that staging slot. The alternative — asking the server to re-read a
 * copy it kept — means holding the bytes of every open upload for the staging
 * TTL, and the browser is already holding this one.
 *
 * ## The mapping lives here, and goes to the server only at step 4
 *
 * B-033 adds no route for it, because the contract never needed one: step 4's
 * `/validate` and step 5's `/commit` both take `mapping` in their own body, so a
 * parked copy on the server would be a fifth piece of wizard state to keep in
 * step with the other four — and the user can go back and change it. What step 3
 * does read from the server is *our* column list (`/fields`) and the saved
 * presets, neither of which the browser can know.
 *
 * ## The preview is cleared whenever anything it described changes
 *
 * A preview is a statement about one file, one sheet and one mapping. Leaving a
 * stale one on screen after the user goes back and changes a column is the worst
 * failure this screen has available: the numbers are specific, they look
 * authoritative, and they describe a run that will not happen. So going back to
 * mapping drops it, and so does every path that touches the upload.
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
   * Whether the user has moved on to mapping.
   *
   * Held separately from `staged` rather than derived from it, so Back to upload
   * returns to step 2 with the file still staged instead of unwinding the upload.
   */
  const [mapping, setMapping] = useState<ColumnMapping | null>(null)
  /**
   * The dry run's answer, once it has one.
   *
   * Held rather than read from `validate.data` so that going back to mapping can
   * drop it in one place — see the note above on why a stale preview is the worst
   * thing this screen could leave on display.
   */
  const [preview, setPreview] = useState<ImportPreviewResponseData | null>(null)
  /**
   * The running import, once one has been accepted.
   *
   * The point of no return for this screen: while it is set, every earlier step
   * is gone from the page. It holds the id rather than the whole batch because
   * everything else about the run is polled — a copy kept here would be a second
   * answer to "how far has it got", and the stale one would be on screen.
   */
  const [batchId, setBatchId] = useState<number | null>(null)

  const validate = useValidateImport()
  const commit = useCommitImport()

  const currentStep = batchId ? 4 : preview ? 3 : mapping ? 2 : staged ? 1 : 0

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
    setMapping(null)
    discardPreview()
    setFile(reason ? null : chosen)
    if (!reason) {
      send(chosen)
    }
  }

  function chooseSheet(sheet: string) {
    if (file && sheet !== staged?.sheet) {
      // The preview described the *other* sheet. Nothing about it survives.
      discardPreview()
      send(file, sheet, staged?.uploadId)
    }
  }

  function startOver() {
    setFile(null)
    setStaged(null)
    setRejected(null)
    setMapping(null)
    setBatchId(null)
    discardPreview()
    upload.reset()
    commit.reset()
  }

  /** Both halves — the answer and the refusal are two states of one request. */
  function discardPreview() {
    setPreview(null)
    validate.reset()
  }

  /**
   * Step 4 — run the dry run.
   *
   * `sheet` travels with it as a cross-check, not a selector: the server refuses
   * a mismatch rather than previewing the other sheet under this one's heading.
   * Sending it costs nothing and closes the one way the wizard's state and the
   * server's can silently diverge.
   */
  function runDryRun() {
    if (!staged?.uploadId || !mapping) return
    validate.mutate(
      {
        schema: 'clients',
        data: { uploadId: staged.uploadId, sheet: staged.sheet, mapping },
      },
      { onSuccess: (response) => setPreview(response.data) },
    )
  }

  /**
   * Back from the preview to the mapping.
   *
   * Dropping the preview is the whole point: the user is going back to change a
   * column, so the numbers on screen are about to stop being true.
   */
  function backToMapping() {
    discardPreview()
    // A refusal about the *commit* is about a mapping the user is on their way
    // to change. Leaving it up would have them reading a complaint about the
    // thing they have just gone to fix.
    commit.reset()
  }

  /**
   * Step 5 — start the import.
   *
   * The body is the upload and the mapping, not the preview: the server re-runs
   * the dry run and writes what *it* judges writable. That is deliberate on both
   * sides — see `ImportCommitService` — and it is why this function has nothing
   * to send but the same two things step 4 sent.
   *
   * `skipRejected` is left off, taking the contract's default of `true`. §4B.3's
   * offer is "import valid rows only" and that is what this screen does; sending
   * `false` would be asking the server to refuse the whole file, which is not
   * what the button says.
   */
  function runCommit() {
    if (!staged?.uploadId || !mapping) return
    commit.mutate(
      {
        schema: 'clients',
        data: { uploadId: staged.uploadId, sheet: staged.sheet, mapping },
      },
      { onSuccess: (response) => setBatchId(response.data.batchId) },
    )
  }

  /**
   * Step 3 opens on the server's auto-match, which is the point of it — a file
   * built from the template arrives fully mapped and the user only confirms.
   *
   * Copied into state rather than read from `staged` on every render: from here
   * on it is the user's mapping, and nothing downstream can tell an override from
   * an auto-match. `ImportMapping`'s javadoc makes that a deliberate property —
   * a manual override taking a different path would be a second, less-tested
   * importer reached only by the users whose files were unusual.
   */
  function startMapping() {
    setMapping({ ...(staged?.suggestedMapping ?? {}) })
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

      {/*
        Once a commit is running, steps 1 to 4 come off the screen entirely
        rather than being disabled. They describe choices that have already been
        made and acted on — a greyed-out Upload control next to a running import
        invites the reading that the file could still be changed.
      */}
      {!batchId && (
        <>
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
              <Button onClick={startMapping} disabled={mapping !== null}>
                Continue to mapping
              </Button>
              <p className="text-sm text-content-muted">
                Your file has been read and nothing has been written — no client has
                changed.
              </p>
            </div>
          </div>
        )}
      </section>

      {/* ── step 3 ─────────────────────────────────────────────────────── */}
      {staged && mapping && !preview && (
        <section
          aria-labelledby="step-3-heading"
          className="rounded-card border border-border bg-surface p-5"
        >
          <h2 id="step-3-heading" className="text-h3 text-content">
            Map the columns
          </h2>
          <MappingStep
            schema="clients"
            headers={staged.headers ?? []}
            rowCount={staged.rowCount ?? 0}
            mapping={mapping}
            onChange={(next) => {
              // Any change invalidates a refusal the server made about the
              // previous mapping — leaving it up would have the user reading a
              // complaint about the column they have just fixed.
              validate.reset()
              setMapping(next)
            }}
            onBack={() => {
              setMapping(null)
              discardPreview()
            }}
            onContinue={runDryRun}
            continuePending={validate.isPending}
          />

          {/*
            The dry run's refusal, rendered here rather than at step 4 — there is
            no step 4 to render it in, because the request that would have built
            one failed. Each of them is fixed on this screen or the one before
            it, which is why the remedy decides the sentence.
          */}
          {validate.isError && <PreviewRefusalNotice error={validate.error} />}
        </section>
      )}

      {/* ── step 4 ─────────────────────────────────────────────────────── */}
      {staged && mapping && preview && (
        <section
          aria-labelledby="step-4-heading"
          className="rounded-card border border-border bg-surface p-5"
        >
          <h2 id="step-4-heading" className="text-h3 text-content">
            Check what the import will do
          </h2>
          <ValidationStep
            schema="clients"
            preview={preview}
            fileName={file?.name ?? staged.fileName ?? 'your file'}
            onBack={backToMapping}
            onCommit={runCommit}
            committing={commit.isPending}
          />

          {/*
            A commit refusal, rendered under the preview that produced it. It
            belongs here rather than at step 5, because there is no step 5 — the
            request that would have started one failed, and nothing has been
            written.
          */}
          {commit.isError && <CommitRefusalNotice error={commit.error} />}
        </section>
      )}
        </>
      )}

      {/* ── step 5 ─────────────────────────────────────────────────────── */}
      {batchId !== null && (
        <section
          aria-labelledby="step-5-heading"
          className="rounded-card border border-border bg-surface p-5"
        >
          <h2 id="step-5-heading" className="text-h3 text-content">
            Importing
          </h2>
          <div className="mt-4">
            <CommitStep
              batchId={batchId}
              fileName={file?.name ?? staged?.fileName ?? 'your file'}
              onStartAnother={startOver}
            />
          </div>
        </section>
      )}
    </div>
  )
}

/**
 * A step-4 refusal, with the step that fixes it named.
 *
 * The remedy is the part worth rendering. "Something went wrong, try again"
 * leaves a user pressing the same button on an upload that expired half an hour
 * ago; "choose your file again at step 2" does not.
 */
function PreviewRefusalNotice({ error }: { error: unknown }) {
  const { message, remedy } = previewRefusal(error)
  const next =
    remedy === 'upload'
      ? 'Choose your file again at step 2.'
      : remedy === 'mapping'
        ? 'Correct the mapping above and run the preview again.'
        : null

  return (
    <p
      role="alert"
      className="mt-4 flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
    >
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <span>
        {message}
        {next ? ` ${next}` : ''} Nothing has been written — no client has changed.
      </span>
    </p>
  )
}

/**
 * A step-5 refusal — the Import button was pressed and nothing started.
 *
 * Separate from `PreviewRefusalNotice` for one word: this one can say
 * "**nothing has been written**" as a completed fact about a request that was
 * refused, which is the reassurance a user needs most at the moment they pressed
 * the only irreversible button on the screen.
 *
 * `revalidate` is the remedy the preview step does not have. It means the server
 * re-judged the file and disagreed with what is on screen — the honest
 * instruction is to run the preview again, not to press Import harder.
 */
function CommitRefusalNotice({ error }: { error: unknown }) {
  const { message, remedy } = commitRefusal(error)
  const next =
    remedy === 'upload'
      ? 'Choose your file again at step 2.'
      : remedy === 'mapping'
        ? 'Correct the mapping at step 3 and run the preview again.'
        : remedy === 'revalidate'
          ? 'Go back to mapping and run the preview again to see the current state of the file.'
          : 'Try again in a moment.'

  return (
    <p
      role="alert"
      className="mt-4 flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
    >
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <span>
        {message} {next} <strong className="font-medium">Nothing has been written</strong> —
        no client has changed.
      </span>
    </p>
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
