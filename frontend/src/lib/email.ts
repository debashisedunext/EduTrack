/**
 * B-028 · the browser's copy of `EmailFormat` — blueprint line 948's "valid
 * emails", stated once on this side of the wire.
 *
 * There were three answers to this question before B-028, and the two on the
 * frontend were the loose ones:
 *
 * - `EmailFormat` (backend, `domain/validation/`) — a dotted TLD is required.
 * - Jakarta `@Email` on the client and contact write shapes — accepted
 *   `accounts@acme`. Removed; the service applies `EmailFormat` instead.
 * - zod's `.email()` in `clientForm.ts` and `contactForm.ts`, and a bare
 *   `includes('@')` in the MSW mock — both accepted it too.
 *
 * A client-side rule looser than the server's is the worse direction of the
 * two: the form accepts the address, the request goes out, and the failure
 * comes back as a server error on a field the user was told was fine — which
 * is the shape of every "it worked in dev" bug. It is also the address B-035's
 * import would later reject on a row describing a client S-33 created.
 *
 * **This is a duplicate of the Java rule and there is no way for it not to be**
 * — the two run on different machines. The pattern is character-for-character
 * the one in `EmailFormat`, and `email.test.ts` asserts the same corpus the
 * backend's `EmailFormatTest` does, so a change to one that is not made to the
 * other fails on the side that was not changed.
 */

/**
 * Local part: anything but whitespace, a second `@`, a comma or a semicolon —
 * the last two because a pasted distribution list is the most common thing that
 * arrives in one of these boxes. Domain: at least two dot-separated labels, so
 * `acme` fails and `acme.example` passes.
 */
const EMAIL = /^[^\s@,;]+@[^\s@,;.]+(\.[^\s@,;.]+)+$/

/**
 * Trims first, because every caller stores the trimmed value — an address
 * copied out of a spreadsheet cell must not be refused where the same address
 * typed by hand is accepted.
 *
 * Blank and `null` are **not valid**: "not supplied" is the caller's to
 * interpret, and the fields that require an address say so themselves. Keeping
 * that split is what lets the contact editor show "An email address is
 * required" rather than "not well-formed" for an empty box.
 */
export function isWellFormedEmail(candidate: string | null | undefined): boolean {
  if (candidate == null) return false
  const trimmed = candidate.trim()
  return trimmed.length > 0 && EMAIL.test(trimmed)
}

/** The one wording, so the client form, the contact editor and the mock agree. */
export const EMAIL_MESSAGE = 'That is not a well-formed email address.'
