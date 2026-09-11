import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { LauncherPage } from './features/launcher/LauncherPage'
import { LoginPage } from './features/auth/LoginPage'
import { ScreenPlaceholder } from './app/ScreenPlaceholder'
import {
  CLIENT_ROUTE,
  PROJECT_ROUTE,
  RESOURCE_ROUTE,
  TICKET_ROUTE,
} from './features/tickets/detail/entityLinks'
import { RequireAuth } from './features/auth/RequireAuth'
import { PortalAuthProvider } from './features/portal/auth/PortalAuthProvider'
import { PortalRequireAuth } from './features/portal/auth/PortalRequireAuth'

const AuditLogPage = lazy(() =>
  import('./features/audit/AuditLogPage').then((m) => ({ default: m.AuditLogPage })),
)
const BulkReassignWizardPage = lazy(() =>
  import('./features/tickets/reassign/BulkReassignWizardPage').then((m) => ({ default: m.BulkReassignWizardPage })),
)
const ChangePasswordPage = lazy(() =>
  import('./features/auth/ChangePasswordPage').then((m) => ({ default: m.ChangePasswordPage })),
)
const ChatPage = lazy(() =>
  import('./features/chat/ChatPage').then((m) => ({ default: m.ChatPage })),
)
const ClientFormPage = lazy(() =>
  import('./features/clients/ClientFormPage').then((m) => ({ default: m.ClientFormPage })),
)
const ClientImportPage = lazy(() =>
  import('./features/imports/ClientImportPage').then((m) => ({ default: m.ClientImportPage })),
)
const ClientListPage = lazy(() =>
  import('./features/clients/ClientListPage').then((m) => ({ default: m.ClientListPage })),
)
const ClientProfilePage = lazy(() =>
  import('./features/clients/ClientProfilePage').then((m) => ({ default: m.ClientProfilePage })),
)
const CreateTicketPage = lazy(() =>
  import('./features/tickets/create/CreateTicketPage').then((m) => ({ default: m.CreateTicketPage })),
)
const ForgotPasswordPage = lazy(() =>
  import('./features/auth/ForgotPasswordPage').then((m) => ({ default: m.ForgotPasswordPage })),
)
const JourneyTemplateDesignerPage = lazy(() =>
  import('./features/onboarding/journeys/JourneyTemplateDesignerPage').then((m) => ({ default: m.JourneyTemplateDesignerPage })),
)
const ModuleServiceCataloguePage = lazy(() =>
  import('./features/onboarding/journeys/ModuleServiceCataloguePage').then((m) => ({ default: m.ModuleServiceCataloguePage })),
)
const MastersIndexPage = lazy(() =>
  import('./features/masters/MastersIndexPage').then((m) => ({ default: m.MastersIndexPage })),
)
const MyTasksPage = lazy(() =>
  import('./features/tickets/my-tasks/MyTasksPage').then((m) => ({ default: m.MyTasksPage })),
)
const NotificationTemplateListPage = lazy(() =>
  import('./features/masters/notificationTemplates/NotificationTemplateListPage').then((m) => ({ default: m.NotificationTemplateListPage })),
)
const ObClientDetailPage = lazy(() =>
  import('./features/onboarding/journey/clientDetail/ObClientDetailPage').then((m) => ({ default: m.ObClientDetailPage })),
)
const ObClientProductPage = lazy(() =>
  import('./features/onboarding/journey/clientDetail/ObClientProductPage').then((m) => ({ default: m.ObClientProductPage })),
)
const ObClientListPage = lazy(() =>
  import('./features/onboarding/clients/ObClientListPage').then((m) => ({ default: m.ObClientListPage })),
)
const ObDashboardPage = lazy(() =>
  import('./features/onboarding/dashboard/ObDashboardPage').then((m) => ({ default: m.ObDashboardPage })),
)
const NewObClientWizardPage = lazy(() =>
  import('./features/onboarding/clients/NewObClientWizardPage').then((m) => ({ default: m.NewObClientWizardPage })),
)
const ObNotificationCentrePage = lazy(() =>
  import('./features/onboarding/notifications/ObNotificationCentrePage').then((m) => ({ default: m.ObNotificationCentrePage })),
)
const ObImplementationStagePage = lazy(() =>
  import('./features/onboarding/implementationstages/ObImplementationStagePage').then((m) => ({
    default: m.ObImplementationStagePage,
  })),
)
const ObProductMasterPage = lazy(() =>
  import('./features/onboarding/products/ObProductMasterPage').then((m) => ({ default: m.ObProductMasterPage })),
)
const ObPrereqMasterPage = lazy(() =>
  import('./features/onboarding/prereqmaster/ObPrereqMasterPage').then((m) => ({ default: m.ObPrereqMasterPage })),
)
const ObReportViewerPage = lazy(() =>
  import('./features/onboarding/reports/ObReportViewerPage').then((m) => ({ default: m.ObReportViewerPage })),
)
const ObReportsHubPage = lazy(() =>
  import('./features/onboarding/reports/ObReportsHubPage').then((m) => ({ default: m.ObReportsHubPage })),
)
const ObTemplatesPage = lazy(() =>
  import('./features/onboarding/settings/ObTemplatesPage').then((m) => ({ default: m.ObTemplatesPage })),
)
const ObModuleAccessPage = lazy(() =>
  import('./features/onboarding/access/ObModuleAccessPage').then((m) => ({
    default: m.ObModuleAccessPage,
  })),
)
const ObSettingsPage = lazy(() =>
  import('./features/onboarding/settings/ObSettingsPage').then((m) => ({ default: m.ObSettingsPage })),
)
const PublicSignoffPage = lazy(() =>
  import('./features/onboarding/signoff/PublicSignoffPage').then((m) => ({ default: m.PublicSignoffPage })),
)
const PriorityListPage = lazy(() =>
  import('./features/masters/priorities/PriorityListPage').then((m) => ({ default: m.PriorityListPage })),
)
const PortalLoginPage = lazy(() =>
  import('./features/portal/auth/PortalLoginPage').then((m) => ({ default: m.PortalLoginPage })),
)
const PortalSetPasswordPage = lazy(() =>
  import('./features/portal/auth/PortalSetPasswordPage').then((m) => ({ default: m.PortalSetPasswordPage })),
)
const PortalModuleChooserPage = lazy(() =>
  import('./features/portal/PortalModuleChooserPage').then((m) => ({ default: m.PortalModuleChooserPage })),
)
const PortalShell = lazy(() =>
  import('./features/portal/PortalShell').then((m) => ({ default: m.PortalShell })),
)
const PortalOnboardingHomePage = lazy(() =>
  import('./features/portal/onboarding/PortalOnboardingHomePage').then((m) => ({
    default: m.PortalOnboardingHomePage,
  })),
)
const PortalPrereqTaskDetailPage = lazy(() =>
  import('./features/portal/onboarding/PortalPrereqTaskDetailPage').then((m) => ({
    default: m.PortalPrereqTaskDetailPage,
  })),
)
const PortalSignoffListPage = lazy(() =>
  import('./features/portal/onboarding/PortalSignoffListPage').then((m) => ({
    default: m.PortalSignoffListPage,
  })),
)
const PortalTicketListPage = lazy(() =>
  import('./features/portal/tickets/PortalTicketListPage').then((m) => ({
    default: m.PortalTicketListPage,
  })),
)
const PortalTicketDetailPage = lazy(() =>
  import('./features/portal/tickets/PortalTicketDetailPage').then((m) => ({
    default: m.PortalTicketDetailPage,
  })),
)
const ProjectDashboardPage = lazy(() =>
  import('./features/projects/ProjectDashboardPage').then((m) => ({ default: m.ProjectDashboardPage })),
)
const ProjectFormPage = lazy(() =>
  import('./features/masters/projects/ProjectFormPage').then((m) => ({ default: m.ProjectFormPage })),
)
const ProjectIndexPage = lazy(() =>
  import('./features/projects/ProjectIndexPage').then((m) => ({ default: m.ProjectIndexPage })),
)
const ProjectListPage = lazy(() =>
  import('./features/masters/projects/ProjectListPage').then((m) => ({ default: m.ProjectListPage })),
)
const ProjectSettingsPage = lazy(() =>
  import('./features/masters/projects/ProjectSettingsPage').then((m) => ({ default: m.ProjectSettingsPage })),
)
const ProjectTeamPage = lazy(() =>
  import('./features/masters/projects/ProjectTeamPage').then((m) => ({ default: m.ProjectTeamPage })),
)
const ReportViewerPage = lazy(() =>
  import('./features/reports/ReportViewerPage').then((m) => ({ default: m.ReportViewerPage })),
)
const ReportsHubPage = lazy(() =>
  import('./features/reports/ReportsHubPage').then((m) => ({ default: m.ReportsHubPage })),
)
const ResetPasswordPage = lazy(() =>
  import('./features/auth/ResetPasswordPage').then((m) => ({ default: m.ResetPasswordPage })),
)
const ResourceFormPage = lazy(() =>
  import('./features/masters/resources/ResourceFormPage').then((m) => ({ default: m.ResourceFormPage })),
)
const ResourceImportPage = lazy(() =>
  import('./features/imports/ResourceImportPage').then((m) => ({ default: m.ResourceImportPage })),
)
const ResourceListPage = lazy(() =>
  import('./features/masters/resources/ResourceListPage').then((m) => ({ default: m.ResourceListPage })),
)
const ResourceProfilePage = lazy(() =>
  import('./features/resources/ResourceProfilePage').then((m) => ({ default: m.ResourceProfilePage })),
)
const RoleListPage = lazy(() =>
  import('./features/masters/roles/RoleListPage').then((m) => ({ default: m.RoleListPage })),
)
const RolePermissionsPage = lazy(() =>
  import('./features/masters/roles/RolePermissionsPage').then((m) => ({ default: m.RolePermissionsPage })),
)
const ScheduledReportsPage = lazy(() =>
  import('./features/reports/ScheduledReportsPage').then((m) => ({ default: m.ScheduledReportsPage })),
)
const SettingsPage = lazy(() =>
  import('./features/settings/SettingsPage').then((m) => ({ default: m.SettingsPage })),
)
const SlaMatrixPage = lazy(() =>
  import('./features/masters/projects/SlaMatrixPage').then((m) => ({ default: m.SlaMatrixPage })),
)
const StageQueuePage = lazy(() =>
  import('./features/tickets/stage-queue/StageQueuePage').then((m) => ({ default: m.StageQueuePage })),
)
const StatusMasterPage = lazy(() =>
  import('./features/masters/statuses/StatusMasterPage').then((m) => ({ default: m.StatusMasterPage })),
)
const TaskTypeListPage = lazy(() =>
  import('./features/masters/taskTypes/TaskTypeListPage').then((m) => ({ default: m.TaskTypeListPage })),
)
const TicketDetailPage = lazy(() =>
  import('./features/tickets/detail/TicketDetailPage').then((m) => ({ default: m.TicketDetailPage })),
)
const TicketListPage = lazy(() =>
  import('./features/tickets/list/TicketListPage').then((m) => ({ default: m.TicketListPage })),
)
const TimesheetPage = lazy(() =>
  import('./features/masters/timesheet/TimesheetPage').then((m) => ({ default: m.TimesheetPage })),
)
const WorkflowDesignerPage = lazy(() =>
  import('./features/masters/designer/WorkflowDesignerPage').then((m) => ({ default: m.WorkflowDesignerPage })),
)
const WorkingCalendarPage = lazy(() =>
  import('./features/masters/calendar/WorkingCalendarPage').then((m) => ({ default: m.WorkingCalendarPage })),
)

