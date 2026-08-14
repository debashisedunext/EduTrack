import * as React from 'react'
import { useParams } from 'react-router-dom'

import type { ProjectSettings } from '@/api/generated/model/projectSettings'
import type { ProjectSettingsTaskType } from '@/api/generated/model/projectSettingsTaskType'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import { FormField } from '../resources/FormField'

import { ProjectTabs } from './ProjectTabs'
import {
  allowListSummary,
  AUTO_ASSIGN_RULES,
  draftFor,
  isDirty,
  isUnrestricted,
  retiredAllowed,
  TICKET_FIELDS,
  toWriteRequest,
  toggle,
  type SettingsDraft,
} from './projectSettings'
import { useProjectSettings, useReplaceProjectSettings } from './projectSettingsQueries'

/**
 * S-10 Project Master — the Settings tab. B-019.
 *
 * "Allowed task types, mandatory fields, auto-assign rule (round-robin /
 * least-loaded / manual)" — three settings behind one wholesale `PUT`.
 *
 * <h2>The empty allow-list, and why this screen talks</h2>
 *
 * Eleven unticked checkboxes read as "nothing may be raised on this project".
 * They mean the opposite: **no allow-list at all, so every active task type is
 * permitted.** That is the state every project is in, because the table behind
 * it did not exist before this task, and it is the state a project returns to
 * when the last box is unticked — a project that permitted no task type could
 * raise no ticket, so there is nothing else the request could sensibly mean.
 *
 * No arrangement of checkboxes says that on its own, so the screen says it in a
 * sentence and keeps saying it while the list is empty. There is deliberately no
 * separate "remove the restriction" control: two controls for one outcome is how
 * they end up disagreeing, which is the call the SLA tab makes about a cleared
 * cell.
 *
 * <h2>A Save button, like the SLA tab and unlike the Team tab</h2>
 *
 * B-017 saves on change because each edit there is one `PATCH` of one field.
 * This is a replace of a whole document, so save-on-change would mean a replace
 * per keystroke of a checkbox list, each racing the last. One button, one
 * `If-Match`, one transaction.
 *
 * <h2>What is deliberately not here</h2>
 *
 * - **Any enforcement.** This screen stores configuration; the create form is
 *   the thing that has to obey it, and that is `api/feature/tickets` and
 *   `CreateTicketPage` — **Stream C's**. Until they consume it, these settings
 *   are recorded and not applied. Said out loud in the README and the backlog
 *   rather than left to be discovered.
 * - **A read-only mode for non-Admins.** The write is `project.manage`, which
 *   Admin and PM hold, and the frontend still has no capability gate to hang
 *   that on — `/me` carries `permissions[]` and nothing reads it. A Developer
 *   sees the controls and meets a `403` in the banner. **Flagged for Stream A**,
 *   as B-018 flagged it first; refusing to render from a hardcoded role check is
 *   what B-015 removed from `ResourceController` and is not the fix.
 */
export function ProjectSettingsPage() {
  const params = useParams<{ projectId?: string }>()
  const projectId = params.projectId ? Number(params.projectId) : null

  const { data, isPending, isError } = useProjectSettings(projectId)

  if (projectId == null) {
    return <p className="p-6 text-sm text-danger-text">No project selected.</p>
  }

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 p-6">
      <ProjectTabs active="Settings" />

      {isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">This project’s settings could not be loaded.</p>
      ) : (
        <SettingsForm
          projectId={projectId}
          settings={data.settings}
          etag={data.etag}
          // Remounts the form when the server's document changes underneath it
          // — after a save, or after the Task Type Master retires a type. The
          // draft is derived from `restrictsTaskTypes`, so keeping it across a
          // change to that flag is how an unrestricted project acquires an
          // allow-list nobody asked for.
          key={data.etag ?? 'no-etag'}
        />
      )}
    </div>
  )
}

