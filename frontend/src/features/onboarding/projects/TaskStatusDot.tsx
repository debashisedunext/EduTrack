import { cn } from '@/lib/utils'

import { taskDotLabel, taskDotState, type TaskDotState } from './taskStatus'
import type { ProjectTask } from './useProjectTasks'

/**
 * A task's state as one coloured circle — what the task strip wears instead of
 * a status pill.
 *
 * <h2>A dot, because the strip is scanned rather than read</h2>
 *
 * <p>The rows under a Step are a column, and a column of words in five
 * different widths — <i>Complete</i>, <i>In progress</i>, <i>Waiting on
 * client</i> — pushes every task's name to a different starting column and
 * makes the one thing a reader is scanning for the hardest thing to compare.
 * Four hues in a fixed column answer "which of these need me" at a glance, and
 * the task's name starts in the same place on every row.
 *
 * <h2>The same four colours the Step strip already uses</h2>
 *
 * <p>Green done, indigo running, red late, grey untouched — the beads above
 * the rows and the dots on the Module strip are drawn from these same tokens,
 * so one palette means one thing everywhere on the page. See
 * {@link TaskDotState} for why there are four of them and not six.
 *
 * <h2>Never colour alone</h2>
 *
 * <p>Blueprint §12.1. The dot is an image with a name — its colour in words,
 * plus the exact status where that says more — so it is readable on hover and
 * announced by a screen reader, and the task popup still prints the status as
 * a word for anybody who wants all six.
 */

/**
 * The palette itself, named by hue rather than by meaning.
 *
 * <h2>Why a hue layer at all</h2>
 *
 * <p>Three levels of this screen now wear the same four circles, and they do
 * not share a vocabulary: a task is Pending / In process / Overdue /
 * Completed, while a check-list item is Not answered / Completed / Not
 * completed and has no notion of a clock. Keying the classes on the *task's*
 * states would have forced the check list to call its Not completed rows
 * "OVERDUE" to get a red ring — a lie in the DOM, in every test that reads
 * `data-state`, and in the next person's head.
 *
 * <p>So the states map to tones ({@link TASK_DOT_TONE}) and the tones map to
 * classes. One palette, and each level keeps its own words for what the
 * colours mean.
 *
 * <p>Amber is the fifth and belongs to one level only: a Step can be
 * <i>Waiting</i>, which is a state a task has no word for — its own
 * `WAITING_ON_CLIENT` colours as In process, because a paused clock is still
 * work somebody started. The hue exists here rather than in a second map in
 * `ObStepTimeline` so the palette stays one file.
 */
export type DotTone = 'grey' | 'blue' | 'red' | 'green' | 'amber'

/** Filled — the dense column on the task strip, where weight is scannable. */
const DISC: Record<DotTone, string> = {
  grey: 'border-ribbon-pending bg-ribbon-pending',
  blue: 'border-primary bg-primary',
  red: 'border-ribbon-blocked bg-ribbon-blocked',
  green: 'border-ribbon-done bg-ribbon-done',
  amber: 'border-ribbon-waiting bg-ribbon-waiting',
}

/**
 * The same four hues as a ring — the border only, over the surface it sits on.
 *
 * <p>Same tokens, deliberately: a hollow dot is the filled one with its middle
 * taken out, not a second palette. The border widens to 2px because 1.5px of
 * colour around an empty 12px circle is close to invisible at a glance, which
 * is the one thing a status column cannot be.
 */
const RING: Record<DotTone, string> = {
  grey: 'border-ribbon-pending',
  blue: 'border-primary',
  red: 'border-ribbon-blocked',
  green: 'border-ribbon-done',
  amber: 'border-ribbon-waiting',
}

/**
 * The same four hues as a filled chip, for a circle with a figure in it.
 *
 * <p>Three parts rather than one: the ring so it reads as the same circle the
 * plain dot draws, a wash so the digit sits on something rather than on the
 * page, and the tone's own text colour so the figure belongs to its bucket
 * instead of being black text near a coloured ring. The timeline's Step beads
 * are built from exactly these triples — same tokens, so the two cannot drift.
 */
const FILL: Record<DotTone, string> = {
  grey: 'border-ribbon-pending bg-ribbon-pending-bg text-ribbon-pending-text',
  blue: 'border-primary bg-primary-soft text-primary',
  red: 'border-ribbon-blocked bg-ribbon-blocked-bg text-ribbon-blocked-text',
  green: 'border-ribbon-done bg-ribbon-done-bg text-ribbon-done-text',
  amber: 'border-ribbon-waiting bg-ribbon-waiting-bg text-ribbon-waiting-text',
}

/** Which hue each of the task's four states wears. */
const TASK_DOT_TONE: Record<TaskDotState, DotTone> = {
  PENDING: 'grey',
  IN_PROCESS: 'blue',
  OVERDUE: 'red',
  COMPLETED: 'green',
}

export function TaskStatusDot({ task }: { task: ProjectTask }) {
  return <StatusDot state={taskDotState(task)} label={taskDotLabel(task)} />
}

