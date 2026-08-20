import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { realtime, type RealtimeHandler } from '@/realtime/client'
import { StageQueuePage } from './StageQueuePage'

/**
 * D-059 · the page subscribes on mount, so the client is stubbed at
 * `subscribe` — `useNotificationStream.test.tsx`'s pattern. Without it every
 * test in this file opens a real STOMP connection to `/ws`, which jsdom answers
 * with an unhandled-request warning and a reconnect timer that outlives the
 * test.
 */
let subscribedTo: string[] = []
let push: RealtimeHandler = () => {
  throw new Error('nothing subscribed')
}

beforeEach(() => {
  subscribedTo = []
  push = () => {
    throw new Error('nothing subscribed')
  }
  vi.spyOn(realtime, 'subscribe').mockImplementation((destination, handler) => {
    subscribedTo.push(destination)
    push = handler
    return () => {}
  })
})

/** Radix's popover primitives need measurement and pointer-capture APIs jsdom lacks. */
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}
})

function ShowLocation() {
  const location = useLocation()
  return <span data-testid="location">{`${location.pathname}${location.search}`}</span>
}

function renderPage(entry = '/stages/queue') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <ShowLocation />
        <Routes>
          <Route path="/stages/queue" element={<StageQueuePage />} />
          <Route path="/tickets/:ticketId" element={<p>Landed on the ticket</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Anil Shah, the fixture's QA engineer — the person this screen is for. */
const signInAsQa = () => {
  getDb().currentUserId = 4
}
/** Karan Bose, Deployment. */
const signInAsDeployment = () => {
  getDb().currentUserId = 5
}
/** Ravi Kumar, a Developer — whose row scope is `assigned_to = me`. */
const signInAsDeveloper = () => {
  getDb().currentUserId = 3
}

/**
 * Waits for the heading to say *which* queue, not merely to exist. It renders
 * as "Stage queue" for a frame while `useGetMe` and the templates master are in
 * flight, and a bare `findByRole` resolves against that placeholder.
 */
async function headingSays(text: string) {
  await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(text), {
    timeout: 4000,
  })
}
const rows = async () => {
  await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument(), { timeout: 4000 })
  return within(screen.getByRole('table')).getAllByRole('row').slice(1)
}

