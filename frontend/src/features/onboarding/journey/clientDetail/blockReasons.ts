/**
 * C-111 · the vocabulary behind `ObBlockJourneyStepRequest.reasonCode`.
 *
 * ## Why a list exists at all
 *
 * The contract types this field as `string, maxLength: 40` — anything goes.
 * Its stated purpose is to power **"where is it stuck"**, which is an aggregate
 * across every blocked step in the module, and free text cannot be aggregated:
 * twenty owners describing one obstacle produce twenty categories and a report
 * nobody can read. So `BlockStepDialog` offers these as a select and puts the
 * specifics in `note`, where prose belongs.
 *
 * ## 🔴 This list is not ratified anywhere
 *
 * There is no `ob_block_reasons` master, no enum in `contracts/openapi.yaml`,
 * and nothing in the module plan naming a set. These five are **this screen's
 * own**, and the choice is deliberate rather than hidden: a short imperfect
 * list can be migrated later, and free text cannot be migrated at all.
 *
 * The vocabulary belongs with the onboarding masters (Stream B) whenever
 * somebody owns it. At that point these move there and this file is deleted —
 * the codes are already kebab-case ids rather than sentences precisely so the
 * rows can be lifted across without touching the data already written.
 *
 * ## Every reason here is an *internal* one, on purpose
 *
 * Plan §5.7 splits the two ways work stops, and only one of them stops the
 * clock: an internal `BLOCKED` step keeps running and the delay is charged to
 * us, while `WAITING_ON_CLIENT` pauses TAT and attributes the wait to the
 * client. A "client has not replied" code here would invite owners to record a
 * client wait as our delay — the reverse of the mistake, and just as wrong.
 * The dialog says so in as many words.
 */
export interface BlockReason {
  code: string
  label: string
  hint: string
}

export const BLOCK_REASONS: readonly BlockReason[] = [
  {
    code: 'dependency-not-ready',
    label: 'Waiting on another service',
    hint: 'Another service or team has not delivered what this one needs.',
  },
  {
    code: 'resource-unavailable',
    label: 'Resource unavailable',
    hint: 'The person or skill this needs is not available.',
  },
  {
    code: 'technical-issue',
    label: 'Technical issue',
    hint: 'An environment, tooling or system fault is stopping the work.',
  },
  {
    code: 'awaiting-internal-approval',
    label: 'Awaiting internal approval',
    hint: 'A sign-off inside our organisation, not the client’s.',
  },
  {
    code: 'third-party',
    label: 'Third party',
    hint: 'A vendor or external system we do not control.',
  },
]
