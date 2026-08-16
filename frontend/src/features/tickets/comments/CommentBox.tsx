import * as React from 'react'

import { Button } from '@/components/ui/button'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import {
  RICH_TEXT_COMPACT_TOOLBAR,
  RICH_TEXT_MAX_LENGTH,
  isRichTextEmpty,
} from '@/components/ui/rich-text'
import { cn } from '@/lib/utils'

/**
 * S-20's comment box — C-029, blueprint §4B.5.
 *
 * Sits directly under the description and the attachment strip and **above the
 * tabs**, where §7's S-20 wireframe puts it. §7 says why it lives outside the
 * tab strip rather than inside the Comments tab: "the comment box itself is
 * always visible above the tabs so posting never costs a click". Somebody who
 * has just read the description and wants to answer it should not have to find
 * a tab first.
 *
 * ## What this is not
 *
 * Not the Comments tab — that is `CommentThread`, which renders the stream below
 * it. Not the `@mention` type-ahead (C-030), not the five-minute edit window
 * (C-033).
 *
 * ## C-031 · the visibility toggle
 *
 * §4B.5 asks for a toggle per comment, and §17's risk register is more specific
 * about what it has to look like: "Comments and attachments default to internal;
 * client-visible is an explicit toggle **shown in a different colour before
 * posting**". Both halves matter, and the second is the one that is easy to skip.
 * A toggle that changes nothing on screen until after the comment has gone is a
 * toggle that gets left in the wrong position, and §16 priced that mistake:
 * "an accidental leak is far costlier than an extra click."
 *
 * So three things, none of them decoration:
 *
 * 1. **Internal is the initial state, and the state returned to after every
 *    post.** Not sticky. Somebody who sends one client-visible summary and then
 *    goes back to pasting stack traces should not have to remember to switch
 *    back — see `submit`.
 * 2. **Client-visible restyles the whole composer**, not just the control that
 *    was clicked, so the warning is in the same glance as the text being typed.
 * 3. **The toggle is only offered when there is a client to be visible to.** On
 *    an internal ticket it is absent rather than disabled: there is no choice to
 *    make, and a greyed control invites someone to hunt for the thing that would
 *    enable it.
 *
 * Colour is never the only signal — §12.1's rule, and the reason the amber is
 * paired with a `⚠` and a sentence naming the client. Somebody who cannot
 * distinguish the tint reads who will see this comment in words.
 */
