import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * B-011 · the S-08 form's field wrapper.
 *
 * <h2>This is a near-copy of `features/tickets/create/FormField.tsx`, and that
 * is deliberate rather than careless</h2>
 *
 * By this codebase's own rule the second caller is the trigger to promote a
 * control to `components/ui/` — which is exactly what B-010 did with
 * `FilterDropdown`. **`components/ui/` is Stream C's path**, that move already
 * needed their sign-off, and making a second unilateral change to it in the
 * same week is not a thing one stream should do on its own branch.
 *
 * So: duplicated here, and **nominated for promotion to
 * `components/ui/form-field.tsx`** the moment Stream C is asked. When that
 * happens both copies go and the two call sites import the shared one. The
 * shape is kept identical to C's on purpose so the merge is a delete rather
 * than a reconciliation.
 */

/** ARIA the control has to carry so the label, hint and error reach a screen reader. */
export interface FieldAria {
  id: string
  /**
   * Carried as well as `<label htmlFor>`, because `for` only names *labelable*
   * elements. A composite control — a listbox, a toggle group — reaches a
   * screen reader unnamed without this, which fails AA.
   */
  'aria-labelledby': string
  'aria-invalid'?: true
  'aria-describedby'?: string
}

export interface FormFieldProps {
  id: string
  label: string
  required?: boolean
  hint?: ReactNode
  error?: string
  className?: string
  /**
   * Render prop rather than plain children: the control needs the generated
   * `aria-describedby`, and passing it through `children` would mean cloning
   * elements — which breaks the moment a caller wraps its input.
   */
  children: (aria: FieldAria) => ReactNode
}

export function FormField({ id, label, required, hint, error, className, children }: FormFieldProps) {
  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean).join(' ')
  const aria: FieldAria = {
    id,
    'aria-labelledby': `${id}-label`,
    ...(error ? { 'aria-invalid': true as const } : {}),
    ...(describedBy ? { 'aria-describedby': describedBy } : {}),
  }

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label id={`${id}-label`} htmlFor={id} className="text-sm font-medium text-content">
        {label}
        {required && (
          <>
            <span aria-hidden className="ml-0.5 text-danger-text">
              *
            </span>
            <span className="sr-only"> (required)</span>
          </>
        )}
      </label>
      {children(aria)}
      {hint && (
        <p id={`${id}-hint`} className="text-caption text-content-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-caption text-danger-text">
          {error}
        </p>
      )}
    </div>
  )
}

/** One of S-08's five sections: Personal · Access · Org · Work · Projects. */
export function FieldGroup({
  title,
  description,
  columns = 2,
  children,
}: {
  title: string
  description?: string
  /** One column for the sections whose controls are full-width. */
  columns?: 1 | 2
  children: ReactNode
}) {
  return (
    <fieldset className="rounded-card border border-border bg-surface p-6 shadow-rest">
      <legend className="px-1 text-h3 text-content">{title}</legend>
      {description && <p className="mb-4 mt-1 text-caption text-content-muted">{description}</p>}
      <div className={cn('grid gap-5', columns === 2 && 'sm:grid-cols-2', description ? '' : 'mt-4')}>
        {children}
      </div>
    </fieldset>
  )
}

/** A value the form shows but never sends — server-owned. */
export function ReadOnlyField({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-sm font-medium text-content">{label}</span>
      <div className="flex min-h-10 items-center rounded-control border border-dashed border-border bg-subtle px-3 text-sm text-content-muted">
        {value}
      </div>
      {hint && <p className="text-caption text-content-muted">{hint}</p>}
    </div>
  )
}
