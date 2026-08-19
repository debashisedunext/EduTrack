import type { Stage } from '@/api/generated/model/stage'
import type { StagePatchRequest } from '@/api/generated/model/stagePatchRequest'
import type { StageWriteRequest } from '@/api/generated/model/stageWriteRequest'

/**
 * B-040 · S-13 tab 2's form state, its validation subset, and the reorder model.
 *
 * <p>Everything here is a rule the server also enforces. The client's copy exists
 * so the form can explain itself before the click rather than after — and where
 * the two could disagree, the server's answer is the one that decides, which is
 * why {@link stageFormErrors} deliberately does **not** re-derive
 * `isCodeEditable` or check whether a return target points backwards on a
 * template the screen has only half of.
 */

export interface StageFormState {
  stageCode: string
  displayName: string
  ownerRole: string
  slaHours: string
  isOptional: boolean
  canReturnTo: string[]
  icon: string
}

export const EMPTY_STAGE_FORM: StageFormState = {
  stageCode: '',
  displayName: '',
  ownerRole: '',
  slaHours: '',
  isOptional: false,
  canReturnTo: [],
  icon: '',
}

export function stageToForm(stage: Stage): StageFormState {
  return {
    stageCode: stage.stageCode,
    displayName: stage.displayName,
    ownerRole: stage.ownerRole,
    // An absent SLA and a zero are different things — §4A.1 gives Development as
    // "per SLA policy" and B-004 seeds it null — so the empty string is the one
    // that round-trips to null rather than to 0.
    slaHours: stage.slaHours == null ? '' : String(stage.slaHours),
    isOptional: stage.isOptional,
    canReturnTo: [...stage.canReturnTo],
    icon: stage.icon ?? '',
  }
}

/**
 * The validation subset, matched to the Bean Validation annotations on
 * `StageDtos.StageWrite`.
 *
 * **The pattern is the one worth having in the client**, because it is the rule
 * with the most surprising consequence: `stageCode` is written as plain text into
 * every `ticket_stage_transitions` row, so a code that has to be corrected later
 * cannot be. Catching `go live` here rather than at the server saves nothing
 * technically and saves the Admin from having typed a name they will be told is
 * permanent.
 *
 * **`slaHours` has a floor of 0.01 and not 0.** A zero-hour stage SLA is not "no
 * SLA" — it is a stage that breaches the instant it is entered, and every ticket
 * passing through raises an alert Stream D's scanner cannot suppress. Leave the
 * field empty for no SLA.
 */
export function stageFormErrors(
  form: StageFormState,
  options: { codeEditable?: boolean } = {},
): Record<string, string> {
  const errors: Record<string, string> = {}
  const codeEditable = options.codeEditable ?? true

  const code = form.stageCode.trim()
  if (codeEditable) {
    if (!code) {
      errors.stageCode = 'A stage code is required.'
    } else if (!/^[A-Z][A-Z0-9_]*$/.test(code)) {
      errors.stageCode =
        'Upper case, starting with a letter — DEV, QA, SIGNOFF. No spaces or hyphens.'
    } else if (code.length > 20) {
      errors.stageCode = 'At most 20 characters.'
    }
  }

  const name = form.displayName.trim()
  if (!name) {
    errors.displayName = 'A display name is required.'
  } else if (name.length > 50) {
    errors.displayName = 'At most 50 characters.'
  }

  if (!form.ownerRole.trim()) {
    errors.ownerRole = 'Pick the role that owns this stage.'
  }

  if (form.slaHours.trim()) {
    const hours = Number(form.slaHours)
    if (Number.isNaN(hours)) {
      errors.slaHours = 'Working hours, as a number.'
    } else if (hours < 0.01) {
      errors.slaHours =
        'At least 0.01. Leave it empty for no stage SLA — zero would breach on entry.'
    } else if (hours > 9999.99) {
      errors.slaHours = 'At most 9999.99.'
    }
  }

  if (form.icon.trim().length > 30) {
    errors.icon = 'At most 30 characters.'
  }

  return errors
}

/**
 * The create body.
 *
 * `seq` is absent, and that is the contract's shape rather than an omission — a
 * new stage is appended and moved with the reorder, because a caller-chosen `seq`
 * is a collision with `uq_workflow_stages_seq` the screen cannot anticipate.
 */
export function formToCreate(form: StageFormState): StageWriteRequest {
  return {
    stageCode: form.stageCode.trim().toUpperCase(),
    displayName: form.displayName.trim(),
    ownerRole: form.ownerRole,
    slaHours: form.slaHours.trim() ? Number(form.slaHours) : null,
    isOptional: form.isOptional,
    canReturnTo: form.canReturnTo,
    icon: form.icon.trim() || null,
  }
}

/**
 * The patch body — only what changed.
 *
 * **`canReturnTo` is the field where "only what changed" has teeth.** An empty
 * array clears every return target and `undefined` leaves them alone, so sending
 * `[]` for "untouched" would silently wipe a loop-back somebody authored. The
 * comparison is order-insensitive because the server stores them in the order it
 * validated them, which is not the order the checkboxes render in.
 *
 * **`stageCode` is only ever sent when it actually changed.** Round-tripping it
 * unchanged is harmless on the server — it is treated as a no-op — but sending it
 * on a frozen stage would turn every unrelated edit into a 409 the moment the
 * form's idea of `isCodeEditable` lagged the server's by one ticket.
 */
