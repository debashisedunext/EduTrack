import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

/**
 * C-121 · CP-01's chrome — `AuthCard`/`AuthField`'s shapes, kept local to
 * `features/portal/` rather than imported from `features/auth/`.
 *
 * `AuthField`'s own file explains why the staff versions are not reached
 * into: they are Stream A's, and depending on them couples the portal's
 * forced-change screen to a refactor nobody there knows this depends on.
 * These are small enough that a second copy is cheaper than the coupling —
 * the same call that file itself makes about not reaching into Stream C's
 * ticket form.
 */

export function PortalAuthCard({
  title,
  description,
  children,
  footer,
}: {
  title: string
  description?: ReactNode
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gradient-to-b from-primary-soft to-app px-4 py-10">
      <main className="w-full max-w-[400px]">
        <div className="mb-6 flex flex-col items-center gap-3">
          <div className="flex items-center gap-2">
            <span
              aria-hidden="true"
              className="flex h-9 w-9 items-center justify-center rounded-control bg-primary text-base font-semibold text-white"
            >
              E
            </span>
            <span className="text-h1 font-semibold tracking-tight text-content">EduTrack</span>
          </div>
          <p className="text-caption text-content-muted">Client portal</p>
        </div>

        <div className="rounded-card border border-border bg-surface p-6 shadow-modal">
          <h1 className="text-h2 text-content">{title}</h1>
          {description ? <p className="mt-1 text-sm text-content-muted">{description}</p> : null}
          <div className="mt-5">{children}</div>
        </div>

        {footer ? <div className="mt-4 text-center text-sm">{footer}</div> : null}
      </main>
    </div>
  )
}

export interface PortalFieldAria {
  id: string
  'aria-invalid'?: true
  'aria-describedby'?: string
}

export function PortalAuthField({
  id,
  label,
  hint,
  error,
  className,
  children,
}: {
  id: string
  label: string
  hint?: ReactNode
  error?: string
  className?: string
  children: (aria: PortalFieldAria) => ReactNode
}) {
  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null]
    .filter(Boolean)
    .join(' ')

  const aria: PortalFieldAria = {
    id,
    ...(error ? { 'aria-invalid': true as const } : {}),
    ...(describedBy ? { 'aria-describedby': describedBy } : {}),
  }

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium text-content">
        {label}
      </label>
      {children(aria)}
      {hint ? (
        <p id={`${id}-hint`} className="text-caption text-content-muted">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-caption text-danger-text">
          {error}
        </p>
      ) : null}
    </div>
  )
}

export function PortalAuthAlert({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="rounded-control border border-danger/40 bg-level-critical-soft px-3 py-2 text-sm text-danger-text"
    >
      {children}
    </div>
  )
}

export function PortalAuthNotice({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="rounded-control border border-success/40 bg-level-low-soft px-3 py-2 text-sm text-success-text"
    >
      {children}
    </div>
  )
}
