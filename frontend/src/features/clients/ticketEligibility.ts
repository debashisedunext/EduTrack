/**
 * B-029 · whether a **new** ticket may be raised against a client — blueprint
 * line 523 and line 948, in one place.
 *
 * ## Why this is a module and not two inline checks
 *
 * There are two gates and they arrived one task apart:
 *
 * - **B-028** — "at least one primary contact before the client can be selected
 *   on a ticket" (line 948).
 * - **B-029** — "deactivating a client … blocks new ticket creation against it"
 *   (line 523).
 *
 * B-028 wrote its half inline on `CreateTicketPage`'s dropdown, which was right
 * while it was the only half. A second one derived beside it is how the rule
 * ends up with two answers — `FieldValidators` said so in its own javadoc and
 * then had it happen anyway (B-028 found the S-33 form and the importer
 * disagreeing about both a client code and an email address), and it is the
 * drift `ProjectRoles` (B-017) and `PasswordComplexity` (B-013) exist to
 * prevent. One function, three call sites, one sentence per refusal.
 *
 * ## The server owes the same rule and cannot pay yet
 *
 * `POST /tickets` has no controller — `feature/tickets/` holds a code
 * generator, an SLA preview, attachments, comments and the two reads, and
 * C-013's own notes say the create service does not exist. So **this file is
 * currently the only thing enforcing either gate anywhere in the system.**
 * That is stated rather than relied on: `createTicket`'s contract description
 * carries both rules as a 400 keyed on `clientId`, written there by B-028 for
 * whoever mounts the operation, and this module is the browser's copy of a rule
 * that has to exist twice — the same shape as `lib/email.ts`, which B-028 wrote
 * against the same corpus as `EmailFormatTest` so neither side could drift
 * alone.
 *
 * ## Blocked is not the same as hidden
 *
 * The third clause of line 523 — "never hides the historical tickets" — is not
 * this module's to enforce and is easy to break by reaching for it. This
 * function answers one question: *may a new ticket be raised against this
 * client, right now.* It is not a visibility predicate. Anything rendering
 * history — the S-15 client filter, the ticket detail's summary panel, a report
 * — must not consult it, and must not send `?isActive=true` either. That
 * conflation is exactly the defect B-029 found on `TicketListPage`.
 */

/**
 * The fields the rule reads. Structural rather than `Client`, so that
 * `ClientDetail` — a separate schema, per B-026 — satisfies it without a cast,
 * and so a test can state a case in four fields instead of thirty.
 *
 * Every one is optional because every one is optional on the wire: `Client`'s
 * fields are additive by CONVENTIONS.md, and `hasPrimaryContact` in particular
 * is deliberately not `required`.
 */
export interface TicketEligibility {
  isActive?: boolean
  hasPrimaryContact?: boolean
}

/**
 * Why a new ticket cannot be raised against this client, or `null` if one can.
 *
 * <h2>Both unknowns block, and that is the safe direction</h2>
 *
 * `undefined` is refused rather than allowed for either field. A response from a
 * server that predates the field, or an object assembled from a partial cache,
 * must not read as permission — and for `hasPrimaryContact` the failure is
 * concrete rather than hypothetical: B-028 rejected deriving the gate from
 * `primaryContact` precisely because that field is `NON_NULL` and therefore
 * *absent* when there is none, so a permissive `?? true` would have offered
 * every client on the form. Blocking on absence keeps the two readings the same.
 *
 * <h2>Inactive is reported before the missing contact</h2>
 *
 * A deactivated client with no primary contact fails both, and the caller
 * renders one sentence. Deactivation is the stronger fact and the actionable
 * one: it was a deliberate administrative act, it is reversible from S-32 by
 * the person reading the message, and telling somebody to go and add a contact
 * to a client the business has closed sends them to fix the wrong thing.
 *
 * <h2>A prospect is eligible</h2>
 *
 * Not an omission. `isActive` on the wire is `status <> 'INACTIVE'` (B-026's
 * `ClientStatus`, and B-028's correction to the matching filter), so a Prospect
 * arrives here as active and a pre-sales ticket is a real thing to raise. The
 * blueprint reserves "blocks new tickets" for deactivation alone.
 */
export function newTicketBlockReason(client: TicketEligibility): string | null {
  if (client.isActive !== true) return 'Inactive — new tickets are blocked'
  if (client.hasPrimaryContact !== true) return 'No primary contact'
  return null
}

/** Convenience for the call sites that only need the boolean. */
export function canRaiseTicket(client: TicketEligibility): boolean {
  return newTicketBlockReason(client) === null
}