export function CommentBox({
  onPost,
  isPosting,
  postError,
  disabled = false,
  disabledReason,
  clientName,
}: {
  /**
   * Resolves when the server has accepted it; rejects and the draft survives.
   *
   * `isClientVisible` is passed positionally rather than as an options object
   * because `useTicketComments.post` has had this exact signature since C-029 —
   * the seam was left open for this task.
   */
  onPost: (body: string, isClientVisible?: boolean) => Promise<void>
  isPosting: boolean
  /** The server's sentence, rendered verbatim. */
  postError: string | null
  disabled?: boolean
  disabledReason?: string
  /**
   * The ticket's client, when it has one. Its presence is what decides whether
   * the visibility toggle is offered at all, and its name is what the warning
   * says out loud — "Adityanath Institute will see this" is a sentence somebody
   * checks against the thing they just typed, where "the client will see this"
   * is one they read past.
   */
  clientName?: string
}) {
  const [body, setBody] = React.useState('')

  /**
   * §4B.5 says the default "follows whether the ticket is client-raised". It
   * does not, here, and that is a recorded deviation rather than a slip —
   * PLAN.md §5 carries it, the contract, the column default and the server all
   * agree, and §16's own recommendation is the later word: internal, always.
   *
   * Deriving it from `isClientRaised` would mean the composer opens
   * client-visible on precisely the tickets where a leaked internal note is
   * most expensive.
   */
  const [isClientVisible, setIsClientVisible] = React.useState(false)

  // Belt and braces for a ticket that loses its client mid-session (a
  // reassignment, a refetch): the state may say client-visible while there is
  // no longer anyone for it to reach. The rendered control is gone in that
  // case, so without this the flag would still be sent, invisibly.
  const clientVisible = isClientVisible && clientName != null

  // Not `body.trim()`. A contentEditable is never truly empty — the browser
  // leaves `<p><br></p>` behind, which is thirteen characters of nothing — and
  // `isRichTextEmpty` is the shared check that knows it. It also counts an image
  // with no text as non-empty, which matters here: a pasted screenshot is
  // frequently the whole of what someone wants to say.
  const empty = isRichTextEmpty(body)
  const tooLong = body.length > RICH_TEXT_MAX_LENGTH
  const canPost = !empty && !tooLong && !isPosting && !disabled

  const submit = React.useCallback(async () => {
    if (!canPost) return
    const draft = body
    try {
      await onPost(draft, clientVisible)
      // Cleared only after the server has taken it. Clearing optimistically and
      // restoring on failure is the version that loses somebody's paragraph the
      // one time the network drops, and a comment is often the longest thing
      // anyone types on this page.
      setBody('')
      // Back to internal, with the draft that justified the choice. A sticky
      // toggle is how the second comment leaks: the first one was a deliberate
      // client-facing summary, the next is a stack trace, and nothing on screen
      // changed in between to prompt a second decision. Resetting costs a click
      // on consecutive client-visible comments and prevents the failure §17
      // names — §16 already priced that trade the same way.
      setIsClientVisible(false)
    } catch {
      // The message comes from `postError`; the draft stays exactly as it was so
      // the fix is to edit and press again — and so does the visibility, because
      // a refusal is about the body, and re-picking the audience for text you
      // did not change is a second chance to pick it wrong.
    }
  }, [body, canPost, clientVisible, onPost])

  /**
   * §4B.5: "Ctrl/Cmd + Enter to post".
   *
   * On the wrapper rather than the editor because `RichTextEditor` exposes no
   * `onKeyDown` — the event bubbles out of the contentEditable, which is enough,
   * and widening that shared component's props for one consumer is the kind of
   * change every other stream would have to absorb.
   *
   * `metaKey` as well as `ctrlKey`: Cmd is the modifier on macOS and a shortcut
   * that only works on Windows reads as broken rather than as absent.
   */
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey)) return
    event.preventDefault()
    void submit()
  }

  const hintId = React.useId()
  // One name shared by both radios — a `name` collision with another composer on
  // the same page would let one box's choice deselect the other's, and `useId`
  // is the only source of a value guaranteed unique per mounted instance.
  const groupName = React.useId()

  return (
    <section
      aria-labelledby="ticket-comment-box-heading"
      className={cn(
        'rounded-card border p-4 shadow-rest transition-colors',
        // §17's "different colour before posting", applied to the whole
        // composer rather than to the control alone. The tint is behind the
        // text as it is typed, which is the moment the choice is still free to
        // change; a colour that only appears on the control is one the eye has
        // already left by the time the paragraph is written.
        clientVisible ? 'border-warning bg-level-high-soft' : 'border-border bg-surface',
      )}
    >
      <h2 id="ticket-comment-box-heading" className="mb-2 text-h3 text-content">
        Add a comment
      </h2>

      {disabled ? (
        <p className="text-caption text-content-muted">
          {disabledReason ?? 'Comments cannot be added here.'}
        </p>
      ) : (
        <div className="flex flex-col gap-2" onKeyDown={onKeyDown}>
          <RichTextEditor
            value={body}
            onChange={setBody}
            disabled={isPosting}
            rows={3}
            // The short bar, built for this box by C-066: a comment is a
            // paragraph or two, and offering headings there invites people to
            // shout.
            toolbar={RICH_TEXT_COMPACT_TOOLBAR}
            placeholder="Write a comment…"
            showCount={tooLong}
            aria-label="Comment"
            aria-describedby={hintId}
            aria-invalid={tooLong || undefined}
          />

          {postError && (
            // `role="alert"` because the refusal arrives after a button press
            // with nothing else on screen changing — without it a screen-reader
            // user gets silence and a draft that did not clear.
            <p role="alert" className="text-caption text-danger-text">
              {postError}
            </p>
          )}

          {clientName != null && (
            <VisibilityToggle
              name={groupName}
              isClientVisible={clientVisible}
              onChange={setIsClientVisible}
              disabled={isPosting}
              clientName={clientName}
            />
          )}

          <div className="flex items-center justify-between gap-3">
            <p id={hintId} className="text-caption text-content-muted">
              {/*
                Stated rather than left to be discovered. It is the difference
                between the two-click daily driver §4B.5 asks for and a box
                people reach for the mouse to send, and a keyboard shortcut
                nobody is told about is one nobody uses.

                Who can see it is stated for a different reason, and it is stated
                even on a ticket with no client and therefore no toggle: a box
                that says nothing about its audience is one people assume about,
                and someone pasting a client's own words deserves to read that it
                is not going back to them rather than infer it.
              */}
              {clientVisible
                ? `${clientName} will see this. Ctrl+Enter to post.`
                : 'Internal to the team. Ctrl+Enter to post.'}
            </p>
            <Button size="sm" onClick={() => void submit()} disabled={!canPost}>
              {isPosting ? 'Posting…' : 'Post'}
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}

/**
 * §4B.5's per-comment visibility, as a segmented pair rather than §7's
 * `[Internal ▾]` dropdown.
 *
 * The wireframe draws a select, and a select is the wrong control for this one
 * job: it shows the *chosen* option and hides the alternative, so the fact that
 * a choice exists — and that this one is deliberate — is only visible to
 * somebody who already opens it. §17 asks for the state to be obvious before
 * posting, and two side-by-side options with one of them lit is obvious at a
 * glance. A one-control deviation from a wireframe, in the direction the risk
 * register points.
 *
 * Native `<input type="radio">` under the styling, not buttons with
 * `role="radio"`: arrow-key navigation, roving focus, the group announcing
 * itself as "Visibility, radio group, 2 items" and form semantics all come free
 * and all have to be hand-written otherwise. `sr-only` rather than
 * `appearance-none` so the input keeps its own focus target, which is what
 * `VisibilityOption` draws the keyboard focus ring from.
 */
function VisibilityToggle({
  name,
  isClientVisible,
  onChange,
  disabled,
  clientName,
}: {
  name: string
  isClientVisible: boolean
  onChange: (next: boolean) => void
  disabled: boolean
  clientName: string
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <div
        role="radiogroup"
        aria-label="Visibility"
        className="inline-flex w-fit gap-1 rounded-control border border-border bg-surface p-0.5"
      >
        <VisibilityOption
          name={name}
          label="Internal note"
          hint="Team only"
          checked={!isClientVisible}
          onSelect={() => onChange(false)}
          disabled={disabled}
          // §4B.5's own words for this state: "Internal note (grey background,
          // team only)". Grey is the *unremarkable* half of the pair on purpose
          // — the safe default should not compete for attention with the one
          // that carries consequences.
          selectedClassName="bg-subtle text-content"
        />
        <VisibilityOption
          name={name}
          label="Client visible"
          hint={clientName}
          checked={isClientVisible}
          onSelect={() => onChange(true)}
          disabled={disabled}
          selectedClassName="bg-level-high-soft text-warning-text ring-1 ring-warning"
        />
      </div>

      {isClientVisible && (
        /*
          Not `role="alert"`. This appears as the direct result of the reader's
          own click, and an alert would interrupt to announce something they
          just did — the radio's own state change already says it. It is here to
          be read while typing, and to name the consequence the colour alone
          cannot: the client portal and the mail thread, which is where §4B.5
          says a client-visible comment actually goes.
        */
        <p className="text-caption text-warning-text">
          <span aria-hidden="true">⚠ </span>
          {clientName} will see this comment on the client portal and in the ticket’s
          email thread.
        </p>
      )}
    </div>
  )
}

function VisibilityOption({
  name,
  label,
  hint,
  checked,
  onSelect,
  disabled,
  selectedClassName,
}: {
  name: string
  label: string
  hint: string
  checked: boolean
  onSelect: () => void
  disabled: boolean
  selectedClassName: string
}) {
  return (
    <label
      className={cn(
        'cursor-pointer rounded-control px-2.5 py-1 text-caption font-medium transition-colors',
        // The ring is drawn from the hidden input's own focus, so a keyboard
        // user arrowing through the group sees which option they are on — an
        // `sr-only` input is still the focus target, and without this its focus
        // ring is clipped away with it.
        //
        // `has-[:focus-visible]` and not `peer-focus-visible`: the input is a
        // *child* of this label, and Tailwind's `peer-*` variants compile to the
        // sibling combinator, so they would silently match nothing. Not
        // `focus-within` either — that fires on a mouse click as well, and a
        // ring left behind every click is the thing `focus-visible` exists to
        // avoid.
        'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary',
        checked ? selectedClassName : 'text-content-muted hover:bg-subtle',
        disabled && 'cursor-not-allowed opacity-60',
      )}
    >
      <input
        type="radio"
        name={name}
        checked={checked}
        onChange={onSelect}
        disabled={disabled}
        className="peer sr-only"
      />
      {label}
      {/*
        The hint rides inside the label rather than beside it, so the accessible
        name is "Client visible · Adityanath Institute" — a screen-reader user
        choosing between two options hears which client they are choosing to
        write to, at the moment of choosing, rather than having to go looking
        for a separate description.
      */}
      <span className="font-normal opacity-75"> · {hint}</span>
    </label>
  )
}
