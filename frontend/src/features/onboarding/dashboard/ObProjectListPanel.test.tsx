import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import type { ObProjectBoardRow } from '@/api/generated/model'

import { ObProjectListPanel } from './ObProjectListPanel'

/**
 * The order a figure's projects are listed in, which is the whole of what this
 * component decides: the rows arrive as props, already counted by the board.
 */

function row(over: Partial<ObProjectBoardRow> & { id: number; name: string }): ObProjectBoardRow {
  return {
    client: { id: 100 + over.id, name: `Client ${over.id}`, clientCode: null, city: null },
    product: { id: 1, code: 'ERP', name: 'ERP Suite' },
    startDate: '2026-09-01',
    gateStatus: 'OPEN',
    bucket: 'ON_TIME',
    tasksTotal: 4,
    tasksDone: 1,
    openEscalations: 0,
    ...over,
  } as ObProjectBoardRow
}

function renderPanel(rows: ObProjectBoardRow[]) {
  render(
    <MemoryRouter>
      <ObProjectListPanel title="Ongoing projects" rows={rows} open onClose={() => {}} />
    </MemoryRouter>,
  )
  // The row's accessible name opens with the project, which is what is ordered.
  return screen
    .getAllByRole('button', { name: /Open the project\.$/ })
    .map((button) => (button.getAttribute('aria-label') ?? '').split(',')[0])
}

describe('the project list panel', () => {
  /**
   * It sorted worst first until Sep 2026, so opening **Ongoing projects** on a
   * board with seven running projects and two slipped ones put both breaches
   * at the top and pushed everything still in flight below them.
   */
  it('lists the late projects last, the worst of them at the very bottom', () => {
    const order = renderPanel([
      row({ id: 1, name: 'Somerville', bucket: 'AT_RISK', daysPastCompletion: 9 }),
      row({ id: 2, name: 'Vishwa Bharti', bucket: 'AHEAD' }),
      row({ id: 3, name: 'DPS N', bucket: 'DELAYED', daysPastCompletion: 5 }),
      row({ id: 4, name: 'Lotus Valley' }),
    ])

    expect(order).toEqual(['Vishwa Bharti', 'Lotus Valley', 'DPS N', 'Somerville'])
  })

  /**
   * Overdue and At risk count breaches and nothing else, so there is no
   * running work for a late row to bury and the worst one is what the reader
   * came for. Read off the rows, so a donut's late arc reads the same way.
   */
  it('puts the worst first when every row in the list is late', () => {
    const order = renderPanel([
      row({ id: 3, name: 'DPS N', bucket: 'DELAYED', daysPastCompletion: 5 }),
      row({ id: 1, name: 'Somerville', bucket: 'AT_RISK', daysPastCompletion: 9 }),
      row({ id: 8, name: 'Trinity', bucket: 'DELAYED', daysPastCompletion: 2 }),
    ])

    expect(order).toEqual(['Somerville', 'DPS N', 'Trinity'])
  })

  /** Nothing late means nothing moves: the board's own order survives. */
  it('leaves a list with no late project in the order the board sent', () => {
    const order = renderPanel([
      row({ id: 7, name: 'Genesis Global', bucket: 'AHEAD' }),
      row({ id: 3, name: 'DOOMS' }),
      row({ id: 5, name: 'Shiv Nada' }),
    ])

    expect(order).toEqual(['Genesis Global', 'DOOMS', 'Shiv Nada'])
  })
})
