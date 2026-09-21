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

import type { ObPortalLoginIssued } from '@/api/generated/model/obPortalLoginIssued'

/**
 * The credentials the add dialog just issued, shown once.
 *
 * <h2>A modal, on `TemporaryPasswordDialog`'s ruling</h2>
 *
 * B-011 made this call for the resource master's generated password and the
 * reasoning transfers unchanged: "a toast that disappears after five seconds,
 * taking with it the only copy of a credential that cannot be looked up again,
 * is the wrong control for the job — the admin's next step is to get this
 * string to a person, and that takes longer than five seconds". The password
 * here is stored as an Argon2id hash and no request recovers it; the only way
 * back is a reset from the client's account panel.
 *
 * <h2>Not the same component, and why</h2>
 *
 * That one shows one string. This shows two, and the second is **optional** —
 * a deployment that has switched `edutrack.portal.temporary-password` off is
 * back to mailing a one-time link and has no password to show. Bending the
 * other component to carry a username and a sometimes-absent password would
 * have made both screens harder to read than having two.
 *
 * ## What the password is, and why showing it is acceptable
 *
 * It is temporary. The client signs in with it once and the portal then
 * refuses them everything but the change-password form until they have chosen
 * their own — `PortalPasswordChangeGate`, server-side. So it is a credential
 * on a staff screen for exactly as long as it takes to be used once, which is
 * the bargain that makes showing it reasonable rather than reckless. The copy
 * below says so, because an operator who thinks this is the client's permanent
 * password will file it somewhere.
 *
 * <h2>Closing needs a deliberate click</h2>
 *
 * No overlay-click and no Escape, for B-011's reason: both dismiss without
 * intent, and the cost of dismissing this one by accident is a password reset
 * for a client who has not logged in yet.
 */
export interface ObPortalLoginIssuedDialogProps {
  /** Null when there is nothing to show. */
  login: ObPortalLoginIssued | null
  clientName: string
  onClose: () => void
}

export function ObPortalLoginIssuedDialog({
  login,
  clientName,
  onClose,
}: ObPortalLoginIssuedDialogProps) {
  return (
    <Modal open={login != null} onOpenChange={(open) => !open && onClose()}>
      <ModalContent
        onEscapeKeyDown={(e) => e.preventDefault()}
        onPointerDownOutside={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <ModalHeader>
          <ModalTitle className="flex items-center gap-2">
            <KeyRound className="h-5 w-5 text-content-muted" aria-hidden />
            {clientName} added, with a portal login
          </ModalTitle>
          <ModalDescription>
            {login?.password
              ? 'Send both to the client now — the password is shown once and is not recoverable. They will be asked to choose their own the first time they sign in. A reset is on the client’s account panel if it is lost.'
              : 'Their contact has been mailed a one-time link to set a password. The username is on the client’s account panel if it is needed again.'}
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-2 px-6 pb-2">
          <CredentialRow label="Username" value={login?.username ?? ''} testId="portal-username" />
          {login?.password ? (
            <CredentialRow
              label="Temporary password"
              value={login.password}
              testId="portal-password"
            />
          ) : null}
        </div>

        <ModalFooter>
          <Button type="button" onClick={onClose}>
            {login?.password ? 'I have saved it' : 'Done'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

function CredentialRow({
  label,
  value,
  testId,
}: {
  label: string
  value: string
  testId: string
}) {
  const [copied, setCopied] = React.useState(false)

  React.useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 2000)
    return () => clearTimeout(timer)
  }, [copied])

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
    } catch {
      // Clipboard access is refused without a secure context. The value is on
      // screen and selectable, so this is a missing convenience rather than a
      // broken flow — and a failed copy must not close the dialog.
      setCopied(false)
    }
  }

  return (
    <div className="flex items-center gap-2 rounded-control border border-border bg-subtle p-3">
      <span className="w-20 shrink-0 text-caption text-content-muted">{label}</span>
      {/* `font-mono` and `select-all` so a click takes the whole string: a
          credential read out of a proportional font is where O-versus-0
          confusion starts. */}
      <code className="flex-1 select-all break-all font-mono text-sm text-content" data-testid={testId}>
        {value}
      </code>
      <Button
        type="button"
        variant="secondary"
        size="sm"
        onClick={copy}
        aria-label={copied ? `${label} copied` : `Copy ${label.toLowerCase()}`}
      >
        {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
        {copied ? 'Copied' : 'Copy'}
      </Button>
    </div>
  )
}
