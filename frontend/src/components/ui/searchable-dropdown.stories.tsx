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
