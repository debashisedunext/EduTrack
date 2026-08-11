import * as React from 'react'
import { cn } from '@/lib/utils'
import { richTextProseClasses, sanitizeRichText } from './rich-text'

export interface RichTextViewProps extends Omit<React.HTMLAttributes<HTMLDivElement>, 'children' | 'dangerouslySetInnerHTML'> {
  /** Stored HTML. Sanitised here on every render — see the note below. */
  html: string
  /** Rendered when the value is empty, so callers don't each invent their own. */
  emptyText?: React.ReactNode
}

/**
 * The read half of PLAN.md §3.9 — the **only** sanctioned way to render stored
 * rich text.
 *
 * Sanitising here rather than trusting the write path is §3.9's rule, not
 * belt-and-braces: rows written months ago went through whatever the allow-list
 * was then, and running the render path through today's list is what makes
 * tightening it apply to data already in the table. It is also the reason this
 * component exists at all — one `dangerouslySetInnerHTML` in the codebase, in a
 * file whose whole job is to be reviewed, beats the same call appearing in
 * whichever feature needed it next.
 *
 * Reviewers: a `dangerouslySetInnerHTML` anywhere outside this file is a bug.
 */
export function RichTextView({ html, emptyText, className, ...props }: RichTextViewProps) {
  // Sanitisation walks the DOM twice, and a ticket list rendering 50 previews
  // would do it on every keystroke of a filter without this.
  const clean = React.useMemo(() => sanitizeRichText(html), [html])

  if (!clean) {
    return emptyText ? <p className="text-sm text-content-muted">{emptyText}</p> : null
  }

  return (
    <div
      {...props}
      className={cn(richTextProseClasses, className)}
      dangerouslySetInnerHTML={{ __html: clean }}
    />
  )
}

