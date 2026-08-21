import { describe, expect, it } from 'vitest'
import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import {
  EMPTY_TEMPLATE_FORM,
  describePair,
  describeRung,
  draftsToRequest,
  duplicatePairKeys,
  formToCreate,
  mappingsChanged,
  mappingsToDrafts,
  newMappingDraft,
  templateFormErrors,
  type MappingDraft,
} from './templateForm'

/**
 * B-041 · S-13 tab 3's form logic.
 */

const draft = (over: Partial<MappingDraft>): MappingDraft => ({
  key: 'k1', serverId: null, projectId: null, taskTypeId: null, ...over,
})

describe('template validation', () => {
  it('requires a name', () => {
    expect(templateFormErrors(EMPTY_TEMPLATE_FORM).name).toMatch(/needs a name/)
    expect(templateFormErrors({ ...EMPTY_TEMPLATE_FORM, name: '   ' }).name).toBeDefined()
  })

  /**
   * 80 and not 150. The contract declared 150 until this task while
   * `workflow_templates.name` has always been `VARCHAR(80)` — a form built to the
   * declared figure would have accepted a name MySQL then truncated, with nothing
   * anywhere reporting it.
   */
  it('caps the name at the column width, not at the old declared width', () => {
    expect(templateFormErrors({ ...EMPTY_TEMPLATE_FORM, name: 'x'.repeat(80) }).name)
      .toBeUndefined()
    expect(templateFormErrors({ ...EMPTY_TEMPLATE_FORM, name: 'x'.repeat(81) }).name)
      .toMatch(/80 characters/)
  })

  it('caps the description at 255', () => {
    const form = { ...EMPTY_TEMPLATE_FORM, name: 'Flow', description: 'x'.repeat(256) }
    expect(templateFormErrors(form).description).toMatch(/255/)
  })

  /**
   * The refusals that matter on this screen are all facts about other rows — the
   * name already taken, the pair claimed elsewhere, the default flag. None is
   * checkable here, and a form that guessed at them would refuse things the
   * server would have accepted.
   */
  it('does not try to guess the refusals that belong to the server', () => {
    const form = { ...EMPTY_TEMPLATE_FORM, name: 'Standard Dev Flow' }
    expect(templateFormErrors(form)).toEqual({})
  })

  it('sends an empty description as null rather than as an empty string', () => {
    expect(formToCreate({ ...EMPTY_TEMPLATE_FORM, name: ' Flow ', description: '  ' }))
      .toEqual({ name: 'Flow', description: null, copyStagesFromTemplateId: null })
  })
})

describe('duplicate pairs', () => {
  /**
   * The one client-side check that earns its place: both offending rules are on
   * screen, so the check is complete here, and letting it reach the server means
   * a round trip returning an error about two rows the user can already see.
   */
  it('flags both ends of a clash, not only the later one', () => {
    const rows = [
      draft({ key: 'a', projectId: 1, taskTypeId: 2 }),
      draft({ key: 'b', projectId: 1, taskTypeId: 2 }),
      draft({ key: 'c', projectId: 1, taskTypeId: 3 }),
    ]

    expect(duplicatePairKeys(rows)).toEqual(new Set(['a', 'b']))
  })

  /** Two wildcards are the same rule, which the generated columns enforce in MySQL. */
  it('treats two "any/any" rules as a clash', () => {
    const rows = [draft({ key: 'a' }), draft({ key: 'b' })]

    expect(duplicatePairKeys(rows)).toEqual(new Set(['a', 'b']))
  })

  it('does not confuse "any project" with a project', () => {
    const rows = [
      draft({ key: 'a', projectId: null, taskTypeId: 2 }),
      draft({ key: 'b', projectId: 1, taskTypeId: 2 }),
    ]

    expect(duplicatePairKeys(rows).size).toBe(0)
  })
})

describe('dirty tracking', () => {
  const mapping = (over: Partial<TemplateMapping>): TemplateMapping => ({
    id: 1, projectId: null, projectCode: null, projectName: null,
    taskTypeId: null, taskTypeCode: null, taskTypeName: null, specificity: 0, ...over,
  })

  /**
   * Order-insensitive on purpose. The server sorts by specificity, so a saved set
   * comes back in a different order than it was sent — and a naive comparison
   * would report every set as dirty the instant it was saved, leaving Save
   * permanently enabled.
   */
  it('is not dirty when the same rules come back in a different order', () => {
    const loaded = [
      mapping({ id: 1, taskTypeId: 2, specificity: 1 }),
      mapping({ id: 2, projectId: 5, taskTypeId: 3, specificity: 2 }),
    ]
    const reversed = [...mappingsToDrafts(loaded)].reverse()

    expect(mappingsChanged(reversed, loaded)).toBe(false)
  })

  it('is dirty when a rule is added, removed or repointed', () => {
    const loaded = [mapping({ id: 1, taskTypeId: 2, specificity: 1 })]
    const drafts = mappingsToDrafts(loaded)

    expect(mappingsChanged([...drafts, newMappingDraft()], loaded)).toBe(true)
    expect(mappingsChanged([], loaded)).toBe(true)
    expect(mappingsChanged([{ ...drafts[0], taskTypeId: 9 }], loaded)).toBe(true)
  })

  it('drops the local key on the way to the request', () => {
    expect(draftsToRequest([draft({ projectId: 1, taskTypeId: 2 })]))
      .toEqual([{ projectId: 1, taskTypeId: 2 }])
  })
})

describe('the words the screen renders', () => {
  it('renders a null as "any" rather than as blank', () => {
    expect(describePair(null, null)).toBe('Any project · Any task type')
    expect(describePair('CRM', null)).toBe('CRM · Any task type')
    expect(describePair(null, 'Production Bug')).toBe('Any project · Production Bug')
  })

  /**
   * The distinction the whole panel exists to draw: a rule somebody wrote versus
   * a fallback nobody chose. `DEFAULT` on its own says neither to anyone who has
   * not read §4A.9.
   */
  it('says out loud when nothing matched', () => {
    expect(describeRung('DEFAULT')).toMatch(/no rule matched/)
    expect(describeRung('NONE')).toMatch(/no template is the default/)
    expect(describeRung('EXACT')).toMatch(/this project and this task type/)
    expect(describeRung('PROJECT')).toMatch(/any task type/)
    expect(describeRung('TASK_TYPE')).toMatch(/any project/)
  })
})
