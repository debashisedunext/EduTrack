import * as React from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Controller, useForm, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft } from 'lucide-react'

import { useListProjects } from '@/api/generated/projects/projects'
import { useListUsers } from '@/api/generated/users/users'
import { ApiError, newIdempotencyKey } from '@/api/http'
import type { RoleCode } from '@/api/generated/model/roleCode'
import type { User } from '@/api/generated/model/user'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { toast } from '@/components/ui/use-toast'

import { WeeklyOffPicker } from '../calendar/WeeklyOffPicker'
import type { IsoDay } from '../calendar/workingWeek'

import { ROLE_LABEL } from './columns'
import { FieldGroup, FormField, ReadOnlyField } from './FormField'
import { SkillsInput } from './SkillsInput'
import { ProjectAssignmentsEditor } from './ProjectAssignmentsEditor'
import { TemporaryPasswordDialog } from './TemporaryPasswordDialog'
import { useCreateResource, useResource, useUpdateResource } from './resourceQueries'
import {
  emptyResourceForm,
  resourceFormSchema,
  splitFieldErrors,
  toFormValues,
  toWriteRequest,
  type ResourceFormValues,
} from './resourceForm'

const ROLES = Object.keys(ROLE_LABEL) as RoleCode[]

/**
 * S-08 Resource Master — Create / Edit (B-011).
 *
 * One page for both, because they are one form: every field, validation and
 * section is shared, and the only differences are the verb, whether an `ETag`
 * is in play, and whether a password comes back. Two components would be the
 * same file twice with one of the copies always slightly behind.
 *
 * <h2>What is deliberately not here</h2>
 *
 * - **Reporting-manager cycle detection beyond self-reference (B-012).** The
 *   manager picker excludes only the resource being edited. A→B→C→A is still
 *   expressible and the server still accepts it.
 * - **The bulk reassignment wizard (B-014).** Deactivating somebody who holds
 *   open tickets is refused with a count, exactly as the grid refuses it.
 * - **Profile photo upload.** S-08 says "Profile photo"; this takes a URL,
 *   because attachment storage is A-016 and there is nothing to upload to yet.
 *   The column and the field are ready for it.
 */
