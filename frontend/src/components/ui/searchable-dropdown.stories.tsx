import { useState } from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { SearchableDropdown } from './searchable-dropdown'

const CLIENTS = [
  { id: '1', name: 'Acme Corp', code: 'ACME', domain: 'acme.com' },
  { id: '2', name: 'Globex Inc', code: 'GLBX', domain: 'globex.com' },
  { id: '3', name: 'Initech', code: 'INIT', domain: 'initech.com' },
  { id: '4', name: 'Umbrella LLC', code: 'UMBR', domain: 'umbrella.com' },
]

const meta: Meta<typeof SearchableDropdown> = {
  title: 'UI/SearchableDropdown',
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof SearchableDropdown>

// Type-ahead over name, code and domain — blueprint §4B.2's client dropdown.
export const ClientPicker: Story = {
  render: function Render() {
    const [client, setClient] = useState<(typeof CLIENTS)[number] | null>(null)
    return (
      <SearchableDropdown
        options={CLIENTS}
        value={client}
        onChange={setClient}
        getKey={(c) => c.id}
        getLabel={(c) => c.name}
        getSearchable={(c) => [c.code, c.domain]}
        placeholder="Search clients…"
        className="w-72"
      />
    )
  },
}

/**
 * Labelled and in an error state.
 *
 * `htmlFor` on a `<label>` names the trigger because a `button` is a labelable
 * element; without `id` the control reaches a screen reader unnamed, which
 * fails AA. `aria-describedby` carries the error text, and `aria-invalid` is
 * what the danger border keys off. All four are optional — a dropdown that
 * needs none of them renders exactly as it did before.
 */
export const LabelledWithError: Story = {
  render: function Render() {
    const [client, setClient] = useState<(typeof CLIENTS)[number] | null>(null)
    return (
      <div className="flex w-72 flex-col gap-1.5">
        <label htmlFor="story-client" className="text-sm font-medium text-content">
          Client
        </label>
        <SearchableDropdown
          id="story-client"
          aria-describedby="story-client-error"
          aria-invalid={client === null}
          options={CLIENTS}
          value={client}
          onChange={setClient}
          getKey={(c) => c.id}
          getLabel={(c) => c.name}
          getSearchable={(c) => [c.code, c.domain]}
          placeholder="Search clients…"
        />
        {client === null && (
          <p id="story-client-error" role="alert" className="text-caption text-danger-text">
            This task type is client-facing — pick the client it was raised for
          </p>
        )}
      </div>
    )
  },
}
