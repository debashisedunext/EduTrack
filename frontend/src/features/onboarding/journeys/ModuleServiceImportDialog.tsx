import * as React from 'react'
import { AlertTriangle, CheckSquare, Download, FileSpreadsheet, Layers, ListTree } from 'lucide-react'

import { ApiError } from '@/api/http'
import type { ModuleImportPreviewData } from '@/api/generated/model/moduleImportPreviewData'
import type { ModuleImportServiceDto } from '@/api/generated/model/moduleImportServiceDto'
import type { ModuleImportInvalidProblem } from '@/api/generated/model/moduleImportInvalidProblem'
import {
  useCommitObModuleServiceImport,
  usePreviewObModuleServiceImport,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useProducts } from '@/features/onboarding/products/productQueries'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'

import {
  useDownloadModuleServiceImportTemplate,
  useInvalidateAfterModuleServiceImport,
} from './moduleServiceImportQueries'

interface ModuleServiceImportDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * OB-07 · "Import" on the Module Service catalogue — choose a product,
 * download the four-column template, upload it back, preview the tree it
 * describes, then confirm.
 *
 * <h2>The product is chosen here, not in the file</h2>
 *
 * <p>`ob_journey_templates.product_id` is NOT NULL and a Module Service name
 * on its own does not imply one, so something has to say which product
 * "Admission Management" belongs to. A fifth column would put that answer on
 * every row of the file, where it is the same answer every time and one typo
 * away from filing a service under the wrong product. So the picker gates the
 * dialog instead: **nothing else is enabled until a product is selected**, and
 * one file loads one product.
 *
 * <h2>Two round trips, not one</h2>
 *
 * <p>The confirm step re-sends the same {@link File} rather than relying on
 * the {@link ModuleImportPreviewData} already on screen: the server writes
 * nothing on preview, so a stale preview and a server-side change (someone
 * publishing one of these services, or editing the Implementation Stage
 * master, in another tab) are only caught by validating again at commit time.
 * `422` there carries the identical row errors, and this dialog renders it
 * exactly like a failed preview rather than as a generic toast at the last
 * step.
 *
 * <h2>Why not the generic Import Wizard</h2>
 *
 * <p>`feature/imports`' wizard (mapping step, batch history, Reverse) is built
 * for a flat entity upserted on one natural key. This file describes a
 * four-level tree whose lower levels have no business key at all, and replaces
 * a draft's whole task tree — see `ObModuleServiceImportService` on the
 * backend. So this is a small purpose-built flow: pick, download, upload,
 * preview, confirm.
 */