/**
 * A-073 · every screen is loaded on demand, and the shell is not.
 *
 * `vite build` used to emit one 2,122 kB chunk because all 46 screens were
 * static imports here — so opening the dashboard downloaded, parsed and
 * executed the workflow designer, the Excel import wizard, the chat panel
 * and every master screen before the first frame. tools/perf/README.md
 * carries the measurement that found it.
 *
 * WHY THE SUSPENSE BOUNDARY IS PER ROUTE AND NOT AROUND <Routes>
 *
 * One boundary at the top would be less code and would have made the metric
 * lie. First contentful paint would then be the *fallback* — a spinner
 * painting in 200 ms while the user waits exactly as long as before, and a
 * budget met in the letter and broken in the intent.
 *
 * Boundaries sit inside AppShell's outlet instead, so `AppShell` stays a
 * static import and the first paint is the real chrome: sidebar, top bar,
 * project switcher. The page area is the only part that waits, which is
 * also the honest thing to show, because it is the only part not yet known.
 */
function RouteFallback() {
  return (
    <div className="p-8" role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">Loading screen</span>
    </div>
  )
}

function withSuspense(node: ReactNode) {
  return <Suspense fallback={<RouteFallback />}>{node}</Suspense>
}

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
        <Route path="/forgot-password" element={withSuspense(<ForgotPasswordPage />)} />
        <Route path="/reset-password" element={withSuspense(<ResetPasswordPage />)} />

        {/*
          OB-09 — B-115. Outside `RequireAuth` and outside the shell, and one
          level further out than the auth screens: those belong to somebody who
          has an account and has simply not signed in, whereas the reader here
          is a customer's SPOC who has none. The path carries no module prefix
          for the same reason the page carries no chrome — "nothing that hints
          at the rest of the application".
        */}
        <Route path="/signoff" element={withSuspense(<PublicSignoffPage />)} />

        {/*
          C-121 · CP-01..CP-04 — the client portal's own route subtree, plan
          §2.3: "Portal routes live in their own trees ... a CLIENT principal
          on any staff route → 404, and vice versa: the fork is at the route
          tree, not per-endpoint conditionals." This mirrors that fork on the
          client: entirely outside `RequireAuth` (which reads the *staff*
          session store) and outside `AppShell`, with its own guard reading
          its own store.

          `PortalAuthProvider` wraps the whole subtree, login included, rather
          than sitting above the router the way the staff `AuthProvider` does
          in `main.tsx` — mounting it only when a browser actually navigates
          under `/portal/**` means a staff page never mounts portal-only
          session state it has no reason to hold. `main.tsx` is Stream D's
          file and this task was not asked to widen it.

          A-130 issues a single access token with no refresh cycle, so this
          provider does not restore or renew anything (see its own note) —
          it only ends the session locally once that token's lifetime is up.
        */}
        <Route
          path="/portal"
          element={withSuspense(
            <PortalAuthProvider>
              <Outlet />
            </PortalAuthProvider>,
          )}
        >
          <Route path="login" element={withSuspense(<PortalLoginPage />)} />
          {/* CP-01's other half — the credential-link landing page,
              redemption only (see its own docstring on why there is no
              forced-change branch here). No `RequireAuth`: an unauthenticated
              visitor with `?token=` is the only caller. */}
          <Route path="set-password" element={withSuspense(<PortalSetPasswordPage />)} />

          <Route element={<PortalRequireAuth />}>
            {/* CP-02. Shell-less, like the staff launcher and for the same
                reason: a screen about picking a module framed by one
                module's chrome is a menu of dead ends. */}
            <Route path="choose" element={withSuspense(<PortalModuleChooserPage />)} />

            <Route element={withSuspense(<PortalShell />)}>
              {/* CP-03/CP-04. */}
              <Route path="onboarding" element={withSuspense(<PortalOnboardingHomePage />)} />
              <Route
                path="onboarding/prereq-tasks/:prereqTaskId"
                element={withSuspense(<PortalPrereqTaskDetailPage />)}
              />
              {/* CP-05 — C-122. Reads `/portal/onboarding/signoffs`, on the
                  same tree as CP-03/CP-04 since `ClientPrincipal.obClientId`
                  scopes it the same way. */}
              <Route path="onboarding/signoffs" element={withSuspense(<PortalSignoffListPage />)} />

              {/* CP-06/CP-07 — C-122. The chooser's Ticketing card has linked
                  here since C-121; this is the task that resolves it. */}
              <Route path="tickets" element={withSuspense(<PortalTicketListPage />)} />
              <Route path="tickets/:ticketId" element={withSuspense(<PortalTicketDetailPage />)} />
            </Route>
          </Route>

          <Route index element={<Navigate to="/portal/choose" replace />} />
          <Route path="*" element={<Navigate to="/portal/login" replace />} />
        </Route>

        <Route element={<RequireAuth />}>
          {/*
            S-03 is authenticated but deliberately shell-less. A-026 closes every
            other route until the password is changed, so a sidebar full of links
            that all redirect back here would be a menu of dead ends.
          */}
          <Route path="/change-password" element={withSuspense(<ChangePasswordPage />)} />

          {/*
            A-116 · OB-01, the module launcher. Shell-less for S-03's reason,
            one line above: a screen about choosing a module, framed by a
            sidebar full of one module's links, is a menu of dead ends.

            Eager rather than lazy, for A-073's reason: it is a *landing*
            route — LandingRoutes.forUser sends every dual-module user here the
            moment they sign in — so deferring it would put a round trip in
            front of the first screen those users ever see.
          */}
          <Route path="/launcher" element={<LauncherPage />} />

          <Route element={<AppShell />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/my-tasks" element={withSuspense(<MyTasksPage />)} />
            {/*
              C-062 · S-31, the QA and Deployment landing page. `LandingRoutes`
              on the server has mapped those two roles here since A-018 and
              carried a note that the route did not exist yet; this is the day
              that note describes, and nothing changes on the server side.
            */}
            <Route path="/stages/queue" element={withSuspense(<StageQueuePage />)} />
            <Route path="/tickets" element={withSuspense(<TicketListPage />)} />
            {/* Ahead of `/tickets/:ticketId` for readability; React Router ranks
                the static segment higher regardless of order. */}
            <Route path="/tickets/new" element={withSuspense(<CreateTicketPage />)} />
            {/*
              S-24, the bulk reassignment wizard — C-063. B-014 declared this
              route as a placeholder because the Resource Master links into it —
              deactivating somebody who holds open tickets sends the admin here
              with `?fromUserId=…&returnTo=…`, per
              `features/masters/resources/reassignHandoff.ts` — and C-063 is
              what replaces the placeholder with the real screen.
            */}
            <Route path="/tickets/bulk-reassign" element={withSuspense(<BulkReassignWizardPage />)} />
            <Route path={TICKET_ROUTE} element={withSuspense(<TicketDetailPage />)} />
            {/* A-077 · the index the sidebar leads to. Not Stream B's project
                master, which stays at /masters/projects and owns every write —
                this lists projects and opens their dashboards, nothing else. */}
            <Route path="/projects" element={withSuspense(<ProjectIndexPage />)} />
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
            <Route path={PROJECT_ROUTE} element={withSuspense(<ProjectDashboardPage />)} />
            {/* B-066 · the third and last of the three placeholders replaced.
                Every client name on every ticket already linked here. */}
            <Route path={CLIENT_ROUTE} element={withSuspense(<ClientProfilePage />)} />
            {/* A-069 · S-28. C-019 registered this pattern against a placeholder
                so every assignee name in the product already linked here. */}
            <Route path={RESOURCE_ROUTE} element={withSuspense(<ResourceProfilePage />)} />
            {/* D-065 · S-25. The placeholder this replaces was the last thing
                standing between a finished chat engine and anybody being able
                to use it — D-050 through D-057 have all been merged for days. */}
            <Route path="/chat" element={withSuspense(<ChatPage />)} />
            {/*
              A-063 · the hub and its viewer. The viewer is a nested path rather
              than a modal because a filtered report is a URL people send to
              each other and bookmark — which is also why its filters live in
              the query string.
            */}
            <Route path="/reports" element={withSuspense(<ReportsHubPage />)} />
            {/* A-065 · before the :reportKey route, or "schedules" is read as a report key. */}
            <Route path="/reports/schedules" element={withSuspense(<ScheduledReportsPage />)} />
            <Route path="/reports/:reportKey" element={withSuspense(<ReportViewerPage />)} />
            {/*
              A-071 · S-16. A top-level path rather than `/masters/audit-logs`:
              this is not a master anybody edits, and `audit.view` is a
              different capability from `master.write` — the two screens are
              reached by different people for different reasons. `RequireAuth`
              covers signing in; the Admin-only part is enforced by the server,
              which answers 403, and the page says so rather than pretending the
              screen does not exist.
            */}
            <Route path="/audit-logs" element={withSuspense(<AuditLogPage />)} />
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
            <Route path="/timesheet" element={withSuspense(<TimesheetPage />)} />
            <Route path="/timesheet/:userId" element={withSuspense(<TimesheetPage />)} />
            {/* B-067 · the index the sidebar's Masters entry has led to since
                A-030 and never actually reached until now. Permission-filtered
                per-card rather than gated as a whole page — `MastersIndexPage`'s
                own note has the per-master @PreAuthorize audit that decided
                which one (notification templates) needed it. */}
            <Route path="/masters" element={withSuspense(<MastersIndexPage />)} />
            <Route path="/masters/resources" element={withSuspense(<ResourceListPage />)} />
            {/* `/new` before `/:userId/edit` so the literal wins — otherwise
                "new" is matched as a userId and the form loads resource NaN.
                Inside `RequireAuth` and inside the shell, like every other
                master screen: the S-08 form is an Admin screen, not one of
                A-030's four shell-less auth routes. */}
            <Route path="/masters/resources/new" element={withSuspense(<ResourceFormPage />)} />
            {/* B-038 · S-07's "bulk import via CSV", on §4B.3's wizard. Literal
                before `/:userId/edit` for the reason above, and deliberately the
                same shape of path as `/masters/clients/import`: it is the same
                screen, registered twice, and two differently-shaped URLs would be
                the first place that stopped being obvious. */}
            <Route path="/masters/resources/import" element={withSuspense(<ResourceImportPage />)} />
            <Route path="/masters/resources/:userId/edit" element={withSuspense(<ResourceFormPage />)} />
            {/* B-016 · S-10. `/new` before `/:projectId/edit` for the same
                reason the resource routes give — otherwise "new" matches as a
                projectId and the form loads project NaN. `/projects/:id` (the
                project *dashboard*, Stream A's A-069) is a different screen at
                a different path and is untouched. */}
            <Route path="/masters/projects" element={withSuspense(<ProjectListPage />)} />
            <Route path="/masters/projects/new" element={withSuspense(<ProjectFormPage />)} />
            <Route path="/masters/projects/:projectId/edit" element={withSuspense(<ProjectFormPage />)} />
            {/* B-017 · S-10's Team tab. A sibling route rather than a nested
                one: the two tabs own their own data, and a layout route would
                make Team inherit General's read — which fetches the `ETag` its
                `PATCH` needs and this screen never sends. */}
            <Route path="/masters/projects/:projectId/team" element={withSuspense(<ProjectTeamPage />)} />
            {/* B-018 · S-10's SLA tab, a sibling for the same reason — and a
                sharper one: it needs its own `ETag`, over the matrix rather
                than over the project, so no shared parent read could serve
                both tabs anyway. */}
            <Route path="/masters/projects/:projectId/sla" element={withSuspense(<SlaMatrixPage />)} />
            {/* B-019 · S-10's Settings tab, and the fourth sibling. Same
                reasoning again, and it has its own `ETag` too — over a document
                spanning `projects` and `project_task_types`, which no read of
                the project alone could tag. Note it is `/masters/projects/:id/settings`
                and not the app-wide `/settings` two lines below; they are
                different screens and the path prefix is what keeps them
                apart. */}
            <Route path="/masters/projects/:projectId/settings" element={withSuspense(<ProjectSettingsPage />)} />
            <Route path="/masters/roles" element={withSuspense(<RoleListPage />)} />
            <Route path="/masters/roles/:roleId" element={withSuspense(<RolePermissionsPage />)} />
            {/* B-021 · S-12. One route, not two: a level is six fields, so the
                create and edit forms are dialogs on the grid rather than a page
                each — the shape B-020 gave S-11. There is no `/:id` route to
                collide with. */}
            <Route path="/masters/priorities" element={withSuspense(<PriorityListPage />)} />
            {/* B-020 · S-11. One route, not two: a task type is eight fields,
                so the create and edit forms are dialogs on the grid rather
                than a page each. There is no `/:id` route to collide with. */}
            <Route path="/masters/task-types" element={withSuspense(<TaskTypeListPage />)} />
            {/*
              S-13, B-039 builds tab 1. `/masters/statuses` rather than
              `/masters/workflow`, because the tab an Admin lands on is the status
              list and B-040/B-041 add tabs to this page rather than routes beside
              it — and the template designer got its own route (S-30), below, which
              is what that arrangement was leaving room for.
            */}
            <Route path="/masters/statuses" element={withSuspense(<StatusMasterPage />)} />
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
              element={withSuspense(<WorkflowDesignerPage />)}
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
              element={withSuspense(<JourneyTemplateDesignerPage />)}
            />
            {/*
              C-123 · the Module Service catalogue — one card per product,
              feeding the designer route above. Same "no onboarding nav yet"
              situation as that route: reached by a direct link until this
              module gets one.
            */}
            <Route
              path="/onboarding/journey-templates"
              element={withSuspense(<ModuleServiceCataloguePage />)}
            />
            {/*
              B-112 · OB-13's full page. Beside the designer route above and for
              the same reason it is not under `/masters/**` — the Onboarding
              module's screens are disjoint from the ticketing masters (plan
              §1.2), and this list is emphatically not S-26's: separate store,
              separate tabs, separate event catalogue.

              Routed now rather than with the onboarding shell, because it is
              what B-114's daily digest links at. PHASE-2-BUILD-PLAN.md §73
              asked for the page for precisely that reason — "a full page is
              needed for history and for the digest links to land somewhere" —
              and a destination that arrives after the mail pointing at it is a
              mail with a broken link. B-109 has since built the nav section —
              see `Sidebar.tsx` — but the bell popover mounting on it is a
              separate task and has not landed.
            */}
            <Route path="/onboarding/notifications" element={withSuspense(<ObNotificationCentrePage />)} />
            {/* OB-11 — B-113. OB Admin only; the server answers 403 and the page
                renders that rather than a blank form. */}
            <Route path="/onboarding/settings" element={withSuspense(<ObSettingsPage />)} />
            {/*
              B-124 · OB-14, the prerequisites master. Beside the other
              administration routes and outside `/masters/**` for the reason
              they all state — the Onboarding module's screens are disjoint
              from the ticketing masters (plan §1.2). This is the org-wide
              checklist OB-04's boarding snapshots from; the per-client
              instance lives on the client detail page (C-110), not here.
              Sidebar.tsx's Administration section gains its row in the same
              task — the comment there has named this screen's absence since
              A-129.
            */}
            <Route path="/onboarding/prereq-master" element={withSuspense(<ObPrereqMasterPage />)} />
            {/*
              OB-15 · the implementation stage master. Beside the other
              Administration routes and outside `/masters/**` for the reason
              every one of them states — the Onboarding module's screens are
              disjoint from the ticketing masters (plan §1.2). Sidebar.tsx's
              Administration section gains its row in the same task, which is
              the rule that section's own comment sets: a screen is absent from
              the nav until it exists, and appears there the day it does.
            */}
            <Route
              path="/onboarding/implementation-stages"
              element={withSuspense(<ObImplementationStagePage />)}
            />
            {/*
              OB-07 · the Products master — the catalogue Module Services are
              written for and clients buy. Beside the other Administration
              routes and outside `/masters/**` for the reason every one of
              them states: the Onboarding module's screens are disjoint from
              the ticketing masters (plan §1.2). Creating and editing are OB
              Admin writes; `ObModuleRoleRules` refuses everyone else.
            */}
            <Route path="/onboarding/products" element={withSuspense(<ObProductMasterPage />)} />
            {/*
              A-117 · OB-08. Its own route rather than a tab on `/onboarding/settings`:
              that screen configures the module's behaviour (thresholds, the escalation
              ladder), this one decides who may open it at all. They are refused to
              different people for different reasons and belong in the Administration
              list as two entries, which is where the design puts them.
            */}
            <Route path="/onboarding/module-access" element={withSuspense(<ObModuleAccessPage />)} />
            {/* OB-12 — B-113. OB Admin only, same as OB-11. */}
            <Route path="/onboarding/templates" element={withSuspense(<ObTemplatesPage />)} />
            {/*
              B-122 · OB-10's two routes, beside the notification centre and for
              the same reason the designer and that page are not under
              `/masters/**`: the Onboarding module's screens are disjoint from
              the ticketing masters (plan §1.2), and this hub is emphatically
              not S-27's. Separate catalogue, separate categories, separate
              column vocabulary — a shared `/reports` prefix would put twelve
              onboarding cards into a grid of eighteen ticketing ones with
              nothing to tell them apart.

              Two routes rather than one, because the viewer is the thing people
              bookmark and send: its filters live in the URL, so a report
              narrowed to one product and a quarter has to be a path somebody
              can paste. Reached by link rather than from the sidebar — B-109's
              nav entry points at the client list, not at reports.
            */}
            <Route path="/onboarding/reports" element={withSuspense(<ObReportsHubPage />)} />
            <Route path="/onboarding/reports/:reportKey" element={withSuspense(<ObReportViewerPage />)} />
            <Route path="/masters/calendar" element={withSuspense(<WorkingCalendarPage />)} />
            {/*
              B-121 · OB-02, the onboarding dashboard. Beside the four routes
              above and outside `/masters/**` for the same reason — the
              Onboarding module's screens are disjoint from the ticketing
              masters (plan §1.2), and this board is emphatically not S-05's:
              its own summary tables, its own vocabulary, and A-115's ArchUnit
              rule refusing the import between them.

              B-109 adds the sidebar entry this comment used to say was
              missing — see `Sidebar.tsx`.
            */}
            <Route path="/onboarding/dashboard" element={withSuspense(<ObDashboardPage />)} />
            {/*
              C-110 · OB-05, the onboarding client detail page. Beside the other
              `/onboarding/**` routes and outside `/masters/**` for the reason
              they all state — the module's screens are disjoint from the
              ticketing masters (plan §1.2), and this page is emphatically not
              S-09's client profile: a different client table with no foreign
              key to that one (plan §1.2 again), a different vocabulary, and
              A-115's ArchUnit rule refusing the import between them.

              The path is `/onboarding/clients/:obClientId` because B-108's
              `ObMailLinks` already sends every onboarding mail there — the
              route arriving after the mail pointing at it was the exact
              situation B-112 flagged, and this is the destination those links
              have been missing. The page's own code lives under
              `features/onboarding/journey/` rather than `.../clients/`; see its
              docstring for why the directory and the URL differ.

              Reached from a mail link, the OB-03 list (B-108) or the sidebar
              (B-109) — this page has no route of its own to link out to.
            */}
            {/*
              B-108 · OB-03, the onboarding client list. Registered *before*
              `/onboarding/clients/:obClientId` for readability only — React
              Router ranks a literal path above one with a variable segment
              regardless of order, the same ranking `/masters/clients/new`
              already depends on. A bare `/onboarding/clients` cannot be
              swallowed by the detail route anyway: `:obClientId` requires a
              segment to bind.

              This is the screen the four routes above have each said they were
              waiting for — "reached by direct link until B-108/B-109 build
              one" — and it is now also `Sidebar.tsx`'s "Onboarding" entry's
              destination: a client roster is the module's natural landing
              screen, the same call `/tickets` makes for the ticketing side.
            */}
            <Route path="/onboarding/clients" element={withSuspense(<ObClientListPage />)} />
            {/*
              B-109 · OB-04, the four-step new client wizard. Registered
              *before* `/onboarding/clients/:obClientId` for the identical
              reason `/onboarding/clients` is — a literal path outranks a
              parameterised one regardless of order, but readability still
              wants the concrete route next to the id-bearing one it resembles.
              `ObClientListPage`'s "New client" button is the only link to it;
              nothing 404s any more.
            */}
            <Route path="/onboarding/clients/new" element={withSuspense(<NewObClientWizardPage />)} />
            <Route
              path="/onboarding/clients/:obClientId"
              element={withSuspense(<ObClientDetailPage />)}
            />
            {/*
              OB-05's second half — one purchased product of one client, with
              that product's journey ribbons and nothing else's.

              A route rather than state on the page above, because a product is
              a place people send each other: "look at KV Varanasi's biometric
              rollout" has to be a URL. Nested under the client for the reason
              the path reads — the product id means nothing without the client,
              the page reads the client document to resolve it, and a browser
              Back out of a step panel then lands on the client rather than on
              the list.

              `products` is a literal segment inside a parameterised one, which
              cannot collide with anything: `:obClientId` binds one segment and
              the client route has no children of its own.
            */}
            <Route
              path="/onboarding/clients/:obClientId/products/:productId"
              element={withSuspense(<ObClientProductPage />)}
            />
            {/* B-022 · S-15. One route, like S-11 and S-12: a template is six
                fields, so create and edit are dialogs on the grid rather than a
                page each. There is no `/:id` route to collide with. */}
            <Route
              path="/masters/notification-templates"
              element={withSuspense(<NotificationTemplateListPage />)}
            />
            {/* B-025 · S-32. Under `/masters` because it is a master screen,
                while `/clients/:clientId` two routes up is the client 360 — a
                different screen at a different path, kept apart by the prefix
                the way `/masters/projects` and `/projects/:id` already are.
                The create/edit form is B-026's, on the two routes below. */}
            <Route path="/masters/clients" element={withSuspense(<ClientListPage />)} />
            {/* B-026 · S-33. `/new` before `/:clientId/edit` for readability
                only — React Router ranks the literal segment above the variable
                regardless of order, the same ranking `/tickets/new` and
                `/masters/resources/new` already depend on. One component serves
                both: they are one form, and two would be the same file twice
                with one copy always slightly behind. */}
            <Route path="/masters/clients/new" element={withSuspense(<ClientFormPage />)} />
            {/* B-031 · S-34, the Excel import wizard. A literal segment beside
                `/new`, and the same ranking applies: `:clientId` never swallows
                it. B-038 moved the component out of `features/clients/` and made
                it configurable; this route is one of its two registrations and
                the path is unchanged. */}
            <Route path="/masters/clients/import" element={withSuspense(<ClientImportPage />)} />
            <Route path="/masters/clients/:clientId/edit" element={withSuspense(<ClientFormPage />)} />
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
            <Route path="/settings" element={withSuspense(<SettingsPage />)} />
            <Route path="*" element={<ScreenPlaceholder title="Not found" />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
