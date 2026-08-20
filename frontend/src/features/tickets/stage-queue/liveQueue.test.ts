import { describe, expect, it } from 'vitest'

import { MAX_STAGE_ROOMS, stageRooms } from './liveQueue'

/** The common case, overridden per test. */
const base = {
  stageCode: 'QA',
  selectedProjectId: null,
  myProjectIds: [1, 2, 3],
  allProjectIds: [1, 2, 3, 4, 5],
  isAdmin: false,
}

describe('D-059 · which stage rooms a queue listens to', () => {
  it('is one room per project the viewer is on', () => {
    expect(stageRooms(base)).toEqual([
      '/topic/stage.QA.1',
      '/topic/stage.QA.2',
      '/topic/stage.QA.3',
    ])
  })

  it('narrows to exactly the selected project', () => {
    expect(stageRooms({ ...base, selectedProjectId: 2 })).toEqual(['/topic/stage.QA.2'])
  })

  it('honours a selected project the viewer is not a member of', () => {
    // The subscription scope decides that, and a refusal on a pasted link is
    // the correct outcome. Pre-empting it here would hide a permissions
    // problem behind a queue that simply never updates.
    expect(stageRooms({ ...base, selectedProjectId: 9 })).toEqual(['/topic/stage.QA.9'])
  })

  it('gives an Admin every active project rather than their own memberships', () => {
    // §10.2 gives Admin the organisation, and StageQueueSubscriptionScope
    // grants them any room — so membership rows, often none, are the wrong
    // list. An Admin on no projects would otherwise get no live updates at
    // all on a screen they can see in full.
    const rooms = stageRooms({ ...base, isAdmin: true, myProjectIds: [] })
    expect(rooms).toHaveLength(5)
    expect(rooms).toContain('/topic/stage.QA.5')
  })

  it('subscribes to nothing when a non-Admin is on no projects', () => {
    // Not "everything as a fallback": every one of those SUBSCRIBEs would be
    // refused, and a refusal is an ERROR frame that closes the session — which
    // would take chat and the notification stream down with the queue.
    expect(stageRooms({ ...base, myProjectIds: [] })).toEqual([])
  })

  it('subscribes to nothing until a stage is known', () => {
    expect(stageRooms({ ...base, stageCode: null })).toEqual([])
    expect(stageRooms({ ...base, stageCode: undefined })).toEqual([])
  })

  it('skips a stage code it cannot address instead of throwing', () => {
    // Stage codes come from a workflow template an Admin edits, so a dot is
    // reachable from the product. `stageTopic` throws on one, which is right
    // for a literal and a white screen on a render path. The queue then
    // behaves exactly as it did before this task.
    expect(stageRooms({ ...base, stageCode: 'QA.2' })).toEqual([])
    expect(stageRooms({ ...base, stageCode: 'Ready for QA' })).toEqual([])
  })

  it('drops ids that are not addressable, rather than building a broken room', () => {
    expect(stageRooms({ ...base, myProjectIds: [0, -1, 1.5, 4] })).toEqual(['/topic/stage.QA.4'])
  })

  it('de-duplicates', () => {
    expect(stageRooms({ ...base, myProjectIds: [2, 2, 2] })).toEqual(['/topic/stage.QA.2'])
  })

  it('caps how many rooms one screen opens', () => {
    const many = Array.from({ length: 200 }, (_, i) => i + 1)
    expect(stageRooms({ ...base, myProjectIds: many })).toHaveLength(MAX_STAGE_ROOMS)
  })
})
