import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import type { TemplateMappingEntry } from '@/api/generated/model/templateMappingEntry'
import type { WorkflowTemplateWriteRequest } from '@/api/generated/model/workflowTemplateWriteRequest'

/**
 * B-041 · S-13 tab 3's form logic, kept out of the component so it can be tested
 * without rendering one — the split `stageForm.ts` and `statusForm.ts` both made.
 */

export interface TemplateFormState {
  name: string
  description: string
  copyStagesFromTemplateId: number | null
}

export const EMPTY_TEMPLATE_FORM: TemplateFormState = {
  name: '',
  description: '',
  copyStagesFromTemplateId: null,
}

/**
 * Field-keyed errors, matching the shape the server's problem document uses.
 *
 * **Deliberately thin.** The interesting refusals on this screen — the name
 * already taken, the pair already routed elsewhere, the template that is the
 * default — are all facts about *other rows*, and a client cannot know any of
 * them. Restating them here would produce a form that guesses, and the way that
 * fails is by refusing something the server would have accepted. What is checked
 * here is only what the field itself can be wrong about.
 */
export function templateFormErrors(form: TemplateFormState): Record<string, string> {
  const errors: Record<string, string> = {}

  const name = form.name.trim()
  if (name.length === 0) {
    errors.name = 'A template needs a name.'
  } else if (name.length > 80) {
    // 80 and not the 150 the contract declared until B-041. workflow_templates.name
    // is VARCHAR(80), so the old figure would have been accepted by the form and
    // truncated by MySQL.
    errors.name = 'Keep the name to 80 characters.'
  }

  if (form.description.trim().length > 255) {
    errors.description = 'Keep the description to 255 characters.'
  }

  return errors
}

export function formToCreate(form: TemplateFormState): WorkflowTemplateWriteRequest {
  return {
    name: form.name.trim(),
    description: form.description.trim() || null,
    copyStagesFromTemplateId: form.copyStagesFromTemplateId,
  }
}

/**
 * The rules as the screen holds them while they are being edited.
 *
 * A local id rather than the server's, because a row being added has no server
 * id yet and React needs a stable key before it has one. The server id is
 * carried alongside where there is one, and is used for nothing but telling an
 * existing rule from a new one in the tests.
 */
export interface MappingDraft {
  key: string
  serverId: number | null
  projectId: number | null
  taskTypeId: number | null
}

let nextDraftKey = 0

export function newMappingDraft(): MappingDraft {
  nextDraftKey += 1
  return { key: `draft-${nextDraftKey}`, serverId: null, projectId: null, taskTypeId: null }
}

export function mappingsToDrafts(mappings: TemplateMapping[]): MappingDraft[] {
  return mappings.map((m) => ({
    key: `server-${m.id}`,
    serverId: m.id ?? null,
    projectId: m.projectId ?? null,
    taskTypeId: m.taskTypeId ?? null,
  }))
}

export function draftsToRequest(drafts: MappingDraft[]): TemplateMappingEntry[] {
  return drafts.map((d) => ({ projectId: d.projectId, taskTypeId: d.taskTypeId }))
}

/** `null` means **any**, and the word is what the screen renders. */
export function describePair(projectLabel: string | null, taskTypeLabel: string | null): string {
  return `${projectLabel ?? 'Any project'} · ${taskTypeLabel ?? 'Any task type'}`
}

/**
 * Two rules naming the same pair, which the server refuses with a 409 from a
 * unique key.
 *
 * **Checked on the client too, and this is the one duplication that earns its
 * place.** It is not a fact about another row — both offending rules are on
 * screen, in the list the Admin is looking at — so the check is complete here,
 * and letting it reach the server means a round trip that returns an error about
 * two rows the user can already see.
 *
 * Returns the keys of every draft in a colliding group, so the screen can mark
 * both ends rather than only the second. Marking only the later one reads as
 * though the earlier one is fine and the new one is wrong, when they are the
 * same mistake.
 */
export function duplicatePairKeys(drafts: MappingDraft[]): Set<string> {
  const seen = new Map<string, string[]>()
  drafts.forEach((d) => {
    const k = `${d.projectId ?? '*'}:${d.taskTypeId ?? '*'}`
    seen.set(k, [...(seen.get(k) ?? []), d.key])
  })
  const clashing = new Set<string>()
  seen.forEach((keys) => {
    if (keys.length > 1) keys.forEach((k) => clashing.add(k))
  })
  return clashing
}

/**
 * Whether the rule set differs from what was loaded.
 *
 * Order-insensitive, because the server sorts by specificity and the screen
 * renders that order — so a saved set comes back in a different order than it
 * was sent, and a naive comparison would report every set as dirty the moment it
 * was saved.
 */
export function mappingsChanged(drafts: MappingDraft[], original: TemplateMapping[]): boolean {
  const key = (p: number | null, t: number | null) => `${p ?? '*'}:${t ?? '*'}`
  const a = [...new Set(drafts.map((d) => key(d.projectId, d.taskTypeId)))].sort()
  const b = [...new Set(original.map((m) => key(m.projectId ?? null, m.taskTypeId ?? null)))].sort()
  return a.length !== b.length || a.some((k, i) => k !== b[i])
}

/**
 * The sentence for the rung a resolution came back on.
 *
 * Prose rather than the raw enum, because the distinction the screen exists to
 * draw is between *a rule somebody wrote* and *a fallback nobody chose* — and
 * `DEFAULT` on its own does not say that to anybody who has not read §4A.9.
 */
export function describeRung(rung: string | undefined): string {
  switch (rung) {
    case 'EXACT':
      return 'a rule naming this project and this task type'
    case 'PROJECT':
      return 'a rule naming this project, for any task type'
    case 'TASK_TYPE':
      return 'a rule naming this task type, on any project'
    case 'ANY':
      return 'a catch-all rule covering every project and task type'
    case 'DEFAULT':
      return 'no rule matched — this is the default template'
    case 'NONE':
      return 'no rule matched and no template is the default'
    default:
      return 'unknown'
  }
}
