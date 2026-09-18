import type * as React from 'react'

import { cn } from '@/lib/utils'

import { StatusDot } from '@/features/onboarding/projects/TaskStatusDot'
import { TASK_DOT_LABEL, type TaskDotState } from '@/features/onboarding/projects/taskStatus'

import { FLAG_BANNER_CLASS, FLAG_MARK, type FlagTone } from './myTaskFlags'

/**
 * What the colours on My Tasks mean — the key to the queue, above the queue.
 *
 * <h2>Why the page needs one at all</h2>
 *
 * <p>The Status column is four hues rather than six words, and the rows tint
 * themselves in three more when a review has touched them. That is seven
 * colours on one screen, and every one of them was learnable only by hovering
 * the right pixel or by opening a task and reading a banner. A queue whose
 * vocabulary has to be discovered is a queue that gets read as "some rows are
 * coloured".
 *
 * <p>Each colour still says its own name on hover and to a screen reader —
 * blueprint §12.1 has never been satisfied by a legend, because a legend is
 * itself a colour lookup and is no use to somebody who cannot tell two swatches
 * apart. This is the sighted reader's shortcut, not the accessible path.
 *
 * <h2>Two groups, because the two colours answer different questions</h2>
 *
 * <p>The dot is about the <em>task</em> — is it done, moving, late, or has
 * nobody started it. The row tint is about its <em>check list</em> — whether a
 * reviewer has sent rows back, passed them, or is still holding them. A task
 * can be blue and its row red at the same time and both are true, so they are
 * drawn as two keys rather than one list of seven.
 *
 * <h2>Always drawn, never folded away</h2>
 *
 * <p>A legend behind a disclosure is found by the people who already know what
 * the colours mean. It costs one wrapped line and it is the line that makes the
 * other twenty readable.
 */

/** The four the Status column paints, in the order a task passes through them. */
const STATUS_KEY: { state: TaskDotState; hint: string }[] = [
  { state: 'PENDING', hint: 'nobody has started it' },
  { state: 'IN_PROCESS', hint: 'being worked on — blocked and waiting on client too' },
  { state: 'OVERDUE', hint: 'past its due date' },
  { state: 'COMPLETED', hint: 'complete or waived' },
]

/** The three a review paints on the row itself, in the order they matter. */
const FLAG_KEY: { tone: FlagTone; word: string; hint: string }[] = [
  { tone: 'back', word: 'Came back', hint: 'your reviewer returned rows — open the task to see why' },
  { tone: 'good', word: 'Approved', hint: 'your reviewer passed rows; nothing to do' },
  { tone: 'review', word: 'Out for verification', hint: 'rows are with your reviewer' },
]

export function ObMyTasksLegend() {
  return (
    <section
      data-testid="ob-my-tasks-legend"
      aria-label="What the colours on this page mean"
      className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-control border border-border bg-subtle px-3.5 py-2"
    >
      <Group label="Status">
        {STATUS_KEY.map(({ state, hint }) => (
          <Entry key={state} word={TASK_DOT_LABEL[state]} hint={hint}>
            {/*
              The queue's own dot, drawn by the queue's own component — hollow
              because that is how the column draws it, and unnamed because the
              word is right beside it and a screen reader would otherwise hear
              "Overdue" twice.
            */}
            <StatusDot state={state} hollow />
          </Entry>
        ))}
      </Group>

      <Group label="Row highlight">
        {FLAG_KEY.map(({ tone, word, hint }) => (
          <Entry key={tone} word={word} hint={hint}>
            {/*
              A swatch rather than a dot: the row wears this as a tint and a
              coloured edge, so the key shows a tinted block with its mark in
              it rather than a circle the rows never draw.
            */}
            <span
              aria-hidden="true"
              data-tone={tone}
              data-testid="ob-legend-swatch"
              className={cn(
                'inline-grid size-[15px] shrink-0 place-items-center rounded-[4px] border',
                'text-[9px] font-semibold leading-none',
                FLAG_BANNER_CLASS[tone],
              )}
            >
              {FLAG_MARK[tone]}
            </span>
          </Entry>
        ))}
      </Group>
    </section>
  )
}

/**
 * One key and its entries.
 *
 * <p>A `dl` because that is what it is — a term and what the term means —
 * rather than a list of spans that happen to sit in pairs.
 */
function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
      <h2 className="m-0 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-content-muted">
        {label}
      </h2>
      <dl className="m-0 flex flex-wrap items-center gap-x-4 gap-y-1.5">{children}</dl>
    </div>
  )
}

/**
 * A swatch, the word it means, and the sentence behind the word.
 *
 * <p>The hint is the `title` rather than a third column of text: four entries
 * each carrying a clause would be a paragraph above the table, and the word
 * alone is what a reader is matching against the colour they just saw. The
 * sentence is there for the one they cannot place.
 */
function Entry({
  word,
  hint,
  children,
}: {
  word: string
  hint: string
  children: React.ReactNode
}) {
  return (
    <div className="flex items-center gap-1.5" title={`${word} — ${hint}`}>
      <dt className="m-0 flex items-center">{children}</dt>
      <dd className="m-0 text-[11px] font-medium text-content-muted">{word}</dd>
    </div>
  )
}
