import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'

import { realtime } from './client'
import { useRealtimeAll } from './useRealtime'
import { canAddressStage } from './destinations'

let subscribedTo: string[] = []
let unsubscribed: string[] = []

beforeEach(() => {
  subscribedTo = []
  unsubscribed = []
  vi.spyOn(realtime, 'subscribe').mockImplementation((destination) => {
    subscribedTo.push(destination)
    return () => unsubscribed.push(destination)
  })
})

describe('useRealtimeAll — D-059', () => {
  it('opens one subscription per destination', () => {
    renderHook(() => useRealtimeAll(['/topic/stage.QA.1', '/topic/stage.QA.2'], () => {}))

    expect(subscribedTo).toEqual(['/topic/stage.QA.1', '/topic/stage.QA.2'])
  })

  it('does not resubscribe when the caller passes a fresh array of the same rooms', () => {
    // The reason this hook exists rather than a loop: a component re-renders
    // constantly, and tearing down and re-opening every subscription on each
    // render loses every event that lands in the gap — `useRealtime`'s handler
    // ref prevents exactly this one dimension over.
    const { rerender } = renderHook(({ rooms }) => useRealtimeAll(rooms, () => {}), {
      initialProps: { rooms: ['/topic/stage.QA.1'] },
    })
    rerender({ rooms: ['/topic/stage.QA.1'] })
    rerender({ rooms: ['/topic/stage.QA.1'] })

    expect(subscribedTo).toEqual(['/topic/stage.QA.1'])
    expect(unsubscribed).toEqual([])
  })

  it('swaps rooms when the set actually changes', () => {
    const { rerender } = renderHook(({ rooms }) => useRealtimeAll(rooms, () => {}), {
      initialProps: { rooms: ['/topic/stage.QA.1'] },
    })
    rerender({ rooms: ['/topic/stage.DEPLOY.1'] })

    expect(unsubscribed).toEqual(['/topic/stage.QA.1'])
    expect(subscribedTo).toEqual(['/topic/stage.QA.1', '/topic/stage.DEPLOY.1'])
  })

  it('collapses duplicates', () => {
    renderHook(() => useRealtimeAll(['/topic/stage.QA.1', '/topic/stage.QA.1'], () => {}))

    expect(subscribedTo).toEqual(['/topic/stage.QA.1'])
  })

  it('subscribes to nothing when handed nothing', () => {
    renderHook(() => useRealtimeAll([], () => {}))

    expect(subscribedTo).toEqual([])
  })

  it('closes everything on unmount', () => {
    const { unmount } = renderHook(() =>
      useRealtimeAll(['/topic/stage.QA.1', '/topic/stage.QA.2'], () => {}),
    )
    unmount()

    expect(unsubscribed).toEqual(['/topic/stage.QA.1', '/topic/stage.QA.2'])
  })
})

describe('canAddressStage', () => {
  it('agrees with what stageTopic will accept', () => {
    expect(canAddressStage('QA')).toBe(true)
    expect(canAddressStage('READY_FOR_QA')).toBe(true)
    expect(canAddressStage('UAT-2')).toBe(true)
    // The two an Admin can actually type into a workflow template.
    expect(canAddressStage('QA.2')).toBe(false)
    expect(canAddressStage('Ready for QA')).toBe(false)
    expect(canAddressStage('')).toBe(false)
  })
})
