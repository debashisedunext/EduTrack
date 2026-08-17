import { describe, expect, it } from 'vitest'

import type { Comment } from '@/api/generated/model/comment'

import { canDeleteComment, canEditComment, editMinutesLeft } from './commentPermissions'

/**
 * C-033 · §4B.5's immutability rules, as the client applies them.
 *
 * These are pure functions for the reason `mention-token.ts` is: a rule buried
 * in a component is a rule that can only be tested through jsdom, and the two
 * that matter most here — the window boundary and the author check — are exactly
 * the ones a rendering test would assert weakly ("the button is not there") for
 * whatever reason happened to be true.
 *
 * The server is the authority in every case; these decide only what is *drawn*.
 * `CommentServiceTest` pins the same rules on the side that enforces them, and
 * the cases below deliberately mirror it — a client that offers a button the
 * server refuses is worse than one that offers none.
 */

const POSTED = '2026-08-16T10:15:30Z'
const DEADLINE = '2026-08-16T10:20:30Z'

const ME = 12
const SOMEBODY_ELSE = 500

function comment(over: Partial<Comment> = {}): Comment {
  return {
    id: 99,
    body: '<p>Root cause is the retry timeout.</p>',
    author: { id: ME, displayName: 'Ravi Kumar' },
    isClientVisible: false,
    isEdited: false,
    isDeleted: false,
    editableUntil: DEADLINE,
    createdAt: POSTED,
    ...over,
  } as Comment
}

describe('C-033 · canEditComment', () => {
  /**
   * D-14 · the shipped default. The server sends no `editableUntil` at all when
   * no window is configured, and every one of these would have returned false
   * before the change — the Edit button would have vanished from every comment
   * in the product while every request it declined to make would have worked.
   */
  describe('D-14 · with no window configured, which is the default', () => {
    const noDeadline = () => comment({ editableUntil: null })

    it('lets the author edit thirty minutes later', () => {
      expect(canEditComment(noDeadline(), { id: ME }, new Date('2026-08-16T10:45:00Z'))).toBe(true)
    })

    it('lets the author edit a year later', () => {
      expect(canEditComment(noDeadline(), { id: ME }, new Date('2027-08-16T10:45:00Z'))).toBe(true)
    })

    it('still refuses somebody else’s comment', () => {
      expect(canEditComment(noDeadline(), { id: SOMEBODY_ELSE, role: 'ADMIN' }, new Date())).toBe(false)
    })

    it('still refuses a tombstone', () => {
      const removed = comment({ isDeleted: true, editableUntil: null })
      expect(canEditComment(removed, { id: ME }, new Date())).toBe(false)
    })

    it('treats an unparseable deadline as no deadline, not as an expired one', () => {
      const malformed = comment({ editableUntil: 'not a date' })
      expect(canEditComment(malformed, { id: ME }, new Date())).toBe(true)
    })
  })

  /**
   * The window is still implemented and restorable with one property, so the
   * client half stays covered too — otherwise "just set edit-window" is advice
   * nobody has checked on this side.
   */
  it('lets the author edit inside a configured window', () => {
    expect(canEditComment(comment(), { id: ME }, new Date('2026-08-16T10:19:00Z'))).toBe(true)
  })

  it('closes exactly one millisecond after a configured deadline', () => {
    expect(canEditComment(comment(), { id: ME }, new Date('2026-08-16T10:20:30.001Z'))).toBe(false)
  })

  it('is inclusive on the deadline itself, matching the server', () => {
    expect(canEditComment(comment(), { id: ME }, new Date(DEADLINE))).toBe(true)
  })

  /**
   * §4B.5's own sentence, and the asymmetry with deletion. An Admin who could
   * edit would leave the thread attributing the new wording to Ravi.
   */
  it.each(['ADMIN', 'PM', 'DEVELOPER'] as const)(
    'refuses %s on somebody else’s comment, inside the window',
    (role) => {
      const viewer = { id: SOMEBODY_ELSE, role }
      expect(canEditComment(comment(), viewer, new Date('2026-08-16T10:16:00Z'))).toBe(false)
    },
  )

  it('refuses a tombstone', () => {
    const removed = comment({ isDeleted: true, editableUntil: null })
    expect(canEditComment(removed, { id: ME }, new Date('2026-08-16T10:16:00Z'))).toBe(false)
  })

  it('refuses while the viewer is still loading', () => {
    expect(canEditComment(comment(), { id: null }, new Date(POSTED))).toBe(false)
  })
})

describe('C-033 · canDeleteComment', () => {
  it('lets the author remove their own', () => {
    expect(canDeleteComment(comment(), { id: ME })).toBe(true)
  })

  it.each(['ADMIN', 'PM'] as const)('lets %s remove somebody else’s', (role) => {
    expect(canDeleteComment(comment(), { id: SOMEBODY_ELSE, role })).toBe(true)
  })

  it.each(['DEVELOPER', 'QA', 'DEPLOYMENT', 'SUPPORT'] as const)(
    'refuses %s on somebody else’s',
    (role) => {
      expect(canDeleteComment(comment(), { id: SOMEBODY_ELSE, role })).toBe(false)
    },
  )

  /**
   * The difference from editing that the whole task turns on: deletion has no
   * window. §4B.5 attaches its five minutes to editing and says of deletion only
   * that it leaves a tombstone, so an author may still remove a comment from
   * last year — a `canDeleteComment` that consulted `editableUntil` would pass
   * every other test in this file and fail here.
   */
  it('has no window — the author may still remove a comment long past the edit deadline', () => {
    const old = comment({ editableUntil: null, createdAt: '2025-01-01T09:00:00Z' })
    expect(canDeleteComment(old, { id: ME })).toBe(true)
  })

  it('refuses a tombstone, so a second × cannot be offered', () => {
    expect(canDeleteComment(comment({ isDeleted: true }), { id: ME })).toBe(false)
  })
})

describe('C-033 · editMinutesLeft', () => {
  it('D-14 · is null when there is no deadline, so no countdown is drawn', () => {
    expect(editMinutesLeft(comment({ editableUntil: null }), new Date(POSTED))).toBeNull()
  })

  it('rounds up, so the last 60 seconds read as "1" rather than "0"', () => {
    expect(editMinutesLeft(comment(), new Date('2026-08-16T10:20:00Z'))).toBe(1)
  })

  it('reads 5 at the moment of posting', () => {
    expect(editMinutesLeft(comment(), new Date(POSTED))).toBe(5)
  })

  it('is null once the window has closed, rather than zero or negative', () => {
    expect(editMinutesLeft(comment(), new Date('2026-08-16T10:21:00Z'))).toBeNull()
  })

  it('is null when there is no deadline', () => {
    expect(editMinutesLeft(comment({ editableUntil: null }), new Date(POSTED))).toBeNull()
  })
})
