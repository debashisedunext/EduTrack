import { describe, expect, it } from 'vitest'
import {
  emptyTemplateForm,
  humaniseEvent,
  templateFormErrors,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  unknownMergeTags,
  type TemplateFormValues,
} from './templateForm'

/**
 * B-022 · S-15's rules, without rendering anything.
 *
 * The two mappers are where a quiet mistake shows up as a save that silently did
 * nothing, and the merge-tag scanner has to agree with `MergeTag.unknownIn`
 * exactly — a validator that accepted `{{ ticket_id }}` while the renderer
 * refused it, or the reverse, would put braces in a client-facing mail.
 */

const KNOWN = ['ticket_id', 'assignee', 'stage', 'client', 'planned_close', 'actor']

const values = (over: Partial<TemplateFormValues> = {}): TemplateFormValues => ({
  ...emptyTemplateForm,
  eventCode: 'COMMENT_ADDED',
  channel: 'EMAIL',
  recipients: ['ASSIGNEE'],
  subjectTemplate: 'New comment from {{actor}}',
  bodyTemplate: '<p>{{ticket_id}}</p>',
  ...over,
})

describe('unknownMergeTags', () => {
  it('catches the camelCase near-miss that would print literal braces', () => {
    expect(unknownMergeTags('<p>{{ticketId}} is late</p>', KNOWN)).toEqual(['ticketId'])
  })

  it('tolerates whitespace inside the braces, like the server does', () => {
    expect(unknownMergeTags('{{ ticket_id }} and {{  assignee  }}', KNOWN)).toEqual([])
  })

  it('reports one mistake once, in first-appearance order', () => {
    expect(unknownMergeTags('{{b}} {{a}} {{b}} {{a}} {{c}}', KNOWN)).toEqual(['b', 'a', 'c'])
  })

  // CSS and inline styles in an HTML body are full of single braces.
  it('ignores single braces', () => {
    expect(unknownMergeTags('<style>p { margin: 0 }</style>', KNOWN)).toEqual([])
  })
})

describe('humaniseEvent', () => {
  it('turns a code into a sentence', () => {
    expect(humaniseEvent('TICKET_ASSIGNED')).toBe('Ticket assigned')
    expect(humaniseEvent('SLA_80_PERCENT_ELAPSED')).toBe('Sla 80 percent elapsed')
  })
})

describe('templateFormErrors', () => {
  it('accepts a well-formed email template', () => {
    expect(templateFormErrors(values(), KNOWN)).toEqual({})
  })

  it('requires a subject on email and not on in-app', () => {
    expect(templateFormErrors(values({ subjectTemplate: '' }), KNOWN))
      .toHaveProperty('subjectTemplate')
    expect(
      templateFormErrors(values({ channel: 'IN_APP', subjectTemplate: '' }), KNOWN),
    ).not.toHaveProperty('subjectTemplate')
  })

  it('requires at least one recipient', () => {
    expect(templateFormErrors(values({ recipients: [] }), KNOWN))
      .toHaveProperty('recipients')
  })

  it('requires a body', () => {
    expect(templateFormErrors(values({ bodyTemplate: '   ' }), KNOWN))
      .toHaveProperty('bodyTemplate')
  })

  it('refuses a misspelled merge tag in the body', () => {
    expect(templateFormErrors(values({ bodyTemplate: '<p>{{ticketId}}</p>' }), KNOWN).bodyTemplate)
      .toContain('{{ticketId}}')
  })

  it('refuses one in the subject too', () => {
    expect(
      templateFormErrors(values({ subjectTemplate: 'About {{ticket}}' }), KNOWN).bodyTemplate,
    ).toContain('{{ticket}}')
  })

  /**
   * A validator that is wrong in the strict direction is worse than one that is
   * briefly quiet — refusing every tag in the body because the vocabulary read
   * has not resolved yet would block a save that is perfectly correct.
   */
  it('reports no unknown tags while the catalogue is still loading', () => {
    expect(templateFormErrors(values({ bodyTemplate: '{{anything}}' }), []))
      .not.toHaveProperty('bodyTemplate')
  })

  /**
   * The lock is not enforced here on purpose. `isMandatory` comes off the row,
   * and the page renders the toggle as a locked statement rather than letting a
   * click earn a 409 — a control whose only outcome is a refusal should not be
   * operable in the first place.
   */
  it('does not refuse isActive: false — the page locks the control instead', () => {
    expect(templateFormErrors(values({ isActive: false }), KNOWN)).toEqual({})
  })
})

describe('the wire mappers', () => {
  it('sends an empty subject as null, not as a blank string', () => {
    // Null means "this channel carries no subject", which is the stored state of
    // every in-app template. A blank string is a subject line that is empty,
    // which is a different and useless thing.
    expect(toWriteRequest(values({ channel: 'IN_APP', subjectTemplate: '  ' })).subjectTemplate)
      .toBeNull()
  })

  it('sends the whole form on a patch, event and channel included', () => {
    // Resending the stored pair is a deliberate no-op server-side; sending it is
    // what makes a *changed* pair refusable, and the pair is the row's identity.
    const patch = toPatchRequest(values())
    expect(patch.eventCode).toBe('COMMENT_ADDED')
    expect(patch.channel).toBe('EMAIL')
  })

  it('round-trips a stored template through the form', () => {
    const stored = {
      id: 3,
      eventCode: 'HANDOFF_RECEIVED',
      channel: 'EMAIL' as const,
      recipients: ['STAGE_OWNER' as const],
      subjectTemplate: 'Handed to you at {{stage}}',
      bodyTemplate: '<p>{{actor}}</p>',
      isActive: true,
    }

    expect(toPatchRequest(toFormValues(stored))).toMatchObject({
      eventCode: 'HANDOFF_RECEIVED',
      channel: 'EMAIL',
      recipients: ['STAGE_OWNER'],
      subjectTemplate: 'Handed to you at {{stage}}',
      bodyTemplate: '<p>{{actor}}</p>',
      isActive: true,
    })
  })

  it('reads a null subject back as an empty input rather than the string "null"', () => {
    expect(toFormValues({ subjectTemplate: null }).subjectTemplate).toBe('')
  })
})
