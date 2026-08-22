import type { Meta, StoryObj } from '@storybook/react-vite'

import type { WorkflowStage } from '@/api/generated/model/workflowStage'
import { RibbonDots } from './RibbonDots'
import { buildCompactDots, type CompactDotTicket } from './compactDots'

const meta: Meta<typeof RibbonDots> = {
  title: 'Ribbon/RibbonDots',
  component: RibbonDots,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'The compact ribbon — blueprint line 984. Eight small dots per ticket-list row: ' +
          'filled = done, ringed = current, hollow = pending, amber diamond = sent back, so a ' +
          'manager can scan a whole grid without opening a ticket. Hovering a dot names the ' +
          'stage and its owner; the strip itself is one `role="img"` with one sentence, because ' +
          'eight focusable marks across 25 rows is 200 stops for a keyboard reader and nothing ' +
          'in the cell can be activated. `RibbonSegment` is the same six states at detail scale.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof RibbonDots>

/** §4A.1's Standard Dev Flow, in the shape `GET /masters/workflow-templates` serves. */
const STANDARD: WorkflowStage[] = [
  { stageCode: 'INTAKE', displayName: 'Intake', sequence: 1, ownerRole: 'SUPPORT' },
  { stageCode: 'TRIAGE', displayName: 'Triage / Planning', sequence: 2, ownerRole: 'PM' },
  { stageCode: 'DEV', displayName: 'Development', sequence: 3, ownerRole: 'DEVELOPER' },
  { stageCode: 'QA', displayName: 'QA / Testing', sequence: 4, ownerRole: 'QA' },
  { stageCode: 'DEPLOY', displayName: 'Deployment', sequence: 5, ownerRole: 'DEPLOYMENT' },
  { stageCode: 'VERIFY', displayName: 'Verification', sequence: 6, ownerRole: 'DEVELOPER' },
  { stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 7, ownerRole: 'PM' },
  { stageCode: 'CLOSED', displayName: 'Closed', sequence: 8, ownerRole: 'PM' },
]

/** Support Fast-Track — five stages, so the column is not a fixed eight. */
const FAST_TRACK: WorkflowStage[] = [
  { stageCode: 'INTAKE', displayName: 'Intake', sequence: 1, ownerRole: 'SUPPORT' },
  { stageCode: 'TRIAGE', displayName: 'Triage / Planning', sequence: 2, ownerRole: 'PM' },
  { stageCode: 'DEV', displayName: 'Development', sequence: 3, ownerRole: 'DEVELOPER' },
  { stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 4, ownerRole: 'SUPPORT' },
  { stageCode: 'CLOSED', displayName: 'Closed', sequence: 5, ownerRole: 'SUPPORT' },
]

const dots = (stages: WorkflowStage[], ticket: CompactDotTicket) =>
  buildCompactDots(stages, ticket) ?? []

export const InDevelopment: Story = {
  args: { dots: dots(STANDARD, { currentStageCode: 'DEV', status: 'IN_PROGRESS' }) },
}

export const JustRaised: Story = {
  args: { dots: dots(STANDARD, { currentStageCode: 'INTAKE', status: 'NEW' }) },
}

/** The amber diamond. It marks where the ticket is *now*, not which earlier
 * stage bounced — `TicketSummary` carries `iterationNo` and no transitions. */
export const SentBack: Story = {
  args: { dots: dots(STANDARD, { currentStageCode: 'DEV', status: 'REWORK', iterationNo: 2 }) },
}

/** No ring: a ring says "the work is here", and on a closed ticket it is nowhere. */
export const Closed: Story = {
  args: { dots: dots(STANDARD, { currentStageCode: 'CLOSED', status: 'CLOSED' }) },
}

/** A shorter template. The column is however many stages the row's own
 * template has, which is the whole reason this needed B-041's resolution route
 * rather than a fixed eight. */
export const FiveStageTemplate: Story = {
  args: { dots: dots(FAST_TRACK, { currentStageCode: 'SIGNOFF', status: 'RESOLVED' }) },
}

/** What a grid actually looks like — the case the feature exists for. */
export const AsAGridColumn: Story = {
  render: () => (
    <table className="text-body">
      <tbody>
        {[
          ['CRM-26-00347', STANDARD, { currentStageCode: 'DEV', status: 'IN_PROGRESS' as const }],
          ['CRM-26-00902', STANDARD, { currentStageCode: 'QA', status: 'IN_PROGRESS' as const, iterationNo: 2 }],
          ['SUP-26-00118', FAST_TRACK, { currentStageCode: 'INTAKE', status: 'NEW' as const }],
          ['CRM-26-00871', STANDARD, { currentStageCode: 'CLOSED', status: 'CLOSED' as const }],
        ].map(([id, stages, ticket]) => (
          <tr key={id as string}>
            <td className="pr-6 font-mono tabular-nums">{id as string}</td>
            <td>
              <RibbonDots dots={dots(stages as WorkflowStage[], ticket as CompactDotTicket)} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  ),
}