export function ResourceFormPage() {
  const navigate = useNavigate()
  const params = useParams<{ userId?: string }>()

  const userId = params.userId ? Number(params.userId) : null
  const isEdit = userId != null

  const { data: loaded, isPending: loadingResource, isError: loadFailed, error: loadError } = useResource(userId)
  const { data: projectsData } = useListProjects({ isActive: true, limit: 200 })
  const projects = React.useMemo(() => projectsData?.data ?? [], [projectsData])

  // Every active resource is a candidate manager. Unfiltered by anything on
  // this form: filtering the manager list by the department being typed would
  // hide the manager somebody is trying to pick.
  const { data: managersData } = useListUsers({ isActive: true, limit: 200 })
  const managers = React.useMemo(
    // Self-reference is blocked here as well as at the server and the database
    // trigger. Offering somebody as their own manager and then refusing the
    // save is a worse experience than not offering it.
    () => (managersData?.data ?? []).filter((u) => u.id !== userId),
    [managersData, userId],
  )

  const [createdPassword, setCreatedPassword] = React.useState<string | null>(null)
  const [createdName, setCreatedName] = React.useState('')
  const [bannerError, setBannerError] = React.useState<string | null>(null)

  const form = useForm<ResourceFormValues>({
    resolver: zodResolver(resourceFormSchema) as Resolver<ResourceFormValues>,
    defaultValues: emptyResourceForm,
    mode: 'onBlur',
  })

  // Seeded once the read lands. `reset` rather than `defaultValues` because the
  // form mounts before the fetch resolves, and re-mounting the whole page on
  // load would throw away focus and scroll position.
  const seeded = React.useRef(false)
  React.useEffect(() => {
    if (loaded && !seeded.current) {
      seeded.current = true
      form.reset(toFormValues(loaded.resource))
    }
  }, [loaded, form])

  const createResource = useCreateResource()
  const updateResource = useUpdateResource()
  const isSaving = createResource.isPending || updateResource.isPending

  /**
   * Generated once per mounted form, not per attempt.
   *
   * `http.ts` is explicit about this: TanStack Query re-invokes the mutation
   * function on retry, so a key created inside it changes every time and
   * defends against nothing.
   */
  const idempotencyKey = React.useRef(newIdempotencyKey())

  function applyServerErrors(error: unknown): void {
    if (!(error instanceof ApiError)) {
      setBannerError('Something went wrong. Try again.')
      return
    }

    const { fields, unmatched } = splitFieldErrors(error.fieldErrors)
    fields.forEach(({ name, message }) => form.setError(name, { type: 'server', message }))

    if (fields.length > 0) {
      // Focus the first one. A 409 on a long form otherwise scrolls nowhere and
      // the message is below the fold.
      form.setFocus(fields[0].name)
    }

    // A 412 has no field errors and is not the user's mistake — it needs its
    // own sentence, because "reload and try again" is the only useful advice
    // and no field can carry it.
    if (error.status === 412) {
      setBannerError('Somebody else changed this resource while you were editing. Reload to see their changes, then reapply yours.')
      return
    }
    if (error.status === 428) {
      setBannerError('This form lost track of the version it loaded. Reload and try again.')
      return
    }
    if (fields.length === 0) {
      setBannerError(unmatched[0] ?? error.problem.detail ?? error.problem.title)
    } else if (unmatched.length > 0) {
      setBannerError(unmatched.join(' '))
    }
  }

  const onSubmit = form.handleSubmit(async (values) => {
    setBannerError(null)
    const data = toWriteRequest(values)

    try {
      if (isEdit) {
        await updateResource.mutateAsync({ userId, data, etag: loaded?.etag ?? null })
        toast({ variant: 'success', title: `${values.displayName} updated` })
        navigate('/masters/resources')
        return
      }

      const created = await createResource.mutateAsync({ data, idempotencyKey: idempotencyKey.current })
      // The dialog holds the page open. Navigating away first would unmount the
      // only place the password is readable.
      setCreatedName(created.resource.displayName)
      setCreatedPassword(created.temporaryPassword)
    } catch (error) {
      applyServerErrors(error)
    }
  })

  if (isEdit && loadingResource) {
    return (
      <div className="flex flex-col gap-4 p-6">
        <Skeleton className="h-8 w-64" />
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-48 w-full" />
        ))}
      </div>
    )
  }

  if (isEdit && loadFailed) {
    return (
      <div className="p-6">
        <EmptyState
          title={loadError instanceof ApiError && loadError.status === 404
            ? 'No such resource'
            : 'Could not load this resource'}
          description={
            loadError instanceof ApiError && loadError.status === 404
              ? 'It may have been removed since the list was loaded.'
              : 'Something went wrong. Try again.'
          }
          action={
            <Button size="sm" onClick={() => navigate('/masters/resources')}>
              Back to resources
            </Button>
          }
        />
      </div>
    )
  }

  const errors = form.formState.errors

  return (
    <form onSubmit={onSubmit} className="flex h-full flex-col gap-4 overflow-y-auto p-6" noValidate>
      {/* ── header ───────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => navigate('/masters/resources')}
          aria-label="Back to resources"
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-h1 text-content">{isEdit ? 'Edit resource' : 'New resource'}</h1>

        <div className="ml-auto flex items-center gap-2">
          <Button type="button" variant="secondary" onClick={() => navigate('/masters/resources')}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSaving}>
            {isSaving ? 'Saving…' : isEdit ? 'Save changes' : 'Create resource'}
          </Button>
        </div>
      </div>

      {bannerError && (
        // `border-danger` and `text-danger-text` are the tokens that exist —
        // blueprint §12.1 defines `danger` (a 3:1 UI shade, for borders and
        // icons) and `danger.text` (a 4.5:1 shade, for small text) and nothing
        // between them. An invented `danger-subtle` would generate no class at
        // all and the banner would render unstyled, which is the failure mode
        // that looks like a CSS bug rather than a rule being broken.
        <div
          role="alert"
          className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          {bannerError}
        </div>
      )}

      {/* ── Personal ─────────────────────────────────────────────────────── */}
      <FieldGroup title="Personal">
        <FormField id="employeeCode" label="Employee code" required error={errors.employeeCode?.message}>
          {(aria) => <Input {...aria} {...form.register('employeeCode')} autoComplete="off" />}
        </FormField>

        <FormField id="displayName" label="Full name" required error={errors.displayName?.message}>
          {(aria) => <Input {...aria} {...form.register('displayName')} autoComplete="off" />}
        </FormField>

        <FormField id="email" label="Email" required error={errors.email?.message}>
          {(aria) => <Input {...aria} type="email" {...form.register('email')} autoComplete="off" />}
        </FormField>

        <FormField id="mobile" label="Mobile" error={errors.mobile?.message}>
          {(aria) => <Input {...aria} {...form.register('mobile')} autoComplete="off" />}
        </FormField>

        <FormField
          id="avatarUrl"
          label="Profile photo"
          hint="A link to an image. Upload lands with attachment storage (A-016)."
          error={errors.avatarUrl?.message}
        >
          {(aria) => <Input {...aria} {...form.register('avatarUrl')} placeholder="https://…" />}
        </FormField>

        <FormField id="dateOfJoining" label="Date of joining" error={errors.dateOfJoining?.message}>
          {(aria) => <Input {...aria} type="date" {...form.register('dateOfJoining')} />}
        </FormField>
      </FieldGroup>

      {/* ── Access ───────────────────────────────────────────────────────── */}
      <FieldGroup title="Access">
        <FormField id="username" label="Username" required error={errors.username?.message}>
          {(aria) => <Input {...aria} {...form.register('username')} autoComplete="off" />}
        </FormField>

        <FormField id="role" label="Role" required error={errors.role?.message}>
          {(aria) => (
            <Controller
              control={form.control}
              name="role"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger {...aria}>
                    <SelectValue placeholder="Choose a role" />
                  </SelectTrigger>
                  <SelectContent>
                    {ROLES.map((role) => (
                      <SelectItem key={role} value={role}>
                        {ROLE_LABEL[role]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
          )}
        </FormField>

        {isEdit ? (
          <FormField
            id="isActive"
            label="Status"
            hint="Deactivating somebody who holds open tickets is refused until those tickets are reassigned."
            error={errors.isActive?.message}
          >
            {(aria) => (
              <Controller
                control={form.control}
                name="isActive"
                render={({ field }) => (
                  <label className="flex h-10 items-center gap-2 text-sm text-content">
                    <input
                      {...aria}
                      type="checkbox"
                      checked={field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                      className="h-4 w-4 cursor-pointer accent-primary"
                    />
                    Active
                  </label>
                )}
              />
            )}
          </FormField>
        ) : (
          <ReadOnlyField
            label="Temporary password"
            value="Generated on save, shown once"
            hint="They will be asked to change it the first time they log in."
          />
        )}

        {isEdit && loaded && (
          <ReadOnlyField
            label="Password state"
            value={
              loaded.resource.mustChangePassword
                ? 'Still on the temporary password'
                : 'Has set their own password'
            }
            hint={
              loaded.resource.mustChangePassword
                ? 'They have not completed a first login.'
                : undefined
            }
          />
        )}
      </FieldGroup>

      {/* ── Org ──────────────────────────────────────────────────────────── */}
      <FieldGroup title="Org">
        <FormField id="department" label="Department" error={errors.department?.message}>
          {(aria) => <Input {...aria} {...form.register('department')} autoComplete="off" />}
        </FormField>

        <FormField id="designation" label="Designation" error={errors.designation?.message}>
          {(aria) => <Input {...aria} {...form.register('designation')} autoComplete="off" />}
        </FormField>

        <FormField
          id="reportingManagerId"
          label="Reporting manager"
          hint="Cycles beyond self-reference are not detected yet (B-012)."
          error={errors.reportingManagerId?.message}
        >
          {(aria) => (
            <Controller
              control={form.control}
              name="reportingManagerId"
              render={({ field }) => {
                const selected = managers.find((m) => m.id === field.value) ?? null
                return (
                  <div className="flex items-center gap-2">
                    <SearchableDropdown<User>
                      {...aria}
                      className="flex-1"
                      options={managers}
                      value={selected}
                      onChange={(manager) => field.onChange(manager.id)}
                      getKey={(m) => String(m.id)}
                      getLabel={(m) => m.displayName}
                      getSearchable={(m) => [m.email ?? '', m.employeeCode ?? '']}
                      placeholder="Search people…"
                    />
                    {field.value != null && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => field.onChange(null)}
                      >
                        Clear
                      </Button>
                    )}
                  </div>
                )
              }}
            />
          )}
        </FormField>

        <FormField id="location" label="Location" error={errors.location?.message}>
          {(aria) => <Input {...aria} {...form.register('location')} autoComplete="off" />}
        </FormField>

        <FormField
          id="timezone"
          label="Time zone"
          required
          hint="Display only. Everything is stored in UTC."
          error={errors.timezone?.message}
        >
          {(aria) => <Input {...aria} {...form.register('timezone')} autoComplete="off" />}
        </FormField>
      </FieldGroup>

      {/* ── Work ─────────────────────────────────────────────────────────── */}
      <FieldGroup title="Work" columns={1}>
        <FormField
          id="dailyCapacityHrs"
          label="Daily capacity (hours)"
          required
          hint="Feeds every utilisation figure."
          className="sm:max-w-xs"
          error={errors.dailyCapacityHrs?.message}
        >
          {(aria) => (
            <Input
              {...aria}
              type="number"
              step="0.5"
              min="0.5"
              max="24"
              {...form.register('dailyCapacityHrs', { valueAsNumber: true })}
            />
          )}
        </FormField>

        <FormField
          id="weeklyOff"
          label="Weekly off pattern"
          hint="Leave inherited unless this person's week differs from the organisation's."
          error={errors.weeklyOff?.message}
        >
          {(aria) => (
            <Controller
              control={form.control}
              name="weeklyOff"
              render={({ field }) => (
                <div className="flex flex-col gap-3">
                  <label className="flex items-center gap-2 text-sm text-content">
                    <input
                      type="checkbox"
                      checked={field.value == null}
                      onChange={(e) =>
                        // Unchecking seeds from the field's own state rather
                        // than from the org week, which this form does not
                        // read. An empty list is a legitimate answer — a
                        // support rota with no weekly off — so it is also a
                        // sensible starting point for editing one.
                        field.onChange(e.target.checked ? null : [])
                      }
                      className="h-4 w-4 cursor-pointer accent-primary"
                    />
                    Use the organisation&rsquo;s working week
                  </label>

                  {field.value != null && (
                    <WeeklyOffPicker
                      value={field.value}
                      onChange={(days: IsoDay[]) => field.onChange(days)}
                      errorId={aria['aria-describedby']}
                    />
                  )}
                </div>
              )}
            />
          )}
        </FormField>

        <FormField id="skills" label="Skills / tags" error={errors.skills?.message}>
          {(aria) => (
            <Controller
              control={form.control}
              name="skills"
              render={({ field }) => (
                <SkillsInput value={field.value} onChange={field.onChange} aria={aria} />
              )}
            />
          )}
        </FormField>
      </FieldGroup>

      {/* ── Projects ─────────────────────────────────────────────────────── */}
      <FieldGroup title="Projects" columns={1}>
        <FormField
          id="projects"
          label="Project assignments"
          hint="A per-project role overrides their global role on that project only."
          error={errors.projects?.message}
        >
          {() => (
            <Controller
              control={form.control}
              name="projects"
              render={({ field }) => (
                <ProjectAssignmentsEditor
                  value={field.value}
                  onChange={field.onChange}
                  projects={projects}
                />
              )}
            />
          )}
        </FormField>
      </FieldGroup>

      <TemporaryPasswordDialog
        password={createdPassword}
        displayName={createdName}
        onClose={() => {
          setCreatedPassword(null)
          navigate('/masters/resources')
        }}
      />
    </form>
  )
}
