import { describe, expect, it } from 'vitest'

import type { ObStepCommunication } from '@/api/generated/model/obStepCommunication'
import {
  COMMUNICATION_CHANNELS,
  RECORDABLE_CHANNELS,
  channelLook,
  entryByline,
  visibilityLabel,
} from './communicationEntry'

/**
 * C-112 · the vocabulary both timelines read, tested without a DOM — the same
 * split `segmentState.test.ts` draws, and for the same reason: the part that
 * has to be right is which entry type gets which word.
 */
function entry(over: Partial<ObStepCommunication> = {}): ObStepCommunication {
  return {
    id: 1,
    stepId: 42,
    channel: 'CALL',
    occurredAt: '2026-09-05T09:00:00Z',
    summary: 'Spoke to the SPOC',
    isClientVisible: false,
    authorType: 'STAFF',
    authorName: 'Ravi Kumar',
    recordedBy: { id: 3, displayName: 'Ravi Kumar' },
    ...over,
  }
}

describe('channelLook', () => {
  it('names and ices every value the wire can carry', () => {
    for (const channel of COMMUNICATION_CHANNELS) {
      const look = channelLook(channel)
      expect(look.label).not.toBe('')
      expect(look.Icon).toBeTruthy()
    }
  })

  /*
   * `entry_type` is a VARCHAR with no CHECK constraint behind it, so a newer
   * deploy can write a ninth value into a table this client reads. An
   * unrecognised one must cost the icon's specificity and nothing else.
   */
  it('renders a value it has never heard of rather than throwing or blanking', () => {
    const look = channelLook('SMS')
    expect(look.label).toBe('SMS')
    expect(look.variant).toBe('neutral')
  })

  it('gives an escalation its own treatment, because it is not another note', () => {
    expect(channelLook('ESCALATION').variant).toBe('danger')
    expect(channelLook('CALL').variant).not.toBe('danger')
  })
})

describe('the recordable subset', () => {
  /*
   * `createObStepCommunication` rejects the other three. A select that offered
   * SYSTEM would be a control whose only outcome is a 400.
   */
  it('offers only what a person may record', () => {
    expect(RECORDABLE_CHANNELS).toEqual(['CALL', 'EMAIL', 'MEETING', 'WHATSAPP', 'OTHER'])
    expect(RECORDABLE_CHANNELS as readonly string[]).not.toContain('COMMENT')
    expect(RECORDABLE_CHANNELS as readonly string[]).not.toContain('ESCALATION')
    expect(RECORDABLE_CHANNELS as readonly string[]).not.toContain('SYSTEM')
  })
})

describe('entryByline', () => {
  it('names the channel and whoever wrote it', () => {
    expect(entryByline(entry())).toBe('Call · Ravi Kumar')
  })

  /*
   * A staff name and a contact name look identical, and which side wrote an
   * entry is the thing a reader scanning the stitched view most needs.
   */
  it('says which side wrote it when the name alone would not', () => {
    expect(
      entryByline(entry({ channel: 'COMMENT', authorType: 'CLIENT', authorName: 'Sanjay Bose', recordedBy: null })),
    ).toBe('Portal comment · Sanjay Bose (client)')
  })

  it('renders a system entry from the one field, with no staff ref to read', () => {
    expect(
      entryByline(entry({ channel: 'SYSTEM', authorType: 'SYSTEM', authorName: 'System', recordedBy: null })),
    ).toBe('System · System')
  })
})

describe('visibilityLabel', () => {
  /*
   * §11 and CP-03. Both states are named in words rather than one of them
   * being the absence of a badge — an internal note repeated on a call because
   * somebody read the wrong chip is the one mistake here that cannot be taken
   * back.
   */
  it('names both states, so neither is merely the absence of the other', () => {
    expect(visibilityLabel(true)).toBe('Client can see this')
    expect(visibilityLabel(false)).toBe('Internal only')
  })
})
