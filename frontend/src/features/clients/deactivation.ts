/**
 * B-029 · what counts as deactivating a client, and what has to be shown first.
 *
 * Separate from `DeactivationWarningDialog.tsx` because the dialog is a
 * component and this is a rule — the two have different reasons to change, and
 * `react-refresh/only-export-components` is right that a file mixing them
 * reloads badly. The split is the same one `clientForm.ts` already makes beside
 * `ClientFormPage.tsx`.
 */

/** A client about to be deactivated that still holds open tickets. */
export interface DeactivationCandidate {
  id: number
  name: string
  clientCode: string
  openTicketCount: number
}

/**
 * The client S-33 is about to save, as far as this rule cares.
 *
 * Structural rather than `ClientDetail` so a test can state a case in five
 * fields, and so the grid — which holds `Client`, a different schema — satisfies
 * it without a cast.
 */
export interface DeactivationSubject {
  id?: number
  name?: string
  clientCode?: string
  status?: string
  openTicketCount?: number
}

/**
 * Whether saving this form is a deactivation worth warning about — blueprint
 * line 523's first clause, on S-33's Status select.
 *
 * Four ways to be silent, and each is a decision:
 *
 * - **No loaded client** — a create. `POST /clients` cannot produce a client
 *   with open tickets, so there is nothing to warn about, and the toast already
 *   says what a new client is missing.
 * - **The next status is not `INACTIVE`** — Active ⇄ Prospect is a commercial
 *   reclassification, not a deactivation. Line 523 reserves "blocks new
 *   tickets" for `INACTIVE`, and `isActive` is `<> INACTIVE` on both sides of
 *   the wire (B-026's `ClientStatus`).
 * - **It was already `INACTIVE`** — the admin is editing an address on a client
 *   that is already closed. Warning about a state that is not changing is how a
 *   confirmation becomes something people click through without reading.
 * - **No open tickets** — the same call S-32's bulk bar makes. Deactivating a
 *   client with nothing outstanding costs nothing, and a dialog on every one of
 *   them is a dialog nobody reads by the third.
 */
export function deactivationWarning(
  client: DeactivationSubject | null,
  nextStatus: string | undefined,
): DeactivationCandidate | null {
  if (client == null || client.id == null) return null
  if (nextStatus !== 'INACTIVE') return null
  if (client.status === 'INACTIVE') return null

  const openTicketCount = client.openTicketCount ?? 0
  if (openTicketCount <= 0) return null

  return {
    id: client.id,
    name: client.name ?? '',
    clientCode: client.clientCode ?? '',
    openTicketCount,
  }
}
