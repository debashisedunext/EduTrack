/**
 * What the New Project form refuses to send, and why.
 *
 * <h2>A pure function rather than a block inside `onSubmit`</h2>
 *
 * <p>Same shape as `moduleServicePicker` and `projectRow` next door: the rule
 * is the part worth testing, and testing it through a page that fetches
 * clients, products, people and a service catalogue would be four mocked reads
 * to assert one sentence.
 *
 * <h2>The messages are the contract's, in the words a person can act on</h2>
 *
 * <p>Every field here is `@NotNull` on `ObProjectCreateRequest`, so the server
 * refuses the same requests this does. What it cannot do is say *which box* —
 * a 400 names the field, not the reason somebody should care — so these say it
 * before the request is made, and `fieldErrorsFrom` maps the server's answer
 * back onto the same keys for anything that gets past.
 */
export interface NewProjectDraft {
  name: string
  clientId: number | null
  productId: number | null
  startDate: string
  salesPersonId: number | null
  implementorUserId: number | null
  implementorManagerUserId: number | null
  /** Active module services of the chosen product — empty until one is chosen. */
  serviceCount: number
  /** Those still checked. */
  selectedCount: number
  /** The catalogue read is still in flight, so an empty list means nothing yet. */
  servicesPending: boolean
}

/** Field key → the sentence shown under it. Empty when the draft may be sent. */
export function validateNewProject(draft: NewProjectDraft): Record<string, string> {
  const found: Record<string, string> = {}

  /*
    Blank is allowed once a client is chosen — the field says "leave blank to
    name it after the client", and the page sends that client's name. With no
    client there is nothing to fall back to, so the sentence names both ways
    out rather than pointing at a box the person may not have meant to fill.
  */
  if (!draft.name.trim() && draft.clientId == null) {
    found.name = 'Give the project a name, or choose the client to name it after.'
  }
  if (draft.clientId == null) found.clientId = 'Choose a client.'
  if (draft.productId == null) found.productId = 'Choose the product bought.'
  if (!draft.startDate) found.startDate = 'Give the project a start date.'

  if (draft.salesPersonId == null) {
    found.salesPersonId = 'Choose the sales person who owns this project.'
  }

  /*
    The implementor is required, and not only as a courtesy to the server.

    It is what an ownerless task falls to: a module service that pins nobody
    instantiates its tasks onto this person, and the journey read resolves the
    older rows onto them too. A project created without one produces journeys
    whose unpinned tasks belong to nobody — which surfaces a fortnight later on
    the Manager's unassigned list rather than here, where somebody is already
    choosing who runs the project.
  */
  if (draft.implementorUserId == null) {
    found.implementorUserId = 'Choose the implementor. Tasks with no responsible fall to them.'
  }

  /*
    The implementor manager is required for the sales person's reason, not the
    implementor's.

    Nothing falls back to them — an ownerless task goes to the implementor, and
    the manager is never instantiated onto anything. They are asked for because
    every engagement has somebody accountable above the person running it, and
    the moment to capture that is while somebody is already choosing the other
    two. Left to be filled in later, it is filled in when a project needs
    escalating and nobody can say to whom.
  */
  if (draft.implementorManagerUserId == null) {
    found.implementorManagerUserId = 'Choose the implementor manager this project escalates to.'
  }

  /*
    Both service rules hang off the product, and neither fires while the
    catalogue read is in flight: an empty list then means "not loaded", not
    "this product publishes nothing", and refusing on it would reject a form
    nobody has had the chance to fill.
  */
  if (draft.productId != null && draft.serviceCount === 0 && !draft.servicesPending) {
    found.productId =
      'This product publishes no active module service, so there is nothing to board the client through.'
  }
  if (draft.productId != null && draft.serviceCount > 0 && draft.selectedCount === 0) {
    found.moduleServiceIds =
      'Keep at least one module service — a project with none has nothing to run.'
  }

  return found
}
