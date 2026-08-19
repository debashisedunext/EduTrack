import { beforeEach, describe, expect, it } from 'vitest'

import { getDb } from '../db'

/**
 * C-064 · `POST`/`DELETE /tickets/{ticketId}/links`, and `linkedTickets` on
 * `GET /tickets/{ticketId}/full`.
 *
 * These handlers are this stream's own addition to `frontend/src/mocks/`
 * (Stream D's directory — flagged in `handlers/tickets.ts`), so unlike
 * `TicketLevelControl.test.tsx` there is no owner boundary keeping the tests
 * out of this directory. What matters most here is not any one request but
 * the *pair*: a relationship created from one ticket has to read correctly
 * from the other, which is the whole point of canonicalising the row rather
 * than storing it twice.
 */

let pathId: string
let targetId: string

beforeEach(() => {
  getDb().currentUserId = 1 // Admin — scope is not what these tests are about
  const [a, b] = getDb().tickets
  pathId = a.ticketId
  targetId = b.ticketId
})

async function post(ticketId: string, body: { targetTicketId: string; linkType: string }) {
  return fetch(`/api/v1/tickets/${ticketId}/links`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function del(ticketId: string, linkId: number) {
  return fetch(`/api/v1/tickets/${ticketId}/links/${linkId}`, { method: 'DELETE' })
}

/** Just the three fields these tests care about — the payload carries more (createdAt, createdBy, title, level, status). */
async function linkedTicketsOf(ticketId: string) {
  const res = await fetch(`/api/v1/tickets/${ticketId}/full`)
  const body = (await res.json()) as {
    data: { linkedTickets: { id: number; linkType: string; ticket: { ticketId: string } }[] }
  }
  return body.data.linkedTickets.map((l) => ({ id: l.id, linkType: l.linkType, ticket: { ticketId: l.ticket.ticketId } }))
}

describe('ticket links — C-064', () => {
  it('BLOCKS from the path ticket reads as BLOCKED_BY from the target', async () => {
    const created = await post(pathId, { targetTicketId: targetId, linkType: 'BLOCKS' })
    expect(created.status).toBe(201)

    const fromPath = await linkedTicketsOf(pathId)
    expect(fromPath).toEqual([{ id: expect.any(Number), linkType: 'BLOCKS', ticket: { ticketId: targetId } }])

    const fromTarget = await linkedTicketsOf(targetId)
    expect(fromTarget).toEqual([{ id: expect.any(Number), linkType: 'BLOCKED_BY', ticket: { ticketId: pathId } }])
  })

  /**
   * The canonicalisation itself: "A is blocked by B" and "B blocks A" say the
   * identical thing and must collide on the identical row, not accumulate as
   * two independent facts nobody can tell apart on screen.
   */
  it('BLOCKED_BY submitted from the path ticket collides with BLOCKS submitted from the other side', async () => {
    const first = await post(pathId, { targetTicketId: targetId, linkType: 'BLOCKED_BY' })
    expect(first.status).toBe(201)

    const second = await post(targetId, { targetTicketId: pathId, linkType: 'BLOCKS' })
    expect(second.status).toBe(409)

    expect(await linkedTicketsOf(pathId)).toHaveLength(1)
  })

  it('RELATES_TO is symmetric regardless of which ticket it is submitted from', async () => {
    await post(pathId, { targetTicketId: targetId, linkType: 'RELATES_TO' })

    const fromEitherSide = await post(targetId, { targetTicketId: pathId, linkType: 'RELATES_TO' })
    expect(fromEitherSide.status).toBe(409)
  })

  it('a self-link is refused with 400', async () => {
    const res = await post(pathId, { targetTicketId: pathId, linkType: 'RELATES_TO' })
    expect(res.status).toBe(400)
  })

  it('DUPLICATED_BY is never accepted as a submission', async () => {
    const res = await post(pathId, { targetTicketId: targetId, linkType: 'DUPLICATED_BY' })
    expect(res.status).toBe(400)
  })

  it('delete removes the row from both sides, and a repeat delete is 404', async () => {
    const created = await post(pathId, { targetTicketId: targetId, linkType: 'RELATES_TO' })
    const { data } = (await created.json()) as { data: { id: number } }

    const removed = await del(pathId, data.id)
    expect(removed.status).toBe(204)
    expect(await linkedTicketsOf(pathId)).toEqual([])
    expect(await linkedTicketsOf(targetId)).toEqual([])

    expect((await del(pathId, data.id)).status).toBe(404)
  })

  /** A caller must not learn "link 41 is real, just not yours" by probing ids across tickets. */
  it('deleting a link that does not touch this ticket is 404, same as one that never existed', async () => {
    const created = await post(pathId, { targetTicketId: targetId, linkType: 'RELATES_TO' })
    const { data } = (await created.json()) as { data: { id: number } }

    const thirdTicketId = getDb().tickets[2].ticketId
    const res = await del(thirdTicketId, data.id)
    expect(res.status).toBe(404)
    // Untouched — the wrong-ticket delete must not have silently succeeded.
    expect(await linkedTicketsOf(pathId)).toHaveLength(1)
  })
})
