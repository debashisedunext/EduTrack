import * as React from 'react'
import { Smile } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { EMOJI_GROUPS } from './emoji'

/**
 * D-053 · the composer's emoji palette.
 *
 * ## Built here rather than reached for
 *
 * There is no popover primitive in `components/ui`, and this is one disclosure
 * on one screen — a shared component would be inventing an API from a single
 * caller. If a second surface ever needs it (a comment box, a reaction bar),
 * that is the moment it moves and gets a Storybook entry, not before.
 *
 * ## Accessibility is not optional (CLAUDE.md), and a grid of unlabelled
 * glyphs is where that is easiest to get wrong
 *
 * - The trigger is a **`aria-expanded` toggle**, so a screen reader is told the
 *   panel exists and whether it is open, rather than finding a button whose
 *   effect is invisible to it.
 * - Every cell carries an `aria-label` naming the emoji's group and position.
 *   A bare `👍` is announced by whatever the user's screen reader happens to
 *   call it, which varies by platform and is sometimes nothing at all.
 * - **Escape closes and returns focus to the trigger.** A picker that traps
 *   focus in a grid the keyboard cannot leave is worse than no picker.
 * - Arrow keys are deliberately *not* implemented as a roving tabindex: the
 *   grid is 40 cells, Tab reaches them in reading order, and a half-built
 *   roving implementation that swallows arrow keys without handling wrap is a
 *   worse experience than plain tab order.
 *
 * ## It closes on pick, and that is a judgement call
 *
 * Slack keeps its picker open for repeat inserts. This closes, because the
 * common case here is one acknowledgement emoji and the panel covers the
 * message list while open. Reopening costs one keystroke; a panel that stays
 * over the conversation costs attention.
 */
export interface EmojiPickerProps {
  onPick: (emoji: string) => void
  disabled?: boolean
}

export function EmojiPicker({ onPick, disabled }: EmojiPickerProps) {
  const [open, setOpen] = React.useState(false)
  const triggerRef = React.useRef<HTMLButtonElement>(null)
  const panelRef = React.useRef<HTMLDivElement>(null)

  const close = React.useCallback(
    (returnFocus: boolean) => {
      setOpen(false)
      if (returnFocus) triggerRef.current?.focus()
    },
    [],
  )

  // Clicking away closes it. Registered only while open, so the app is not
  // carrying a document listener for a panel nobody has opened.
  React.useEffect(() => {
    if (!open) return
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return
      setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  return (
    <div className="relative">
      <Button
        ref={triggerRef}
        type="button"
        variant="ghost"
        size="sm"
        disabled={disabled}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label="Insert emoji"
        onClick={() => setOpen((wasOpen) => !wasOpen)}
      >
        <Smile aria-hidden className="size-4" />
      </Button>

      {open && (
        <div
          ref={panelRef}
          role="dialog"
          aria-label="Emoji"
          className={cn(
            'absolute bottom-full right-0 z-20 mb-2 w-72 rounded-md border border-border',
            'bg-surface p-3 shadow-lg',
          )}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.stopPropagation()
              close(true)
            }
          }}
        >
          {EMOJI_GROUPS.map((group) => (
            <section key={group.name} className="mb-2 last:mb-0">
              <h3 className="mb-1 text-xs font-medium uppercase tracking-wide text-content-muted">
                {group.name}
              </h3>
              <div className="flex flex-wrap gap-1">
                {group.emoji.map((emoji, index) => (
                  <button
                    key={emoji}
                    type="button"
                    // Named rather than left to the reader's own emoji
                    // dictionary, which differs by platform and is sometimes
                    // silent. The group plus the position is the honest
                    // description available without shipping a name table.
                    aria-label={`${group.name} emoji ${index + 1}: ${emoji}`}
                    className={cn(
                      'flex size-8 items-center justify-center rounded text-lg',
                      'hover:bg-subtle focus-visible:outline-none focus-visible:ring-2',
                      'focus-visible:ring-primary',
                    )}
                    onClick={() => {
                      onPick(emoji)
                      // Focus goes back to the composer, not the trigger — the
                      // user is mid-sentence, and landing them on a button
                      // means a Tab before they can keep typing.
                      close(false)
                    }}
                  >
                    <span aria-hidden>{emoji}</span>
                  </button>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  )
}