function SettingsForm({
  projectId,
  settings,
  etag,
}: {
  projectId: number
  settings: ProjectSettings
  etag: string | null
}) {
  const [draft, setDraft] = React.useState<SettingsDraft>(() => draftFor(settings))
  const [bannerError, setBannerError] = React.useState<string | null>(null)

  const replace = useReplaceProjectSettings(projectId)
  const dirty = isDirty(settings, draft)
  const retired = retiredAllowed(settings.taskTypes)

  function save() {
    setBannerError(null)
    replace.mutate(
      { body: toWriteRequest(settings, draft), etag },
      {
        onSuccess: (saved) =>
          toast({
            title: saved.settings.restrictsTaskTypes
              ? 'Settings saved'
              : 'Settings saved — every task type may be raised',
          }),
        onError: (error: ApiError) =>
          setBannerError(
            // A 412 is the one refusal with a specific next step, and the
            // remount on the refetched tag is what makes "reload" work.
            error.status === 412
              ? 'Somebody else changed these settings while you were editing them. Reload the page and reapply your changes.'
              : Object.values(error.fieldErrors)[0]?.[0]
                ?? error.problem.detail
                ?? 'These settings could not be saved.',
          ),
      },
    )
  }

  return (
    <>
      {bannerError ? (
        <div
          role="alert"
          className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          {bannerError}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-end gap-3">
        <Button type="button" onClick={save} disabled={!dirty || replace.isPending}>
          {replace.isPending ? 'Saving…' : dirty ? 'Save settings' : 'Saved'}
        </Button>
      </div>

      {/* ── auto-assign ─────────────────────────────────────────────────── */}
      <section className="flex flex-col gap-2">
        <h2 className="text-sm font-semibold text-content">Auto-assign rule</h2>
        <FormField
          id="autoAssignRule"
          label="When a new ticket names no assignee"
          hint={AUTO_ASSIGN_RULES.find((r) => r.value === draft.autoAssignRule)?.hint}
        >
          {(aria) => (
            <Select
              value={draft.autoAssignRule}
              onValueChange={(value) =>
                setDraft((d) => ({ ...d, autoAssignRule: value as SettingsDraft['autoAssignRule'] }))
              }
              disabled={replace.isPending}
            >
              <SelectTrigger {...aria} className="max-w-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {AUTO_ASSIGN_RULES.map((rule) => (
                  <SelectItem key={rule.value} value={rule.value}>
                    {rule.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </FormField>
      </section>

      {/* ── allowed task types ──────────────────────────────────────────── */}
      <section className="flex flex-col gap-2">
        <h2 className="text-sm font-semibold text-content">Allowed task types</h2>

        {/*
          The sentence the screen owes the user. An empty list is not a
          restriction that permits nothing — it is no restriction at all, and
          nothing about eleven unticked boxes says so.
        */}
        <p
          className={
            isUnrestricted(draft) ? 'text-sm text-content-muted' : 'text-sm text-content'
          }
        >
          {allowListSummary(draft, settings.taskTypes)}
        </p>
        {isUnrestricted(draft) ? (
          <p className="text-caption text-content-muted">
            Tick one or more to restrict this project to them. Unticking them all removes the
            restriction again — there is no way to allow none, because a project that permitted no
            task type could raise no ticket.
          </p>
        ) : null}

        <ul className="grid gap-1 sm:grid-cols-2">
          {settings.taskTypes.filter((t) => t.isActive).map((taskType) => (
            <TaskTypeCheckbox
              key={taskType.taskTypeId}
              taskType={taskType}
              checked={draft.allowedTaskTypeIds.has(taskType.taskTypeId)}
              disabled={replace.isPending}
              onChange={(next) =>
                setDraft((d) => ({
                  ...d,
                  allowedTaskTypeIds: toggle(d.allowedTaskTypeIds, taskType.taskTypeId, next),
                }))
              }
            />
          ))}
        </ul>

        {/*
          Retired types this project still allows. Rendered because the PUT is
          assembled from these rows: one that was allowed and not shown would be
          dropped by the next save through a screen that never displayed it.
          Labelled, because it cannot be raised on a new ticket whatever this
          screen says.
        */}
        {retired.length > 0 ? (
          <div className="flex flex-col gap-1 border-t border-border pt-2">
            <p className="text-caption text-content-muted">
              Retired in the Task Type Master. No new ticket can use these; untick to remove them
              from this project’s list.
            </p>
            <ul className="grid gap-1 sm:grid-cols-2">
              {retired.map((taskType) => (
                <TaskTypeCheckbox
                  key={taskType.taskTypeId}
                  taskType={taskType}
                  checked={draft.allowedTaskTypeIds.has(taskType.taskTypeId)}
                  disabled={replace.isPending}
                  onChange={(next) =>
                    setDraft((d) => ({
                      ...d,
                      allowedTaskTypeIds: toggle(d.allowedTaskTypeIds, taskType.taskTypeId, next),
                    }))
                  }
                />
              ))}
            </ul>
          </div>
        ) : null}
      </section>

      {/* ── mandatory fields ────────────────────────────────────────────── */}
      <section className="flex flex-col gap-2">
        <h2 className="text-sm font-semibold text-content">Mandatory fields</h2>
        <p className="text-sm text-content-muted">
          {draft.mandatoryFields.size === 0
            ? 'A new ticket requires only project, title, task type and level.'
            : `${draft.mandatoryFields.size} extra ${
                draft.mandatoryFields.size === 1 ? 'field is' : 'fields are'
              } required on a new ticket here.`}
        </p>
        {/*
          Project, title, task type and level are absent by design — they are
          required of every ticket already, so a checkbox for one could not
          change any outcome, and a control that cannot do what it appears to do
          is worse than a missing one.
        */}
        <ul className="grid gap-1 sm:grid-cols-2">
          {TICKET_FIELDS.map((field) => {
            const id = `mandatory-${field.value}`
            return (
              <li key={field.value} className="flex items-start gap-2 py-1">
                <input
                  id={id}
                  type="checkbox"
                  checked={draft.mandatoryFields.has(field.value)}
                  disabled={replace.isPending}
                  onChange={(e) =>
                    setDraft((d) => ({
                      ...d,
                      mandatoryFields: toggle(d.mandatoryFields, field.value, e.target.checked),
                    }))
                  }
                  aria-describedby={field.hint ? `${id}-hint` : undefined}
                  className="mt-1 h-4 w-4 rounded-control border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:opacity-40"
                />
                <label htmlFor={id} className="flex flex-col text-sm text-content">
                  {field.label}
                  {field.hint ? (
                    <span id={`${id}-hint`} className="text-caption text-content-muted">
                      {field.hint}
                    </span>
                  ) : null}
                </label>
              </li>
            )
          })}
        </ul>
      </section>
    </>
  )
}

// ---------------------------------------------------------------------------
// one task type
// ---------------------------------------------------------------------------

function TaskTypeCheckbox({
  taskType,
  checked,
  disabled,
  onChange,
}: {
  taskType: ProjectSettingsTaskType
  checked: boolean
  disabled: boolean
  onChange: (next: boolean) => void
}) {
  const id = `task-type-${taskType.taskTypeId}`

  return (
    <li className="flex items-center gap-2 py-1">
      <input
        id={id}
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 rounded-control border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:opacity-40"
      />
      <label htmlFor={id} className="flex items-center gap-2 text-sm text-content">
        {taskType.name}
        {taskType.isActive ? null : <Chip variant="warning">retired</Chip>}
      </label>
    </li>
  )
}
