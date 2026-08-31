import { describe, expect, it } from 'vitest'

import { DEFAULT_ROW_LIMIT, groupTickets, type GroupableTicket, type TicketGroup } from './groupTickets'

/**
 * S-05 tab 3 · the nesting the weekly accordions are drawn from.
 *
 * The assertions that matter here are about the two things a reader of the
 * screen can check for themselves and will: that a header's count equals the
 * tickets behind it, and that it keeps equalling them once the cap has thrown
 * rows away. Everything else is ordering, which only has to be *stable* —
 * tested by grouping the same input twice from different input orders.
 */

interface Row extends GroupableTicket {
  title: string
}

const select = (r: Row): GroupableTicket => r

function ticket(over: Partial<Row> & { ticketId: string }): Row {
  return {
    title: `Ticket ${over.ticketId}`,
    level: 'HIGH',
    client: { id: 1, name: 'Acme' },
    moduleId: 10,
    ...over,
  }
}

const SEVERITY = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const
const MODULES = new Map([[10, 'Billing'], [20, 'Reports']])
const opts = {
  moduleLabel: (id: number) => MODULES.get(id),
  severityOrder: SEVERITY,
}

/** Every leaf row under a node, in render order. */
function rowsUnder<T>(node: TicketGroup<T>): T[] {
  return node.children.length === 0 ? node.rows : node.children.flatMap(rowsUnder)
}

describe('groupTickets — the shape', () => {
  it('nests client → module → severity and puts the rows on the leaves', () => {
    const result = groupTickets(
      [
        ticket({ ticketId: 'A-1', level: 'HIGH' }),
        ticket({ ticketId: 'A-2', level: 'LOW' }),
        ticket({ ticketId: 'A-3', moduleId: 20, level: 'HIGH' }),
        ticket({ ticketId: 'B-1', client: { id: 2, name: 'Globex' } }),
      ],
      select,
      opts,
    )

    expect(result.groups.map((g) => g.label)).toEqual(['Acme', 'Globex'])
    const acme = result.groups[0]
    expect(acme.count).toBe(3)
    expect(acme.children.map((m) => `${m.label}:${m.count}`)).toEqual(['Billing:2', 'Reports:1'])
    expect(acme.children[0].children.map((s) => s.label)).toEqual(['HIGH', 'LOW'])
    expect(acme.children[0].children[0].rows.map((r) => r.ticketId)).toEqual(['A-1'])
    // Rows live only on the leaves; the branches carry counts.
    expect(acme.rows).toEqual([])
    expect(acme.children[0].rows).toEqual([])
  })

  it('carries the fields that identify each node, and builds no URLs', () => {
    const result = groupTickets([ticket({ ticketId: 'A-1', level: 'CRITICAL' })], select, opts)
    const [client] = result.groups
    expect(client.filter).toEqual({ clientId: 1 })
    expect(client.children[0].filter).toEqual({ clientId: 1, moduleId: 10 })
    expect(client.children[0].children[0].filter).toEqual({ clientId: 1, moduleId: 10, level: 'CRITICAL' })
  })

  it('counts at every level equal the tickets beneath them', () => {
    const rows = Array.from({ length: 12 }, (_, i) =>
      ticket({
        ticketId: `T-${i}`,
        client: { id: i % 3, name: `Client ${i % 3}` },
        moduleId: i % 2 === 0 ? 10 : 20,
        level: SEVERITY[i % 4],
      }),
    )
    const result = groupTickets(rows, select, opts)

    const check = (node: TicketGroup<Row>) => {
      expect(node.count).toBe(rowsUnder(node).length)
      node.children.forEach(check)
    }
    result.groups.forEach(check)
    expect(result.total).toBe(12)
    expect(result.truncated).toBe(0)
  })
})

