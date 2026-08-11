import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

/**
 * A labelled field for the four auth screens.
 *
 * ## Why this is not imported from `features/tickets/create/FormField`
 *
 * That component exists, has the same ARIA contract, and belongs to Stream C.
 * Reaching into another stream's feature directory would compile this module
 * against a file I cannot change and Divyansh does not know I depend on — his
 * next refactor of the create form would break the login screen, which is the
 * exact coupling TEAM-PLAN §6 draws the ownership map to prevent.
 *
 * The honest fix is to promote one of these into `components/ui`, which is the
 * shared design system and Divyansh's to own. That is a request to him with two
 * real callers behind it, not something to do unilaterally in his directory —
 * and not a reason to hold up A-030 in the meantime. Noted in this feature's
 * README so it is a queued conversation rather than forgotten duplication.
 *
 * The render prop matches his deliberately: the control needs the generated
 * `aria-describedby`, and passing it through `children` would mean cloning
 * elements, which breaks as soon as a caller wraps its input.
 */
export interface FieldAria {
  id: string;
  'aria-invalid'?: true;
  'aria-describedby'?: string;
}

export function AuthField({
  id,
  label,
  hint,
  error,
  className,
  labelSuffix,
  children,
}: {
  id: string;
  label: string;
  hint?: ReactNode;
  error?: string;
  className?: string;
  /** A link rendered on the label's row — "Forgot password?" beside Password. */
  labelSuffix?: ReactNode;
  children: (aria: FieldAria) => ReactNode;
}) {
  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null]
    .filter(Boolean)
    .join(' ');

  const aria: FieldAria = {
    id,
    ...(error ? { 'aria-invalid': true as const } : {}),
    ...(describedBy ? { 'aria-describedby': describedBy } : {}),
  };

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <div className="flex items-baseline justify-between gap-2">
        <label htmlFor={id} className="text-sm font-medium text-content">
          {label}
        </label>
        {labelSuffix}
      </div>
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
  );
}

/**
 * The form-level message — wrong credentials, a locked account, an expired link.
 *
 * `role="alert"` because it appears in response to a submit the user has already
 * made; without it the screen reader announces nothing and the user is left with
 * a form that simply did not proceed. Not `aria-live="assertive"` on a static
 * node, which announces on mount and would fire on every render that keeps it.
 */
export function AuthAlert({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="rounded-control border border-danger/40 bg-level-critical-soft px-3 py-2 text-sm text-danger-text"
    >
      {children}
    </div>
  );
}

/** The same shape in the affirmative — a reset link sent, a password changed. */
export function AuthNotice({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="rounded-control border border-success/40 bg-level-low-soft px-3 py-2 text-sm text-success-text"
    >
      {children}
    </div>
  );
}
