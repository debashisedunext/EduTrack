import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { SearchableDropdown } from './searchable-dropdown'

/**
 * B-028 · `getOptionDisabled`.
 *
 * The component's other behaviour is covered by Storybook and by the screens
 * that use it; what is asserted here is the one addition, because it is a
 * *refusal* — the kind of behaviour that looks right until somebody reaches it
 * by the path nobody tested.
 */

type Client = { id: string; label: string; hasPrimaryContact: boolean }

const CLIENTS: Client[] = [
  { id: '1', label: 'ACME — Acme Retail', hasPrimaryContact: true },
  { id: '2', label: 'KESTREL — Kestrel Analytics', hasPrimaryContact: false },
]

function Picker({ onChange }: { onChange: (c: Client) => void }) {
  const [value, setValue] = useState<Client | null>(null)
  return (
    <SearchableDropdown<Client>
      options={CLIENTS}
      value={value}
      onChange={(c) => {
        setValue(c)
        onChange(c)
      }}
      getKey={(c) => c.id}
      getLabel={(c) => c.label}
      getOptionDisabled={(c) => (c.hasPrimaryContact ? null : 'No primary contact')}
      placeholder="Search clients…"
    />
  )
}

describe('SearchableDropdown · unselectable options', () => {
  it('lists the option rather than hiding it, and shows why it cannot be chosen', async () => {
    const user = userEvent.setup()
    render(<Picker onChange={vi.fn()} />)

    await user.click(screen.getByRole('button'))

    // Listed, not filtered out — a client that simply is not there is
    // indistinguishable from a dropdown that has lost its data.
    const option = await screen.findByRole('option', { name: /Kestrel/ })
    expect(option).toHaveAttribute('aria-disabled', 'true')
    expect(option).toHaveTextContent('No primary contact')
  })

  it('does not select on click', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Picker onChange={onChange} />)

    await user.click(screen.getByRole('button'))
    await user.click(await screen.findByRole('option', { name: /Kestrel/ }))

    expect(onChange).not.toHaveBeenCalled()
    // Still open: closing on a refused click would read as a selection that
    // took, and the user would only find out at save.
    expect(screen.getByRole('option', { name: /Kestrel/ })).toBeInTheDocument()
  })

  /**
   * The path that would have been missed. A `pointer-events-none` on the row
   * disables the mouse and nothing else — arrow-down twice, Enter, and the
   * refusal is gone. `select()` is the single gate for exactly this reason.
   */
  it('does not select on Enter either', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Picker onChange={onChange} />)

    await user.click(screen.getByRole('button'))
    await screen.findByRole('option', { name: /Kestrel/ })
    await user.keyboard('{ArrowDown}{Enter}')

    expect(onChange).not.toHaveBeenCalled()
  })

  it('still selects the options that are allowed', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Picker onChange={onChange} />)

    await user.click(screen.getByRole('button'))
    await user.click(await screen.findByRole('option', { name: /Acme/ }))

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: '1' }))
  })

  /** Every existing call site passes no `getOptionDisabled` and must be unaffected. */
  it('leaves every option selectable when the prop is omitted', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <SearchableDropdown<Client>
        options={CLIENTS}
        value={null}
        onChange={onChange}
        getKey={(c) => c.id}
        getLabel={(c) => c.label}
        placeholder="Search clients…"
      />,
    )

    await user.click(screen.getByRole('button'))
    await user.click(await screen.findByRole('option', { name: /Kestrel/ }))

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: '2' }))
  })
})
