import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { ScreenPlaceholder } from './app/ScreenPlaceholder'
import { CreateTicketPage } from './features/tickets/create/CreateTicketPage'
import { TicketDetailPage } from './features/tickets/detail/TicketDetailPage'
import {
  CLIENT_ROUTE,
  PROJECT_ROUTE,
  RESOURCE_ROUTE,
  TICKET_ROUTE,
} from './features/tickets/detail/entityLinks'
import { TicketListPage } from './features/tickets/list/TicketListPage'
import { MyTasksPage } from './features/tickets/my-tasks/MyTasksPage'
import { WorkingCalendarPage } from './features/masters/calendar/WorkingCalendarPage'
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
          <Route path={TICKET_ROUTE} element={<TicketDetailPage />} />
          <Route path="/projects" element={<ScreenPlaceholder title="Projects" />} />
          {/*
            S-20's traceability rule is that every entity in the summary panel
            is a link, and three of those destinations belong to other streams:
            the project dashboard and the resource 360 (A-069 / S-28) to Stream
            A, the client 360 to Stream B. Their routes are declared here, from
            the same constants the links are built from, so a link lands on a
            named "not built yet" screen instead of the catch-all Not found —
            which reads as a broken link rather than an unbuilt screen. Each
            owner replaces one element.
          */}
          <Route path={PROJECT_ROUTE} element={<ScreenPlaceholder title="Project dashboard" />} />
          <Route path={CLIENT_ROUTE} element={<ScreenPlaceholder title="Client 360" />} />
          <Route path={RESOURCE_ROUTE} element={<ScreenPlaceholder title="Resource profile" />} />
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