export function formToPatch(form: StageFormState, original: Stage): StagePatchRequest {
  const patch: StagePatchRequest = {}

  const code = form.stageCode.trim().toUpperCase()
  if (code && code !== original.stageCode) {
    patch.stageCode = code
  }
  if (form.displayName.trim() !== original.displayName) {
    patch.displayName = form.displayName.trim()
  }
  if (form.ownerRole !== original.ownerRole) {
    patch.ownerRole = form.ownerRole
  }

  const slaHours = form.slaHours.trim() ? Number(form.slaHours) : null
  if (slaHours !== (original.slaHours ?? null)) {
    patch.slaHours = slaHours
  }
  if (form.isOptional !== original.isOptional) {
    patch.isOptional = form.isOptional
  }
  if (!sameTargets(form.canReturnTo, original.canReturnTo)) {
    patch.canReturnTo = form.canReturnTo
  }

  const icon = form.icon.trim() || null
  if (icon !== (original.icon ?? null)) {
    patch.icon = icon
  }

  return patch
}

function sameTargets(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  const left = [...a].sort()
  const right = [...b].sort()
  return left.every((code, i) => code === right[i])
}

/**
 * Which stages this one may return to, given where it sits.
 *
 * A return target is a **backward** target — §4A.1's loop-back table is entirely
 * backward moves, and a forward "return" is an ordinary handoff with a reason
 * attached. So the control offers what is above this stage in the ribbon and
 * nothing else, which is also why editing a stage is the only place this list can
 * be computed: it depends on position, not on the stage.
 *
 * A stage being created is appended last, so `position` of `null` means every
 * existing stage qualifies.
 *
 * **Deprecated stages are dropped unless this stage already returns to one** —
 * B-042. The server refuses a target naming a retired stage, because a return
 * target is a move the transition service will honour and an arrow into a retired
 * stage is an entry into one nothing may enter. Offering it would be offering a
 * checkbox that 400s.
 *
 * The exception is not politeness. A stage retired *after* a loop-back was
 * authored leaves that arrow stored, and a picker that silently omitted it would
 * render the checkbox unticked — so the next unrelated edit would send a
 * `canReturnTo` with the target quietly missing and clear an arrow nobody
 * touched. Shown, ticked, and clearable on purpose.
 */
export function returnTargetOptions(
  stages: Stage[],
  position: number | null,
  selected: string[] = [],
): Stage[] {
  const backward = position == null ? stages : stages.filter((s) => s.position < position)
  return backward.filter((stage) => !stage.isDeprecated || selected.includes(stage.stageCode))
}

/**
 * Why this stage cannot be retired yet, or null if it can.
 *
 * The screen's copy of `guardRetirable`, for the reason `forwardReturnPaths` is
 * the screen's copy of the reorder's rule: an Admin should read which stage is in
 * the way before the click rather than a 409 naming a rule after it. The server
 * checks both again — a browser is not a guarantee, and this list is only ever
 * the ribbon the screen happens to be holding.
 *
 * Arrows are returned in the `"QA → DEV"` shape the server puts on its `pairs`
 * property, so both paths render through one component.
 */
export function retireBlockers(
  stage: Stage,
  stages: Stage[],
): { reason: 'last-live'; arrows: [] } | { reason: 'return-target'; arrows: string[] } | null {
  const others = stages.filter((s) => s.id !== stage.id)
  if (!others.some((s) => !s.isDeprecated)) {
    return { reason: 'last-live', arrows: [] }
  }
  const arrows = others
    .filter((s) => !s.isDeprecated)
    .filter((s) => s.canReturnTo.some((t) => t === stage.stageCode))
    .map((s) => `${s.stageCode} \u2192 ${stage.stageCode}`)

  return arrows.length > 0 ? { reason: 'return-target', arrows } : null
}

/**
 * The order after a move, and the only place indices are reasoned about.
 *
 * Returns a new array; out-of-range moves return the input unchanged rather than
 * throwing, because the keyboard controls call this on every arrow press and the
 * ends of the list are the ordinary case rather than an error.
 */
export function moveStage<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) {
    return items
  }
  const next = [...items]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}

/**
 * Which return paths the proposed order would leave pointing forwards.
 *
 * The screen's copy of the rule the reorder is refused by. It exists so the Save
 * button can name the pairs before the request rather than rendering a 409 — the
 * server checks it again, because a browser is not a guarantee.
 *
 * Returned as `"QA → DEV"` strings, matching the `pairs` property the server puts
 * on its problem document, so both paths render through the same component.
 */
export function forwardReturnPaths(ordered: Stage[]): string[] {
  const position = new Map(ordered.map((stage, index) => [stage.stageCode, index]))
  const broken: string[] = []

  ordered.forEach((stage, index) => {
    stage.canReturnTo.forEach((target) => {
      const at = position.get(target)
      if (at != null && at >= index) {
        broken.push(`${stage.stageCode} → ${target}`)
      }
    })
  })

  return broken
}

/**
 * Whether the ribbon has been dragged away from what the server holds.
 *
 * By id and by position, not by content: an edit made in the dialog is saved by
 * its own request, and treating it as an unsaved reorder would offer a Save
 * button that sends the order back unchanged.
 */
export function orderChanged(ordered: Stage[], original: Stage[]): boolean {
  if (ordered.length !== original.length) return true
  return ordered.some((stage, index) => stage.id !== original[index].id)
}
