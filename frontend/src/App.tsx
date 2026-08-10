import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { ScreenPlaceholder } from './app/ScreenPlaceholder'
import { TicketDetailPlaceholder } from './app/TicketDetailPlaceholder'
import { CreateTicketPage } from './features/tickets/create/CreateTicketPage'
import { WorkingCalendarPage } from './features/masters/calendar/WorkingCalendarPage'
import { Button } from './components/ui/button'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<ScreenPlaceholder title="Dashboard" />} />
          <Route path="/my-tasks" element={<ScreenPlaceholder title="My Tasks" />} />
          <Route
            path="/tickets"
            element={
              <ScreenPlaceholder
                title="Tickets"
                action={
                  <Button asChild>
                    {/* The grid's own [+ New Ticket] arrives with the list, C-014. */}
                    <Link to="/tickets/new">New ticket</Link>
                  </Button>
                }
              />
            }
          />
          {/* Ahead of `/tickets/:ticketId` for readability; React Router ranks
              the static segment higher regardless of order. */}
          <Route path="/tickets/new" element={<CreateTicketPage />} />
          <Route path="/tickets/:ticketId" element={<TicketDetailPlaceholder />} />
          <Route path="/projects" element={<ScreenPlaceholder title="Projects" />} />
          <Route path="/chat" element={<ScreenPlaceholder title="Chat" />} />
          <Route path="/reports" element={<ScreenPlaceholder title="Reports" />} />
          <Route
            path="/masters"
            element={
              <ScreenPlaceholder
                title="Masters"
                action={
                  <Button asChild>
                    {/* The masters index arrives with the rest of M3; until
                        then the calendar is reachable from here. */}
                    <Link to="/masters/calendar">Working calendar</Link>
                  </Button>
                }
              />
            }
          />
          <Route path="/masters/calendar" element={<WorkingCalendarPage />} />
          <Route path="/settings" element={<ScreenPlaceholder title="Settings" />} />
          <Route path="*" element={<ScreenPlaceholder title="Not found" />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
