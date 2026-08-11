import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { ScreenPlaceholder } from './app/ScreenPlaceholder'
import { TicketDetailPlaceholder } from './app/TicketDetailPlaceholder'
import { CreateTicketPage } from './features/tickets/create/CreateTicketPage'
import { TicketListPage } from './features/tickets/list/TicketListPage'
import { MyTasksPage } from './features/tickets/my-tasks/MyTasksPage'
import { WorkingCalendarPage } from './features/masters/calendar/WorkingCalendarPage'
import { ResourceListPage } from './features/masters/resources/ResourceListPage'
import { Button } from './components/ui/button'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<ScreenPlaceholder title="Dashboard" />} />
          <Route path="/my-tasks" element={<MyTasksPage />} />
          <Route path="/tickets" element={<TicketListPage />} />
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
                  <div className="flex flex-wrap items-center justify-center gap-2">
                    {/* The masters index arrives with the rest of M3; until
                        then the screens that exist are reachable from here. */}
                    <Button asChild>
                      <Link to="/masters/resources">Resources</Link>
                    </Button>
                    <Button asChild variant="secondary">
                      <Link to="/masters/calendar">Working calendar</Link>
                    </Button>
                  </div>
                }
              />
            }
          />
          <Route path="/masters/resources" element={<ResourceListPage />} />
          <Route path="/masters/calendar" element={<WorkingCalendarPage />} />
          <Route path="/settings" element={<ScreenPlaceholder title="Settings" />} />
          <Route path="*" element={<ScreenPlaceholder title="Not found" />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
