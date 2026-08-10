import { useNotificationStream } from './useNotificationStream'

/**
 * D-043/D-044 · mounts the notification stream for the lifetime of the shell.
 *
 * Renders nothing. It exists so the one line this feature needs inside
 * `AppShell` — Stream C's file — is an import and a tag, and every decision
 * about toasts, snoozing and the badge stays in Stream D's directory.
 *
 * Its own file rather than living beside the hook, because a module that
 * exports both a component and a hook breaks React Fast Refresh: editing the
 * hook would remount the shell instead of hot-swapping it.
 */
export function NotificationStream(): null {
  useNotificationStream()
  return null
}
