import { AlertCircle, ArrowLeft, Check, Download, Loader2 } from 'lucide-react'
import { Link } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { useDownloadImportTemplate } from './importQueries'

/**
 * The five steps of blueprint §4B.3, named on screen from the start.
 *
 * The rail shows all five while only the first works, and that is the honest
 * shape rather than a placeholder: a user about to hand over a spreadsheet of
 * four hundred clients is entitled to know, before they start, that nothing is
 * written until they have seen a per-row preview. That promise is the reason the
 * wizard has five steps instead of one upload button, and it is worth making at
 * step 1 rather than at step 4.
 *
 * `done` is what each step is called elsewhere in the product, so the words on
 * the rail match the words in the backlog and the contract.
 */
const STEPS = [
  { title: 'Download template', detail: 'Pre-formatted .xlsx with the exact columns' },
  { title: 'Upload', detail: '.xlsx or .csv, up to 5 MB and 5,000 rows' },
  { title: 'Map columns', detail: 'Auto-matched by heading, overridable per column' },
  { title: 'Validate', detail: 'A dry run — every row’s outcome, nothing written' },
  { title: 'Commit', detail: 'Imports in the background, with an error report' },
] as const

/**
 * S-34 Client Import Wizard — step 1. B-031.
 *
 * Blueprint §4B.3, and the engine behind it is B-030's schema registry: this
 * screen names clients exactly once, in the route it is mounted at and in the
 * schema it asks for. B-038 registers resources and this page is what it reuses.
 *
 * ## Steps 2 to 5 are visible and disabled, not hidden
 *
 * Hiding them would make the screen look finished and leave the user to discover
 * at the end of step 1 that there is no step 2. Showing them greyed says what is
 * coming, in the order it comes, and the Continue button says why it is disabled
 * rather than being mysteriously inert — the same call the S-32 grid made when
 * this route did not exist yet and its Import button said so on the tooltip.
 *
 * ## The download is a mutation, not a query
 *
 * A download is an event. The generated `useDownloadImportTemplate` is a
 * `useQuery`, which would fetch a workbook on mount and again on every window
 * focus; `importQueries.ts` explains the rest of why it is not used.
 */
export function ClientImportPage() {
  const download = useDownloadImportTemplate()

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
          const current = index === 0
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

          {/*
            Disabled and labelled, rather than absent. The user has to know that
            the file they are about to fill in has nowhere to go yet — finding
            that out after a morning of data entry is the outcome worth spending
            a sentence to avoid.
          */}
          <Button variant="secondary" disabled title="Upload arrives with the next step of this screen">
            Continue to upload
          </Button>
          <p className="text-sm text-content-muted">
            Uploading is not available yet. Fill the template in and come back —
            nothing you do here changes any client.
          </p>
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
    </div>
  )
}
