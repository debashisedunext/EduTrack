import { describe, expect, it } from 'vitest'

import type { ObMyTask } from '@/api/generated/model'

import { myTaskBucket, myTaskSlices } from './obMyTaskSlices'

/**
 * The four states the implementor's donut draws, and the one property the
 * centre figure depends on: that they partition the queue.
 */
function task(over: Partial<ObMyTask> & { taskId: number }): ObMyTask {
  return {
    taskName: `Task ${over.taskId}`,
    status: 'PENDING',
    isOverdue: false,
    projectId: 1,
    projectName: 'Horizon — ERP',
    obClientId: 1,
    obClientName: 'Horizon',
    ...over,
  } as ObMyTask
}

describe('myTaskBucket', () => {
  it('counts a late task as Delayed whatever it is doing', () => {
    // The overlap that would otherwise double-count: In progress *and* late.
    expect(myTaskBucket(task({ taskId: 1, status: 'IN_PROGRESS', isOverdue: true }))).toBe('delayed')
    expect(myTaskBucket(task({ taskId: 2, status: 'BLOCKED', isOverdue: true }))).toBe('delayed')
  })

  it('reads blocked and waiting-on-client as At risk', () => {
    expect(myTaskBucket(task({ taskId: 3, status: 'BLOCKED' }))).toBe('at-risk')
    expect(myTaskBucket(task({ taskId: 4, status: 'WAITING_ON_CLIENT' }))).toBe('at-risk')
  })

  it('reads a running task as In progress', () => {
    expect(myTaskBucket(task({ taskId: 5, status: 'IN_PROGRESS' }))).toBe('in-progress')
  })

  it('reads anything else still open as Pending', () => {
    expect(myTaskBucket(task({ taskId: 6, status: 'PENDING' }))).toBe('pending')
    expect(myTaskBucket(task({ taskId: 7, status: 'PENDING_REVIEW' }))).toBe('pending')
  })
})

describe('myTaskSlices', () => {
  const queue = [
    task({ taskId: 1, status: 'IN_PROGRESS', isOverdue: true }),
    task({ taskId: 2, status: 'IN_PROGRESS' }),
    task({ taskId: 3, status: 'WAITING_ON_CLIENT' }),
    task({ taskId: 4, status: 'PENDING' }),
    task({ taskId: 5, status: 'PENDING' }),
  ]

  /*
    The property the centre figure rests on. Four counts that overlapped
    would total more tasks than the person holds.
  */
  it('partitions the queue, so the slices sum to it', () => {
    const slices = myTaskSlices(queue)
    expect(slices.reduce((sum, s) => sum + s.rows.length, 0)).toBe(queue.length)
  })

  it('orders the legend worst first', () => {
    expect(myTaskSlices(queue).map((s) => s.label)).toEqual([
      'Delayed',
      'At risk',
      'In progress',
      'Pending',
    ])
  })

  /* A zero-width arc is a slice nobody can hover, click or see. */
  it('drops an empty state rather than drawing it at zero', () => {
    const slices = myTaskSlices([task({ taskId: 1, status: 'PENDING' })])
    expect(slices.map((s) => s.label)).toEqual(['Pending'])
  })

  it('counts tasks, never the checklist rows inside them', () => {
    const slices = myTaskSlices([
      task({ taskId: 1, status: 'PENDING', rowsOut: 9, rowsReturned: 3, rowsApproved: 2 }),
    ])
    expect(slices[0].rows).toHaveLength(1)
  })
})