/**
 * The circle itself, for a row whose state is already worked out.
 *
 * <p>My Tasks' queue is the other caller: its rows carry a status and the
 * server's overdue flag rather than a {@link ProjectTask}, and the point of
 * sharing this is that the same four hues mean the same four things on both
 * screens. A second dot drawn from its own class map is how one of them ends
 * up amber a month later.
 *
 * <p>`hollow` draws the ring instead of the disc: the queue prints one dot per
 * row with white space all around it, where an outline reads as a marker,
 * while the strip's dots sit in a dense column and want the weight of a filled
 * disc to be scannable. Same four hues either way.
 *
 * <p>`label` is omitted in one place and one only: the legend on My Tasks,
 * where the word is printed beside the circle and naming the circle as well
 * would have a screen reader announce "Overdue" twice for one entry. It asks
 * this rather than reaching for the palette map, so the key and the column are
 * drawn by the same function and cannot come to mean different colours.
 */
export function StatusDot({
  state,
  label,
  hollow = false,
}: {
  state: TaskDotState
  /** Omitted where something beside the dot already prints the word. */
  label?: string
  hollow?: boolean
}) {
  return (
    <ToneDot
      tone={TASK_DOT_TONE[state]}
      label={label}
      hollow={hollow}
      dataState={state}
      testId="ob-task-dot"
    />
  )
}

/**
 * One circle in one of the four hues, for a level with its own vocabulary.
 *
 * <h2>`inline-block`, which is not a nicety</h2>
 *
 * <p>A bare `<span>` is inline, and an inline box ignores width and height —
 * so in a table cell this drew as an 11px-tall bar one border wide. The strip
 * hid the bug by putting it in a flex row, where a flex item is blockified for
 * free. Stating it here means the dot is a circle wherever it is dropped.
 *
 * <h2>Named, unless something around it already says the word</h2>
 *
 * <p>With a `label` it is an image carrying that name — never colour alone
 * (blueprint §12.1). Without one it is decorative and hidden from the
 * accessibility tree, which is what it must be inside a control whose own
 * label already states the answer and what pressing will do: announcing
 * "Completed" twice, once as the button and once as a picture inside it, is
 * how a two-word control becomes a four-word one.
 */
export function ToneDot({
  tone,
  label,
  hollow = false,
  dataState,
  testId = 'ob-status-dot',
}: {
  tone: DotTone
  /** Omitted where the surrounding control already names the state. */
  label?: string
  hollow?: boolean
  /** What the level calls this state — its own word, not the tone's. */
  dataState?: string
  testId?: string
}) {
  return (
    <span
      {...(label ? { role: 'img', 'aria-label': label, title: label } : { 'aria-hidden': true })}
      data-state={dataState}
      data-tone={tone}
      data-testid={testId}
      className={cn(
        'inline-block shrink-0 rounded-chip align-middle',
        hollow ? `size-3 border-2 bg-transparent ${RING[tone]}` : `size-[11px] border-[1.5px] ${DISC[tone]}`,
      )}
    />
  )
}

/**
 * The same circle with its figure <em>inside</em> it.
 *
 * <h2>Why the number moved in</h2>
 *
 * <p>A ring beside a digit is two marks a reader has to pair up, and three of
 * them in a row is six. Which digit belongs to which hue is then a matter of
 * spacing — and spacing is the first thing to go when a Step's name is long
 * enough to wrap. Inside, the pairing is not something the reader does at all:
 * the colour and the figure are one object, and a column of Steps can be
 * scanned down a fixed position rather than read across.
 *
 * <p>It also buys the hue some room. A 12px ring carrying no content was two
 * pixels of colour; a 24px chip with a wash behind the digit is legible at the
 * distance somebody actually reads a header from.
 *
 * <h2>Still never colour alone</h2>
 *
 * <p>Blueprint &sect;12.1 is satisfied more directly than before: the figure is
 * literal text in the circle, so the count survives any failure to distinguish
 * green from red. The word — <em>verified</em>, <em>rejected</em> — still comes
 * from the `title`, and from the group's own name one level up.
 *
 * <p><b>Two digits fit; three do not.</b> `min-w` with horizontal padding lets
 * the chip stretch into a lozenge rather than clip, which is the right failure:
 * a Step with 14 verified tasks is unusual and a slightly wider pill is a far
 * smaller problem than a `1` where a `14` should be.
 */
export function ToneCount({
  tone,
  count,
  label,
  bucket,
  testId = 'ob-status-count',
}: {
  tone: DotTone
  count: number
  /** The word behind the hue — shown on hover, and read out. */
  label: string
  /** Which bucket this is, for a test that does not want to read colours. */
  bucket?: string
  testId?: string
}) {
  return (
    <span
      title={label}
      data-bucket={bucket}
      data-tone={tone}
      data-testid={testId}
      className={cn(
        'inline-grid h-6 min-w-6 shrink-0 place-items-center rounded-chip border-[1.5px] px-1',
        'text-[11px] font-semibold leading-none tabular-nums align-middle',
        FILL[tone],
      )}
    >
      {count}
    </span>
  )
}
