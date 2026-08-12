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
import { ResourceListPage } from './features/masters/resources/ResourceListPage'
import { ResourceFormPage } from './features/masters/resources/ResourceFormPage'
import { ChangePasswordPage } from './features/auth/ChangePasswordPage'
import { ForgotPasswordPage } from './features/auth/ForgotPasswordPage'
import { LoginPage } from './features/auth/LoginPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { ResetPasswordPage } from './features/auth/ResetPasswordPage'
import { Button } from './components/ui/button'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/*
          S-01, S-02 and the reset link sit outside `RequireAuth` and outside the
          shell — A-030. The shell's top bar carries an avatar, a project
          switcher and a notification bell, all of which query `/me` and none of
          which mean anything to someone who is not signed in.
        */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        <Route element={<RequireAuth />}>
          {/*
            S-03 is authenticated but deliberately shell-less. A-026 closes every
            other route until the password is changed, so a sidebar full of links
            that all redirect back here would be a menu of dead ends.
          */}
          <Route path="/change-password" element={<ChangePasswordPage />} />

          <Route element={<AppShell />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<ScreenPlaceholder title="Dashboard" />} />
            <Route path="/my-tasks" element={<MyTasksPage />} />
            <Route path="/tickets" element={<TicketListPage />} />
            {/* Ahead of `/tickets/:ticketId` for readability; React Router ranks
                the static segment higher regardless of order. */}
            <Route path="/tickets/new" element={<CreateTicketPage />} />
            {/*
              S-24, the bulk reassignment wizard. Stream C's C-063 replaces this
              element; B-014 declares the route because the Resource Master now
              links into it — deactivating somebody who holds open tickets sends
              the admin here with `?fromUserId=…&returnTo=…`, per
              `features/masters/resources/reassignHandoff.ts`.

              Declared rather than left to the catch-all for the reason the three
              routes below give: an unbuilt screen and a broken link look
              identical to a user, and only one of them is worth reporting.
              **Flagged for Stream C** — one line in a shared file, replaced by
              one line when C-063 lands.
            */}
            <Route
              path="/tickets/bulk-reassign"
              element={<ScreenPlaceholder title="Bulk reassignment wizard" />}
            />
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
            {/* `/new` before `/:userId/edit` so the literal wins — otherwise
                "new" is matched as a userId and the form loads resource NaN.
                Inside `RequireAuth` and inside the shell, like every other
                master screen: the S-08 form is an Admin screen, not one of
                A-030's four shell-less auth routes. */}
            <Route path="/masters/resources/new" element={<ResourceFormPage />} />
            <Route path="/masters/resources/:userId/edit" element={<ResourceFormPage />} />
            <Route path="/masters/calendar" element={<WorkingCalendarPage />} />
            <Route path="/settings" element={<ScreenPlaceholder title="Settings" />} />
            <Route path="*" element={<ScreenPlaceholder title="Not found" />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
