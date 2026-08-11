import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { FilterDropdown } from './filter-dropdown'

interface Project {
  id: number
  projectCode: string
  name: string
}

const PROJECTS: Project[] = [
  { id: 1, projectCode: 'CRM', name: 'Client CRM Platform' },
  { id: 2, projectCode: 'PAY', name: 'Payments Gateway' },
  { id: 3, projectCode: 'WEB', name: 'Marketing Website' },
]

const ROLES = [
  { value: 'ADMIN', label: 'Admin' },
  { value: 'PM', label: 'PM' },
  { value: 'DEVELOPER', label: 'Developer' },
  { value: 'QA', label: 'QA' },
  { value: 'DEPLOYMENT', label: 'Deployment' },
  { value: 'SUPPORT', label: 'Support' },
]

const meta: Meta<typeof FilterDropdown> = {
  title: 'UI/FilterDropdown',
  component: FilterDropdown,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'A filter-bar dropdown. Unlike `SearchableDropdown`, unset is a first-class ' +
          'value: the open list carries an "All …" row and the closed trigger carries an ' +
          'inline clear button, so returning a filter to unset never costs opening the popup. ' +
          'Used by the ticket list (S-17) and the resource master (S-07).',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof FilterDropdown<Project>>

/** Unset — the default state, and the one a filter bar spends most of its life in. */
export const Unset: Story = {
  render: () => {
    const [value, setValue] = React.useState<Project | null>(null)
    return (
      <FilterDropdown
        label="Project"
        options={PROJECTS}
        value={value}
        onChange={setValue}
        getKey={(p) => String(p.id)}
        getLabel={(p) => `${p.projectCode} — ${p.name}`}
        getSearchable={(p) => [p.projectCode, p.name]}
      />
    )
  },
}

/** Set — the trigger turns primary and grows a clear button in place of the chevron. */
export const Selected: Story = {
  render: () => {
    const [value, setValue] = React.useState<Project | null>(PROJECTS[0])
    return (
      <FilterDropdown
        label="Project"
        options={PROJECTS}
        value={value}
        onChange={setValue}
        getKey={(p) => String(p.id)}
        getLabel={(p) => `${p.projectCode} — ${p.name}`}
      />
    )
  },
}

/**
 * `searchable={false}` for a short enum. There is nothing to type-ahead over
 * six options, and the search box would be one more thing to tab through.
 */
export const ShortEnum: StoryObj = {
  render: () => {
    const [value, setValue] = React.useState<(typeof ROLES)[number] | null>(null)
    return (
      <FilterDropdown
        label="Role"
        options={ROLES}
        value={value}
        onChange={setValue}
        getKey={(r) => r.value}
        getLabel={(r) => r.label}
        searchable={false}
      />
    )
  },
}

/** A whole filter row, which is how it is actually seen. */
export const FilterRow: StoryObj = {
  render: () => {
    const [project, setProject] = React.useState<Project | null>(PROJECTS[1])
    const [role, setRole] = React.useState<(typeof ROLES)[number] | null>(null)
    return (
      <div className="flex flex-wrap items-center gap-2">
        <FilterDropdown
          label="Project"
          options={PROJECTS}
          value={project}
          onChange={setProject}
          getKey={(p) => String(p.id)}
          getLabel={(p) => p.projectCode}
        />
        <FilterDropdown
          label="Role"
          options={ROLES}
          value={role}
          onChange={setRole}
          getKey={(r) => r.value}
          getLabel={(r) => r.label}
          searchable={false}
        />
      </div>
    )
  },
}

export const Disabled: Story = {
  render: () => (
    <FilterDropdown
      label="Project"
      options={PROJECTS}
      value={null}
      onChange={() => {}}
      getKey={(p) => String(p.id)}
      getLabel={(p) => p.projectCode}
      disabled
    />
  ),
}