describe('groupTickets — the missing-value buckets', () => {
  it('labels a ticket with no module and sorts it after the named ones', () => {
    const result = groupTickets(
      [
        ticket({ ticketId: 'A-1', moduleId: null }),
        ticket({ ticketId: 'A-2', moduleId: 20 }),
        ticket({ ticketId: 'A-3', moduleId: 10 }),
      ],
      select,
      opts,
    )
    expect(result.groups[0].children.map((m) => m.label)).toEqual(['Billing', 'Reports', 'No module'])
    // The bucket is a real node with a real count, not a dropped row.
    expect(result.groups[0].children[2].count).toBe(1)
    expect(result.groups[0].count).toBe(3)
  })

  it('labels a ticket with no client the same way', () => {
    const result = groupTickets(
      [ticket({ ticketId: 'A-1', client: null }), ticket({ ticketId: 'A-2' })],
      select,
      opts,
    )
    expect(result.groups.map((g) => g.label)).toEqual(['Acme', 'No client'])
    expect(result.groups[1].children[0].filter).toEqual({ moduleId: 10 })
  })

  it('falls back to the id when a module has no name, rather than showing a blank header', () => {
    const result = groupTickets([ticket({ ticketId: 'A-1', moduleId: 99 })], select, opts)
    expect(result.groups[0].children[0].label).toBe('Module 99')
  })

  it('treats a whitespace-only name as missing', () => {
    const result = groupTickets(
      [ticket({ ticketId: 'A-1', client: { id: 7, name: '   ' } })],
      select,
      opts,
    )
    expect(result.groups[0].label).toBe('Client 7')
  })

  it('keeps a single-ticket client as a full three-level branch', () => {
    const result = groupTickets([ticket({ ticketId: 'A-1' })], select, opts)
    expect(result.groups).toHaveLength(1)
    expect(result.groups[0].children).toHaveLength(1)
    expect(result.groups[0].children[0].children).toHaveLength(1)
    expect(result.groups[0].children[0].children[0].rows).toHaveLength(1)
  })

  it('returns an empty result rather than throwing on no tickets', () => {
    expect(groupTickets([], select, opts)).toEqual({ groups: [], total: 0, rendered: 0, truncated: 0 })
  })
})

describe('groupTickets — severity ordering comes from the master', () => {
  it('orders by the supplied sequence, not alphabetically', () => {
    const result = groupTickets(
      [
        ticket({ ticketId: 'A-1', level: 'LOW' }),
        ticket({ ticketId: 'A-2', level: 'CRITICAL' }),
        ticket({ ticketId: 'A-3', level: 'MEDIUM' }),
      ],
      select,
      opts,
    )
    expect(result.groups[0].children[0].children.map((s) => s.label))
      .toEqual(['CRITICAL', 'MEDIUM', 'LOW'])
  })

  it('honours an organisation that configured its own levels', () => {
    const result = groupTickets(
      [
        ticket({ ticketId: 'A-1', level: 'P3' }),
        ticket({ ticketId: 'A-2', level: 'P1' }),
        ticket({ ticketId: 'A-3', level: 'P2' }),
      ],
      select,
      { ...opts, severityOrder: ['P1', 'P2', 'P3'] },
    )
    expect(result.groups[0].children[0].children.map((s) => s.label)).toEqual(['P1', 'P2', 'P3'])
  })

  it('puts a level the master does not list after the ones it does, rather than dropping it', () => {
    const result = groupTickets(
      [
        ticket({ ticketId: 'A-1', level: 'BLOCKER' }),
        ticket({ ticketId: 'A-2', level: 'LOW' }),
        ticket({ ticketId: 'A-3', level: 'CRITICAL' }),
      ],
      select,
      opts,
    )
    expect(result.groups[0].children[0].children.map((s) => s.label))
      .toEqual(['CRITICAL', 'LOW', 'BLOCKER'])
    expect(result.total).toBe(3)
  })

  it('with no severityOrder at all, still groups and still counts', () => {
    const result = groupTickets(
      [ticket({ ticketId: 'A-1', level: 'LOW' }), ticket({ ticketId: 'A-2', level: 'CRITICAL' })],
      select,
      { moduleLabel: (id) => MODULES.get(id) },
    )
    expect(result.groups[0].children[0].children.map((s) => s.label)).toEqual(['CRITICAL', 'LOW'])
    expect(result.total).toBe(2)
  })
})

