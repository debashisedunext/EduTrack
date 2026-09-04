import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { ChatPage } from './features/chat/ChatPage'
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
import { BulkReassignWizardPage } from './features/tickets/reassign/BulkReassignWizardPage'
import { MyTasksPage } from './features/tickets/my-tasks/MyTasksPage'
import { StageQueuePage } from './features/tickets/stage-queue/StageQueuePage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { ClientListPage } from './features/clients/ClientListPage'
import { ClientFormPage } from './features/clients/ClientFormPage'
import { ClientProfilePage } from './features/clients/ClientProfilePage'
import { ClientImportPage } from './features/imports/ClientImportPage'
import { ResourceImportPage } from './features/imports/ResourceImportPage'
import { MastersIndexPage } from './features/masters/MastersIndexPage'
import { WorkingCalendarPage } from './features/masters/calendar/WorkingCalendarPage'
import { ProjectFormPage } from './features/masters/projects/ProjectFormPage'
import { ProjectListPage } from './features/masters/projects/ProjectListPage'
import { ProjectSettingsPage } from './features/masters/projects/ProjectSettingsPage'
import { ProjectTeamPage } from './features/masters/projects/ProjectTeamPage'
import { SlaMatrixPage } from './features/masters/projects/SlaMatrixPage'
import { NotificationTemplateListPage } from './features/masters/notificationTemplates/NotificationTemplateListPage'
import { TimesheetPage } from './features/masters/timesheet/TimesheetPage'
import { PriorityListPage } from './features/masters/priorities/PriorityListPage'
import { ResourceListPage } from './features/masters/resources/ResourceListPage'
import { RoleListPage } from './features/masters/roles/RoleListPage'
import { StatusMasterPage } from './features/masters/statuses/StatusMasterPage'
import { WorkflowDesignerPage } from './features/masters/designer/WorkflowDesignerPage'
import { JourneyTemplateDesignerPage } from './features/onboarding/journeys/JourneyTemplateDesignerPage'
import { RolePermissionsPage } from './features/masters/roles/RolePermissionsPage'
import { TaskTypeListPage } from './features/masters/taskTypes/TaskTypeListPage'
import { ResourceFormPage } from './features/masters/resources/ResourceFormPage'
import { ChangePasswordPage } from './features/auth/ChangePasswordPage'
import { ForgotPasswordPage } from './features/auth/ForgotPasswordPage'
import { LoginPage } from './features/auth/LoginPage'
import { RequireAuth } from './features/auth/RequireAuth'
import { ResetPasswordPage } from './features/auth/ResetPasswordPage'
import { AuditLogPage } from './features/audit/AuditLogPage'
import { ProjectDashboardPage } from './features/projects/ProjectDashboardPage'
import { ProjectIndexPage } from './features/projects/ProjectIndexPage'
import { ReportsHubPage } from './features/reports/ReportsHubPage'
import { ReportViewerPage } from './features/reports/ReportViewerPage'
import { ScheduledReportsPage } from './features/reports/ScheduledReportsPage'
import { ResourceProfilePage } from './features/resources/ResourceProfilePage'
import { SettingsPage } from './features/settings/SettingsPage'

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
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/my-tasks" element={<MyTasksPage />} />
            {/*
              C-062 · S-31, the QA and Deployment landing page. `LandingRoutes`
              on the server has mapped those two roles here since A-018 and
              carried a note that the route did not exist yet; this is the day
              that note describes, and nothing changes on the server side.
            */}
            <Route path="/stages/queue" element={<StageQueuePage />} />
            <Route path="/tickets" element={<TicketListPage />} />
            {/* Ahead of `/tickets/:ticketId` for readability; React Router ranks
                the static segment higher regardless of order. */}
            <Route path="/tickets/new" element={<CreateTicketPage />} />
            {/*
              S-24, the bulk reassignment wizard — C-063. B-014 declared this
              route as a placeholder because the Resource Master links into it —
              deactivating somebody who holds open tickets sends the admin here
              with `?fromUserId=…&returnTo=…`, per
              `features/masters/resources/reassignHandoff.ts` — and C-063 is
              what replaces the placeholder with the real screen.
            */}
            <Route path="/tickets/bulk-reassign" element={<BulkReassignWizardPage />} />
            <Route path={TICKET_ROUTE} element={<TicketDetailPage />} />
            {/* A-077 · the index the sidebar leads to. Not Stream B's project
                master, which stays at /masters/projects and owns every write —
                this lists projects and opens their dashboards, nothing else. */}
            <Route path="/projects" element={<ProjectIndexPage />} />
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
            {/* A-077 · the second of the three placeholders replaced. C-019
                registered this pattern the same way it registered the resource
                one, so every project name on every ticket already linked here. */}
            <Route path={PROJECT_ROUTE} element={<ProjectDashboardPage />} />
            {/* B-066 · the third and last of the three placeholders replaced.
                Every client name on every ticket already linked here. */}
            <Route path={CLIENT_ROUTE} element={<ClientProfilePage />} />
            {/* A-069 · S-28. C-019 registered this pattern against a placeholder
                so every assignee name in the product already linked here. */}
            <Route path={RESOURCE_ROUTE} element={<ResourceProfilePage />} />
            {/* D-065 · S-25. The placeholder this replaces was the last thing
                standing between a finished chat engine and anybody being able
                to use it — D-050 through D-057 have all been merged for days. */}
            <Route path="/chat" element={<ChatPage />} />
            {/*
              A-063 · the hub and its viewer. The viewer is a nested path rather
              than a modal because a filtered report is a URL people send to
              each other and bookmark — which is also why its filters live in
              the query string.
            */}
            <Route path="/reports" element={<ReportsHubPage />} />
            {/* A-065 · before the :reportKey route, or "schedules" is read as a report key. */}
            <Route path="/reports/schedules" element={<ScheduledReportsPage />} />
            <Route path="/reports/:reportKey" element={<ReportViewerPage />} />
            {/*
              A-071 · S-16. A top-level path rather than `/masters/audit-logs`:
              this is not a master anybody edits, and `audit.view` is a
              different capability from `master.write` — the two screens are
              reached by different people for different reasons. `RequireAuth`
              covers signing in; the Admin-only part is enforced by the server,
              which answers 403, and the page says so rather than pretending the
              screen does not exist.
            */}
            <Route path="/audit-logs" element={<AuditLogPage />} />
            {/*
              B-063 · §21's timesheet. A top-level path for the audit log's
              reason one route up: it is not a master anybody edits, and the
              person who opens it every Friday should not have to know it was
              built by the masters stream.

              Two routes, one page. `/timesheet` is your own week — the common
              case, and the only target a sidebar entry can have — and
              `/timesheet/:userId` is somebody else's, which is a link a manager
              follows and a URL they send. The server decides whether they may
              see it and answers 404 if not, so the route is deliberately not
              role-gated here.
            */}
            <Route path="/timesheet" element={<TimesheetPage />} />
            <Route path="/timesheet/:userId" element={<TimesheetPage />} />
            {/* B-067 · the index the sidebar's Masters entry has led to since
                A-030 and never actually reached until now. Permission-filtered
                per-card rather than gated as a whole page — `MastersIndexPage`'s
                own note has the per-master @PreAuthorize audit that decided
                which one (notification templates) needed it. */}
            <Route path="/masters" element={<MastersIndexPage />} />
            <Route path="/masters/resources" element={<ResourceListPage />} />
            {/* `/new` before `/:userId/edit` so the literal wins — otherwise
                "new" is matched as a userId and the form loads resource NaN.
                Inside `RequireAuth` and inside the shell, like every other
                master screen: the S-08 form is an Admin screen, not one of
                A-030's four shell-less auth routes. */}
            <Route path="/masters/resources/new" element={<ResourceFormPage />} />
            {/* B-038 · S-07's "bulk import via CSV", on §4B.3's wizard. Literal
                before `/:userId/edit` for the reason above, and deliberately the
                same shape of path as `/masters/clients/import`: it is the same
                screen, registered twice, and two differently-shaped URLs would be
                the first place that stopped being obvious. */}
            <Route path="/masters/resources/import" element={<ResourceImportPage />} />
            <Route path="/masters/resources/:userId/edit" element={<ResourceFormPage />} />
            {/* B-016 · S-10. `/new` before `/:projectId/edit` for the same
                reason the resource routes give — otherwise "new" matches as a
                projectId and the form loads project NaN. `/projects/:id` (the
                project *dashboard*, Stream A's A-069) is a different screen at
                a different path and is untouched. */}
            <Route path="/masters/projects" element={<ProjectListPage />} />
            <Route path="/masters/projects/new" element={<ProjectFormPage />} />
            <Route path="/masters/projects/:projectId/edit" element={<ProjectFormPage />} />
            {/* B-017 · S-10's Team tab. A sibling route rather than a nested
                one: the two tabs own their own data, and a layout route would
                make Team inherit General's read — which fetches the `ETag` its
                `PATCH` needs and this screen never sends. */}
            <Route path="/masters/projects/:projectId/team" element={<ProjectTeamPage />} />
            {/* B-018 · S-10's SLA tab, a sibling for the same reason — and a
                sharper one: it needs its own `ETag`, over the matrix rather
                than over the project, so no shared parent read could serve
                both tabs anyway. */}
            <Route path="/masters/projects/:projectId/sla" element={<SlaMatrixPage />} />
            {/* B-019 · S-10's Settings tab, and the fourth sibling. Same
                reasoning again, and it has its own `ETag` too — over a document
                spanning `projects` and `project_task_types`, which no read of
                the project alone could tag. Note it is `/masters/projects/:id/settings`
                and not the app-wide `/settings` two lines below; they are
                different screens and the path prefix is what keeps them
                apart. */}
            <Route path="/masters/projects/:projectId/settings" element={<ProjectSettingsPage />} />
            <Route path="/masters/roles" element={<RoleListPage />} />
            <Route path="/masters/roles/:roleId" element={<RolePermissionsPage />} />
            {/* B-021 · S-12. One route, not two: a level is six fields, so the
                create and edit forms are dialogs on the grid rather than a page
                each — the shape B-020 gave S-11. There is no `/:id` route to
                collide with. */}
            <Route path="/masters/priorities" element={<PriorityListPage />} />
            {/* B-020 · S-11. One route, not two: a task type is eight fields,
                so the create and edit forms are dialogs on the grid rather
                than a page each. There is no `/:id` route to collide with. */}
            <Route path="/masters/task-types" element={<TaskTypeListPage />} />
            {/*
              S-13, B-039 builds tab 1. `/masters/statuses` rather than
              `/masters/workflow`, because the tab an Admin lands on is the status
              list and B-040/B-041 add tabs to this page rather than routes beside
              it — and the template designer got its own route (S-30), below, which
              is what that arrangement was leaving room for.
            */}
            <Route path="/masters/statuses" element={<StatusMasterPage />} />
            {/*
              B-043 · S-30, the workflow template designer — the route the note
              above reserved. `/masters/workflow/...` rather than a fourth tab on
              `/masters/statuses`, because S-13's three tabs are §7.4's and a
              canvas needs the width of a page; and under `/masters/workflow/`
              rather than beside `/masters/statuses/:id`, so a template id can
              never be read as a status id. Reached from tab 3, not from the
              sidebar: S-30 is the builder *inside* S-13, and a nav entry beside
              it would read as a second, competing master.
            */}
            <Route
              path="/masters/workflow/designer/:templateId"
              element={<WorkflowDesignerPage />}
            />
            {/*
              C-102 · OB-07's journey template designer. Its own route rather
              than nested under `/masters/**` — the Onboarding module's
              screens are disjoint from the ticketing masters (plan §1.2), the
              same separation `db.ts`'s `obClients`/`obProducts` keep from
              `clients`/`projects`. There is no onboarding sidebar or nav
              section yet — nothing under `/onboarding/**` is routed at all
              before this — so this route is reached only by a direct link
              until that navigation exists; registering it is this task's
              whole scope.
            */}
            <Route
              path="/onboarding/journey-templates/:templateId"
              element={<JourneyTemplateDesignerPage />}
            />
            <Route path="/masters/calendar" element={<WorkingCalendarPage />} />
            {/* B-022 · S-15. One route, like S-11 and S-12: a template is six
                fields, so create and edit are dialogs on the grid rather than a
                page each. There is no `/:id` route to collide with. */}
            <Route
              path="/masters/notification-templates"
              element={<NotificationTemplateListPage />}
            />
            {/* B-025 · S-32. Under `/masters` because it is a master screen,
                while `/clients/:clientId` two routes up is the client 360 — a
                different screen at a different path, kept apart by the prefix
                the way `/masters/projects` and `/projects/:id` already are.
                The create/edit form is B-026's, on the two routes below. */}
            <Route path="/masters/clients" element={<ClientListPage />} />
            {/* B-026 · S-33. `/new` before `/:clientId/edit` for readability
                only — React Router ranks the literal segment above the variable
                regardless of order, the same ranking `/tickets/new` and
                `/masters/resources/new` already depend on. One component serves
                both: they are one form, and two would be the same file twice
                with one copy always slightly behind. */}
            <Route path="/masters/clients/new" element={<ClientFormPage />} />
            {/* B-031 · S-34, the Excel import wizard. A literal segment beside
                `/new`, and the same ranking applies: `:clientId` never swallows
                it. B-038 moved the component out of `features/clients/` and made
                it configurable; this route is one of its two registrations and
                the path is unchanged. */}
            <Route path="/masters/clients/import" element={<ClientImportPage />} />
            <Route path="/masters/clients/:clientId/edit" element={<ClientFormPage />} />
            {/* The personal half of Settings — profile, password, 2FA, theme
                and the browser-push switch.

                **B-068's decision is not reversed.** That task declined an
                *org* settings screen around `PUT /attachments/limits`
                (DEPENDENCIES.md row 24), and attachment limits are still not
                here; that row stands untouched. What this replaces is the
                consequence nobody chose — a sidebar entry every role can see,
                leading to an empty state, while `POST /me/2fa/*` sat
                implemented and reachable from no screen at all.

                One route, three tabs, `?tab=` for linkability. The two tabs
                that are missing — S-26's notification matrix and, if it is ever
                revisited, Organisation — are named in `SettingsPage`'s comment
                with whose they are. */}
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<ScreenPlaceholder title="Not found" />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
