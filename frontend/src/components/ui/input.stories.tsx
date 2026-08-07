import type { Meta, StoryObj } from '@storybook/react-vite'
import { Input } from './input'

const meta: Meta<typeof Input> = {
  title: 'UI/Input',
  component: Input,
  tags: ['autodocs'],
  render: (args) => <Input className="w-72" {...args} />,
}
export default meta

type Story = StoryObj<typeof Input>

export const Default: Story = { args: { placeholder: 'Search tickets…' } }
export const WithValue: Story = { args: { defaultValue: 'CRM-26-00347' } }
export const Disabled: Story = { args: { placeholder: 'Disabled', disabled: true } }
export const Invalid: Story = {
  args: { defaultValue: '', placeholder: 'Required field', 'aria-invalid': true },
}