describe('groupTickets — the 200-row cap', () => {
  const many = (n: number, over: (i: number) => Partial<Row> = () => ({})) =>
    Array.from({ length: n }, (_, i) =>
      ticket({ ticketId: `T-${String(i).padStart(4, '0')}`, ...over(i) }),
    )

  it('caps rendered rows at the limit and reports what it dropped', () => {
    const result = groupTickets(many(250), select, opts)
    expect(result.total).toBe(250)
    expect(result.rendered).toBe(DEFAULT_ROW_LIMIT)
    expect(result.truncated).toBe(50)
  })

  it('keeps every count TRUE while rows are dropped — the reason this module exists', () => {
    const result = groupTickets(many(250), select, { ...opts, limit: 10 })

    const client = result.groups[0]
    expect(client.count).toBe(250)
    expect(rowsUnder(client)).toHaveLength(10)
    expect(client.truncated).toBe(240)

    const leaf = client.children[0].children[0]
    expect(leaf.count).toBe(250)
    expect(leaf.rows).toHaveLength(10)
    expect(leaf.truncated).toBe(240)
  })

  it('reports a level whose children are ALL truncated with its true count and no rows', () => {
    // Two clients, 6 tickets each; a budget of 6 fills the first and starves the second.
    const rows = [
      ...many(6, () => ({ client: { id: 1, name: 'Acme' } })),
      ...Array.from({ length: 6 }, (_, i) =>
        ticket({ ticketId: `Z-${i}`, client: { id: 2, name: 'Globex' } })),
    ]
    const result = groupTickets(rows, select, { ...opts, limit: 6 })

    const [acme, globex] = result.groups
    expect(rowsUnder(acme)).toHaveLength(6)
    expect(acme.truncated).toBe(0)

    expect(globex.count).toBe(6)
    expect(rowsUnder(globex)).toHaveLength(0)
    expect(globex.truncated).toBe(6)
    // The header still exists and still says 6 — an invisible group would read
    // as "Globex has nothing this week", which is a different and false claim.
    expect(globex.children[0].children[0].count).toBe(6)
    expect(globex.children[0].children[0].rows).toEqual([])
  })

  it('keeps the rows at the top of the screen, not an arbitrary subset', () => {
    const result = groupTickets(many(5), select, { ...opts, limit: 2 })
    expect(rowsUnder(result.groups[0]).map((r) => r.ticketId)).toEqual(['T-0000', 'T-0001'])
  })

  it('a limit of zero renders nothing and still counts everything', () => {
    const result = groupTickets(many(5), select, { ...opts, limit: 0 })
    expect(result.rendered).toBe(0)
    expect(result.truncated).toBe(5)
    expect(result.groups[0].count).toBe(5)
  })
})

describe('groupTickets — stability', () => {
  const rows = [
    ticket({ ticketId: 'A-3', client: { id: 2, name: 'Globex' }, moduleId: 20, level: 'LOW' }),
    ticket({ ticketId: 'A-1', client: { id: 1, name: 'Acme' }, moduleId: 10, level: 'CRITICAL' }),
    ticket({ ticketId: 'A-2', client: { id: 1, name: 'Acme' }, moduleId: 10, level: 'CRITICAL' }),
    ticket({ ticketId: 'A-4', client: null, moduleId: null, level: 'HIGH' }),
  ]

  /** Keys and row order, flattened — the whole visible structure in one string. */
  const shapeOf = (input: Row[]) => {
    const walk = (n: TicketGroup<Row>): string =>
      `${n.key}(${n.count})[${n.rows.map((r) => r.ticketId).join(',')}]`
      + n.children.map(walk).join('')
    return groupTickets(input, select, opts).groups.map(walk).join('|')
  }

  it('produces the same tree whatever order the tickets arrive in', () => {
    const forward = shapeOf(rows)
    const reversed = shapeOf([...rows].reverse())
    const rotated = shapeOf([rows[2], rows[3], rows[0], rows[1]])
    expect(reversed).toBe(forward)
    expect(rotated).toBe(forward)
  })

  it('gives every node a key that is unique and stable', () => {
    const result = groupTickets(rows, select, opts)
    const keys: string[] = []
    const collect = (n: TicketGroup<Row>) => {
      keys.push(n.key)
      n.children.forEach(collect)
    }
    result.groups.forEach(collect)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys).toEqual(groupTickets([...rows].reverse(), select, opts).groups.flatMap(function all(n): string[] {
      return [n.key, ...n.children.flatMap(all)]
    }))
  })
})