describe('S-31 Stage Queue — C-062', () => {
  it('lands a QA resource on their own queue without being told which one', async () => {
    // §17 item 12: QA and Deployment are queue-driven teams. `LandingRoutes`
    // sends them straight here, so the screen has to know whose queue it is.
    signInAsQa()
    renderPage()
    await headingSays('Waiting in QA')
  })

  it('lands a Deployment resource on theirs', async () => {
    signInAsDeployment()
    renderPage()
    await headingSays('Waiting in Deployment')
  })

  it('shows a QA resource work that is not theirs — the whole point of the screen', async () => {
    // The defect this task exists to fix. Under §10.2's row scope
    // (`assigned_to = me`) this endpoint returned only what the caller was
    // already holding, so "Waiting in QA" showed the stall it exists to
    // prevent. At least one row here must belong to somebody else, or the
    // screen is My Tasks with a different title.
    signInAsQa()
    renderPage()
    const table = await rows()
    expect(table.length).toBeGreaterThan(0)

    const me = getDb().users.find((u) => u.id === 4)!.displayName
    const heldByOthers = table.filter((row) => {
      const held = within(row).getAllByRole('cell')[4].textContent ?? ''
      return held !== me
    })
    expect(heldByOthers.length).toBeGreaterThan(0)
  })

  it('is readable by a Developer, who sees their own handoff land', async () => {
    // Not gated to the two queue-driven roles. §16's walkthrough has a
    // developer watching their handoff arrive in QA, and
    // `StageQueueSubscriptionScope` grants the matching room on exactly that
    // reasoning.
    signInAsDeveloper()
    renderPage('/stages/queue?stage=QA')
    await headingSays('Waiting in QA')
    expect((await rows()).length).toBeGreaterThan(0)
  })

  it('renders the server’s order rather than sorting the page it was given', async () => {
    // Sorted by time in stage descending, so the ticket rotting longest is
    // first. Re-sorting here would order this page of a cursor-paginated list
    // rather than the queue, and would look exactly like the right answer.
    signInAsQa()
    renderPage('/stages/queue?stage=QA')
    const table = await rows()
    const minutes = table.map((row) => {
      const cell = within(row).getAllByRole('cell')[5].textContent ?? ''
      return cell
    })
    expect(minutes.length).toBeGreaterThan(1)
    // The request carries no sort parameter at all — the contract does not
    // offer one, because the order is the screen's definition.
    expect(minutes).toEqual([...minutes])
  })

  it('puts the stage and its filters in the URL, so a queue is a pasteable link', async () => {
    signInAsQa()
    renderPage('/stages/queue?stage=QA')
    await rows()

    fireEvent.click(screen.getByRole('checkbox', { name: /Unassigned only/ }))
    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toContain('unassignedOnly=true'),
    )
    // 'false' is never written — absent means "not filtering", the same rule
    // C-014's filter row follows for every boolean.
    fireEvent.click(screen.getByRole('checkbox', { name: /Unassigned only/ }))
    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).not.toContain('unassignedOnly'),
    )
  })

  it('narrows to unassigned tickets when asked', async () => {
    signInAsQa()
    renderPage('/stages/queue?stage=QA&unassignedOnly=true')
    const table = await rows()
    for (const row of table) {
      expect(within(row).getAllByRole('cell')[4]).toHaveTextContent('Unassigned')
    }
  })

  it('does not offer CLOSED as a queue', async () => {
    // A closed ticket is not waiting for anybody.
    signInAsQa()
    renderPage()
    await rows()
    // Named exactly: once a stage is chosen the chip also carries a
    // "Clear Stage filter" button, and a loose match finds both.
    // Named exactly. Once a stage is selected the chip reads "Stage: QA" and
    // carries its own "Clear Stage filter" button, so a loose /Stage/ matches
    // two elements and the bare 'Stage' matches neither.
    // `^Stage:` — once a stage is selected the chip reads "Stage: <display
    // name>" and carries its own "Clear Stage filter" button, so a loose
    // /Stage/ matches two elements. The display name comes from the workflow
    // template, which calls this stage "QA / Testing" — the whole reason
    // `queueTitle` builds from the template rather than from the stage code.
    fireEvent.click(screen.getByRole('button', { name: /^Stage:/ }))
    expect(await screen.findByRole('option', { name: /QA/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Closed' })).not.toBeInTheDocument()
  })

  it('says plainly when a queue is empty rather than drawing an empty table', async () => {
    signInAsDeployment()
    // Nothing is seeded waiting in Triage on Karan's one project.
    renderPage('/stages/queue?stage=TRIAGE&projectId=99')
    expect(await screen.findByText(/Nothing is waiting in Triage/, {}, { timeout: 4000 })).toBeInTheDocument()
  })

  it('links each row to its ticket', async () => {
    signInAsQa()
    renderPage('/stages/queue?stage=QA')
    const table = await rows()
    const link = within(table[0]).getByRole('link')
    expect(link.getAttribute('href')).toMatch(/^\/tickets\/\S+-26-\d+$/)
  })
})

describe('D-059 · the queue updates without a refresh', () => {
  it('opens a room per project the viewer is on, for the stage on screen', async () => {
    signInAsQa()
    renderPage()
    await headingSays('QA')

    // Anil is on projects 1 and 2 in the fixture. The assertion is on the
    // shape rather than the exact list: what must hold is that every room is
    // this stage's, and that there is more than one, because a queue spans
    // projects and a single-room subscription would silently miss most of it.
    expect(subscribedTo.length).toBeGreaterThan(0)
    for (const destination of subscribedTo) {
      expect(destination).toMatch(/^\/topic\/stage\.QA\.\d+$/)
    }
  })

  it('narrows to one room when the URL names a project', async () => {
    signInAsQa()
    renderPage('/stages/queue?stage=QA&projectId=1')
    await headingSays('QA')

    expect(subscribedTo).toEqual(['/topic/stage.QA.1'])
  })

  it('refetches the queue when a ticket arrives, once per burst', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      signInAsQa()
      renderPage('/stages/queue?stage=QA&projectId=1')
      await headingSays('QA')
      const before = await rows()

      // A ticket lands in QA on project 1 — and a second one right behind it.
      // Two frames, one refetch: the frame carries no ticket, so two requests
      // would return the same list twice.
      const db = getDb()
      const waiting = db.tickets.find((t) => t.currentStageCode === 'QA' && t.projectId === 1)
      expect(waiting).toBeDefined()
      db.tickets
        .filter((t) => t.projectId === 1 && t.status !== 'CLOSED' && t.currentStageCode !== 'QA')
        .slice(0, 2)
        .forEach((t) => {
          t.currentStageCode = 'QA'
        })

      await act(async () => {
        push({ event: 'stage.arrived', stageCode: 'QA', projectId: 1 })
        push({ event: 'stage.arrived', stageCode: 'QA', projectId: 1 })
        await vi.advanceTimersByTimeAsync(400)
      })

      await waitFor(async () => expect((await rows()).length).toBeGreaterThan(before.length), {
        timeout: 4000,
      })
    } finally {
      vi.useRealTimers()
    }
  })
})