export function ModuleServiceImportDialog({ open, onOpenChange }: ModuleServiceImportDialogProps) {
  const [productId, setProductId] = React.useState<number | null>(null)
  const [file, setFile] = React.useState<File | null>(null)
  const [preview, setPreview] = React.useState<ModuleImportPreviewData | null>(null)

  const products = useProducts()
  const download = useDownloadModuleServiceImportTemplate()
  const previewImport = usePreviewObModuleServiceImport()
  const commitImport = useCommitObModuleServiceImport()
  const invalidate = useInvalidateAfterModuleServiceImport()

  React.useEffect(() => {
    if (!open) {
      setProductId(null)
      setFile(null)
      setPreview(null)
      previewImport.reset()
      commitImport.reset()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  /*
    Changing the product invalidates the preview as surely as changing the
    file does: the same rows resolve to different services, and CREATE becomes
    REPLACE or a refusal. Keeping the old preview on screen would offer a
    Confirm button for a run that was never validated.
  */
  function pickProduct(next: number | null) {
    setProductId(next)
    setPreview(null)
    previewImport.reset()
    commitImport.reset()
  }

  function pickFile(next: File | null) {
    setFile(next)
    setPreview(null)
    previewImport.reset()
    commitImport.reset()
  }

  async function runPreview() {
    if (!file || productId == null) return
    try {
      const response = await previewImport.mutateAsync({ data: { productId, file } })
      setPreview(response.data)
    } catch (error) {
      toast({ title: 'Could not read that file', description: problemDetail(error), variant: 'danger' })
    }
  }

  async function confirmCommit() {
    if (!file || productId == null) return
    try {
      const response = await commitImport.mutateAsync({ data: { productId, file } })
      await invalidate()
      const { servicesCreated, servicesReplaced, taskCount, checklistCount } = response.data
      toast({
        title: 'Module Services imported',
        description:
          `${servicesCreated} created, ${servicesReplaced} replaced — `
          + `${taskCount} task${taskCount === 1 ? '' : 's'} and `
          + `${checklistCount} checklist item${checklistCount === 1 ? '' : 's'}.`,
      })
      onOpenChange(false)
    } catch (error) {
      const invalid = invalidProblem(error)
      if (invalid) {
        // The re-uploaded file stopped validating between preview and confirm
        // — read exactly like a failed preview rather than a bare toast.
        setPreview({ valid: false, errors: invalid.errors, services: [] })
        return
      }
      toast({ title: 'Import failed', description: problemDetail(error), variant: 'danger' })
    }
  }

  const busy = previewImport.isPending || commitImport.isPending
  const chosen = productId != null

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>Import Module Services from a spreadsheet</ModalTitle>
          <ModalDescription>
            One sheet of four columns &mdash; Module Service, Step, Task, Checklist. A service
            the product does not have yet is created as a draft; an existing draft has its
            whole task tree replaced. Nothing is written until you confirm the preview.
          </ModalDescription>
        </ModalHeader>

        <div className="space-y-4 px-6 py-2">
          <label className="block text-sm font-medium text-content" htmlFor="ms-import-product">
            Product
            <select
              id="ms-import-product"
              className="mt-1 block h-9 w-full rounded-control border border-border bg-surface px-2 text-sm font-normal text-content"
              value={productId ?? ''}
              disabled={busy || products.isPending}
              onChange={(e) => pickProduct(e.target.value === '' ? null : Number(e.target.value))}
            >
              <option value="">Choose a product&hellip;</option>
              {(products.data ?? []).map((product) => (
                <option key={product.id} value={product.id}>
                  {product.name}
                  {product.isActive ? '' : ' · retired'}
                </option>
              ))}
            </select>
            <span className="mt-1 block text-caption font-normal text-content-muted">
              Every Module Service in the file belongs to this product. One file loads one
              product.
            </span>
          </label>

          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => download.mutate()}
            disabled={!chosen || download.isPending}
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
                disabled={!chosen || busy}
                onChange={(event) => pickFile(event.target.files?.[0] ?? null)}
                className="text-sm text-content-muted file:mr-3 file:rounded-control file:border-0 file:bg-subtle file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-content"
              />
            </div>
          </label>

          {chosen && file && !preview && (
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
                    {rowError.rowNumber > 0 ? `Row ${rowError.rowNumber}: ` : ''}
                    {rowError.message}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {preview && preview.valid && <ImportTree services={preview.services} />}
        </div>

        <ModalFooter>
          <Button type="button" variant="secondary" size="sm" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={confirmCommit}
            disabled={!chosen || !preview?.valid || busy}
          >
            {commitImport.isPending ? 'Importing…' : 'Confirm import'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/**
 * The four levels, nested — Module Service → Step → Task → Checklist, which is
 * the same shape the designer draws once the import lands.
 *
 * <h2>Why the tree and not the counts it replaced</h2>
 *
 * <p>This block used to end at the Step, with "3 tasks, 7 checklist items"
 * beside it. Those numbers confirm the file parsed; they do not confirm it
 * parsed the way the author meant. The file is flat — one row per checklist
 * entry, with Module Service, Step and Task repeated down the rows that share
 * them — and the one thing authors get wrong is exactly what that repetition
 * means: three rows naming one task are **one** task with three checklist
 * entries, not three tasks. A count of 3 reads identically under both
 * readings, so the summary was silent on the only question the preview is
 * there to answer. Drawn out, a mis-typed Task cell is visible as a task that
 * split in two.
 *
 * <h2>Every task shows at least one checklist entry</h2>
 *
 * <p>A task whose rows all left the Checklist column blank still draws one,
 * named after the task itself. That is not this component being generous — it
 * is what the server will write (`TaskBuilder#checklistOrDefault`), so the
 * preview stays the thing that is about to happen rather than a tidier
 * version of it.
 *
 * <h2>Plain nested lists, nothing collapsible</h2>
 *
 * <p>Read once, in a modal, to answer "is this what I meant?" — a control that
 * hides a level works against the only reason to look. Nesting and item counts
 * are already announced by list semantics, so no ARIA is added over them.
 */
function ImportTree({ services }: { services: readonly ModuleImportServiceDto[] }) {
  const totals = services.reduce(
    (acc, service) => {
      for (const step of service.steps) {
        acc.steps += 1
        for (const task of step.tasks) {
          acc.tasks += 1
          acc.checklist += task.checklist.length
        }
      }
      return acc
    },
    { steps: 0, tasks: 0, checklist: 0 },
  )

  return (
    <div className="rounded-card border border-border bg-subtle p-3 text-sm">
      <p className="m-0 font-medium text-content">
        {plural(services.length, 'Module Service')}, {plural(totals.steps, 'step')},{' '}
        {plural(totals.tasks, 'task')}, {plural(totals.checklist, 'checklist item')} ready to
        import
      </p>

      <ul className="mt-2 max-h-72 space-y-3 overflow-y-auto" data-testid="ms-import-tree">
        {services.map((service) => (
          <li key={service.name}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium text-content">{service.name}</span>
              {/*
                CREATE and REPLACE are the whole reason the preview exists: an
                admin must see which of their drafts is about to be
                overwritten before they press Confirm. A chip rather than a
                clause, because REPLACE discards an existing tree and is the
                one thing on this screen somebody might not have intended.
              */}
              <Chip variant={service.action === 'CREATE' ? 'success' : 'warning'}>
                {service.action === 'CREATE' ? 'New draft' : 'Replaces existing draft'}
              </Chip>
            </div>

            <ul className="mt-1 space-y-1.5 border-l border-border pl-3">
              {service.steps.map((step) => (
                <li key={step.name}>
                  <div className="flex items-center gap-1.5 text-content">
                    <Layers className="h-3.5 w-3.5 shrink-0 text-content-muted" aria-hidden="true" />
                    {step.name}
                  </div>

                  <ul className="mt-1 space-y-1 border-l border-border pl-3">
                    {step.tasks.map((task) => (
                      <li key={task.name}>
                        <div className="flex items-center gap-1.5 text-content-muted">
                          <ListTree className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                          {task.name}
                        </div>

                        <ul className="mt-0.5 space-y-0.5 border-l border-border pl-3">
                          {task.checklist.map((label, index) => (
                            <li
                              key={`${label}-${index}`}
                              className="flex items-center gap-1.5 text-caption text-content-muted"
                            >
                              <CheckSquare className="h-3 w-3 shrink-0" aria-hidden="true" />
                              {label}
                            </li>
                          ))}
                        </ul>
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  )
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

function invalidProblem(error: unknown): ModuleImportInvalidProblem | null {
  if (!(error instanceof ApiError)) return null
  const problem = error.problem as Partial<ModuleImportInvalidProblem>
  return Array.isArray(problem.errors) ? (problem as ModuleImportInvalidProblem) : null
}

function problemDetail(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Reload the page and try again.'
  return error.problem.detail ?? error.problem.title ?? 'Reload the page and try again.'
}
