import type { Meta, StoryObj } from '@storybook/react-vite'
import { TableContainer, Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from './table'
import { Chip } from './chip'

const ROWS = Array.from({ length: 15 }, (_, i) => ({
  id: `CRM-26-${String(i + 1).padStart(5, '0')}`,
  title: `Sample ticket row ${i + 1}`,
  level: (['low', 'medium', 'high', 'critical'] as const)[i % 4],
}))

const meta: Meta<typeof Table> = {
  title: 'UI/Table',
  tags: ['autodocs'],
  parameters: { layout: 'padded' },
}
export default meta

type Story = StoryObj<typeof Table>

// Scroll the container — the header shadow only appears once scrolled (§12.3).
export const StickyHeaderOnScroll: Story = {
  render: () => (
    <TableContainer className="max-h-72 w-[560px]">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Ticket</TableHead>
            <TableHead>Title</TableHead>
            <TableHead>Level</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {ROWS.map((r) => (
            <TableRow key={r.id}>
              <TableCell>{r.id}</TableCell>
              <TableCell>{r.title}</TableCell>
              <TableCell>
                <Chip variant={r.level}>{r.level}</Chip>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  ),
}
