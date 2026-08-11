/**
 * `DEVELOPMENT` → `Development`. A display formatter, not a workflow-template
 * lookup — resolving a ticket's *real* stage display name means resolving
 * which workflow template it belongs to, which `list/README.md` documents as
 * a heavier problem the ticket list also declined to take on for its dropped
 * ribbon column. Good enough for a one-line "stamped to X" caption; not a
 * stand-in for the ribbon.
 */
export function titleCase(stageCode: string): string {
  return stageCode
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
