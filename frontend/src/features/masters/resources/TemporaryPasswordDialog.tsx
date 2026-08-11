import * as React from 'react'
import { Check, Copy, KeyRound } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

/**
 * B-011 · the one time the generated password is readable.
 *
 * <h2>Why this is a modal and not a toast</h2>
 *
 * The password is stored as an Argon2id hash and no request can recover it. A
 * toast that disappears after five seconds, taking with it the only copy of a
 * credential that cannot be looked up again, is the wrong control for the job —
 * the admin's next step is to get this string to a person, and that takes
 * longer than five seconds.
 *
 * <h2>Why closing needs a deliberate click</h2>
 *
 * No overlay-click and no Escape. Both dismiss without intent, and the cost of
 * dismissing this one by accident is a password reset for somebody who has not
 * logged in yet. The button says what is about to be lost.
 */
export interface TemporaryPasswordDialogProps {
  /** Null when there is nothing to show. */
  password: string | null
  displayName: string
  onClose: () => void
}

export function TemporaryPasswordDialog({
  password,
  displayName,
  onClose,
}: TemporaryPasswordDialogProps) {
  const [copied, setCopied] = React.useState(false)

  React.useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 2000)
    return () => clearTimeout(timer)
  }, [copied])

  async function copy() {
    if (!password) return
    try {
      await navigator.clipboard.writeText(password)
      setCopied(true)
    } catch {
      // Clipboard access is refused in some browsers without a secure context.
      // The password is on screen and selectable, so this is a missing
      // convenience rather than a broken flow — and a failed copy must not
      // close the dialog and lose the string.
      setCopied(false)
    }
  }

  return (
    <Modal open={password != null} onOpenChange={(open) => !open && onClose()}>
      <ModalContent
        onEscapeKeyDown={(e) => e.preventDefault()}
        onPointerDownOutside={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <ModalHeader>
          <ModalTitle className="flex items-center gap-2">
            <KeyRound className="h-5 w-5 text-content-muted" aria-hidden />
            {displayName} has been created
          </ModalTitle>
          <ModalDescription>
            This temporary password is shown once and cannot be looked up again. Send it to them now — they
            will be asked to change it the first time they log in.
          </ModalDescription>
        </ModalHeader>

        <div className="flex items-center gap-2 rounded-control border border-border bg-subtle p-3">
          {/* `font-mono` and `select-all` so a click selects the whole string:
              a temporary password read out of a proportional font is where the
              O-versus-0 confusion the generator already avoids comes back. */}
          <code
            className="flex-1 select-all break-all font-mono text-sm text-content"
            data-testid="temporary-password"
          >
            {password}
          </code>
          <Button type="button" variant="secondary" size="sm" onClick={copy}>
            {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>

        <ModalFooter>
          <Button type="button" onClick={onClose}>
            I have saved it
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
