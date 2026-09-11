/**
 * The product code, derived from the name.
 *
 * The API requires a code on every product — `^[A-Z0-9_-]+$`, at most 32
 * characters, unique case-insensitively — because `ob_client_applications`
 * and `ob_journey_templates` point at the row by id while mails and reports
 * name it by code. The master does not ask for one: an admin creating
 * "Biometric Attendance" wants to type that and nothing else, so the code is
 * made here from the name and never shown.
 *
 * Deterministic, so the same name always yields the same code and a second
 * "EduTrack ERP" collides with the first — which is the server's duplicate
 * check doing its job, surfaced on the name field.
 */
export function codeFromName(name: string): string {
  const slug = name
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 32)
    .replace(/_+$/g, '')
  if (slug) return slug
  // A name with no Latin letters or digits in it — a product named in another
  // script, say — leaves nothing to slug. The code is never displayed, so a
  // time-based token keeps it unique without pretending to be readable.
  return `P${Date.now().toString(36).toUpperCase()}`
}
