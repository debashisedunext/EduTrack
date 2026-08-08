import { useParams } from 'react-router-dom'
import { ScreenPlaceholder } from './ScreenPlaceholder'

/** Where the command palette (C-006) lands until the real detail page (C-019) exists. */
export function TicketDetailPlaceholder() {
  const { ticketId } = useParams()
  return <ScreenPlaceholder title={`Ticket ${ticketId}`} />
}
