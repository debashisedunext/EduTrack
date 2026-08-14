import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Controller, useForm, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft } from 'lucide-react'

import { useListUsers } from '@/api/generated/users/users'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { FieldGroup, FormField, ReadOnlyField } from '../resources/FormField'

import { ProjectTabs } from './ProjectTabs'
import { useCreateProject, useProject, useUpdateProject } from './projectQueries'
import {
  AUTO_ASSIGN_RULES,
  COLOUR_TAGS,
  emptyProjectForm,
  isCodeEditable,
  PROJECT_STATUSES,
  projectFieldSchema,
  projectFormSchema,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  type ProjectFormValues,
} from './projectForm'

/**
 * S-10 Project Master — Create / Edit (B-016).
 *
 * One page for both, for the reason `ResourceFormPage` gives: they are one form,
 * every field and validation is shared, and the only differences are the verb
 * and whether an `ETag` is in play. Two components would be the same file twice
 * with one copy always slightly behind.
 *
 * <h2>The code field disables itself, and says why</h2>
 *
 * `ticketsIssued > 0` closes it — the same test the server applies, read off the
 * same field. **Disabled rather than hidden**, with the count in the hint: a
 * missing input reads as a rendering bug, and "347 ticket IDs already carry this
 * prefix" is the kind of thing an admin should be able to see rather than
 * discover from a 409 after typing.
 *
 * <h2>What is deliberately not here</h2>
 *
 * - **The Team tab (B-017), the SLA tab (B-018) and the Settings tab (B-019).**
 *   S-10 describes four tabs and this is the first. The other three have their
 *   own contract paths and their own tasks; stubbing them as empty tabs would
 *   make three unbuilt screens look built.
 * - **A free hex colour input.** CLAUDE.md: never introduce a colour that is not
 *   a token. The picker offers the eight `--chart-*` swatches from §12.1. The
 *   server still accepts any `#RRGGBB`, because constraining it there would mean
 *   the palette could not change without a release.
 * - **A delete button.** There is none, and there cannot be: `tickets.project_id`
 *   is a foreign key with no cascade. Closing a project is how it is retired.
 */
export function ProjectFormPage() {
  const navigate = useNavigate()
  const params = useParams<{ projectId?: string }>()

  const projectId = params.projectId ? Number(params.projectId) : null
  const isEdit = projectId != null

  const { data: loaded, isPending: loading, isError: loadFailed } = useProject(projectId)

  // Every active resource is a candidate manager. The role is deliberately not
  // filtered — a support-led project has a legitimate reason to name its Support
  // lead, and a hardcoded role set is what B-015 removed from the resource grid.
  const { data: managersData } = useListUsers({ isActive: true, limit: 200 })
  const managers = React.useMemo(() => managersData?.data ?? [], [managersData])

  const [bannerError, setBannerError] = React.useState<string | null>(null)

  const form = useForm<ProjectFormValues>({
    resolver: zodResolver(projectFormSchema) as Resolver<ProjectFormValues>,
    defaultValues: emptyProjectForm,
    mode: 'onBlur',
  })

  // Seeded once the read lands. `reset` rather than `defaultValues` because the
  // form mounts before the fetch resolves, and re-mounting the page on load
  // would throw away focus and scroll position.
  const seeded = React.useRef(false)
  React.useEffect(() => {
    if (loaded && !seeded.current) {
      seeded.current = true
      form.reset(toFormValues(loaded.project))
    }
  }, [loaded, form])

  const createProject = useCreateProject()
  const updateProject = useUpdateProject()
  const isSaving = createProject.isPending || updateProject.isPending

  const codeEditable = isCodeEditable(loaded?.project ?? null)
  const ticketsIssued = loaded?.project.ticketsIssued ?? 0

  function applyServerError(error: unknown): void {
    if (!(error instanceof ApiError)) {
      setBannerError('Something went wrong. Try again.')
      return
    }

    // 412 is not a field error and does not belong on an input: nothing the
    // admin typed is wrong, the row moved underneath them.
    if (error.status === 412) {
      setBannerError(
        'Somebody else saved this project while you were editing. Reload the page and reapply your change.',
      )
      return
    }

    const known = new Set(Object.keys(projectFieldSchema.shape))
    const unmatched: string[] = []

    for (const [key, messages] of Object.entries(error.fieldErrors)) {
      const message = messages[0] ?? 'Invalid'
      if (known.has(key)) {
        form.setError(key as keyof ProjectFormValues, { type: 'server', message })
      } else {
        unmatched.push(message)
      }
    }

    const first = Object.keys(error.fieldErrors).find((k) => known.has(k))
    if (first) {
      // Focus it. A 409 on a long form otherwise scrolls nowhere and the message
      // sits below the fold.
      form.setFocus(first as keyof ProjectFormValues)
      return
    }
    setBannerError(unmatched[0] ?? error.problem.detail ?? 'The project was not saved.')
  }

  const onSubmit = form.handleSubmit((values) => {
    setBannerError(null)

    if (isEdit && projectId != null) {
      updateProject.mutate(
        { projectId, data: toPatchRequest(values), etag: loaded?.etag ?? null },
        {
          onSuccess: (project) => {
            toast({ title: `${project.name} saved` })
            navigate('/masters/projects')
          },
          onError: applyServerError,
        },
      )
      return
    }

    createProject.mutate(toWriteRequest(values), {
      onSuccess: (project) => {
        toast({
          title: `${project.name} created`,
          description: `Ticket IDs on this project will read ${project.projectCode}-26-00001.`,
        })
        navigate('/masters/projects')
      },
      onError: applyServerError,
    })
  })

  if (isEdit && loading) {
    return <Skeleton className="m-6 h-96" />
  }
  if (isEdit && loadFailed) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <p className="text-sm text-danger-text">That project could not be loaded.</p>
        <Button asChild variant="secondary" className="mt-4">
          <Link to="/masters/projects">Back to projects</Link>
        </Button>
      </div>
    )
  }

  return (
    <form onSubmit={onSubmit} className="mx-auto flex max-w-3xl flex-col gap-6 p-6">
      {/*
        B-017 · an existing project gets the tab strip; a new one does not, and
        cannot: the Team tab writes `project_members` rows, which need a
        project id that does not exist until this form is submitted. Offering
        the tab on a create would be offering a screen that can only 404.
      */}
      {isEdit ? (
        <ProjectTabs active="General" />
      ) : (
        <header className="flex flex-col gap-2">
          <Link
            to="/masters/projects"
            className="inline-flex w-fit items-center gap-1 text-sm text-content-muted hover:text-content"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden />
            Projects
          </Link>
          <h1 className="text-2xl font-semibold text-content">New project</h1>
        </header>
      )}

      {bannerError ? (
        // `border-danger` + `text-danger-text` are the tokens that exist —
        // §12.1 defines a 3:1 UI shade and a 4.5:1 text shade and nothing
        // between them. An invented `danger-subtle` generates no class at all
        // and renders unstyled, which looks like a CSS bug rather than a rule
        // being broken. Same note as `ResourceFormPage`'s banner.
        <div
          role="alert"
          className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          {bannerError}
        </div>
      ) : null}

      <FieldGroup title="Identity" description="The code prefixes every ticket ID this project issues.">
        <FormField
          id="projectCode"
          label="Project code"
          required
          error={form.formState.errors.projectCode?.message}
          hint={
            codeEditable
              ? 'Letters and digits, 2–10 characters. Fixed permanently once the first ticket is raised.'
              : `Fixed — ${ticketsIssued} ticket ID${ticketsIssued === 1 ? '' : 's'} already carry this prefix.`
          }
        >
          {(aria) => (
            <Input
              {...aria}
              {...form.register('projectCode')}
              disabled={!codeEditable}
              placeholder="CRM"
              onChange={(e) => form.setValue('projectCode', e.target.value.toUpperCase())}
            />
          )}
        </FormField>

        <FormField
          id="name"
          label="Project name"
          required
          error={form.formState.errors.name?.message}
        >
          {(aria) => <Input {...aria} {...form.register('name')} placeholder="Client CRM Platform" />}
        </FormField>

        <FormField
          id="clientName"
          label="Client"
          error={form.formState.errors.clientName?.message}
          hint="Free text for now — the client master link arrives with B-026."
        >
          {(aria) => <Input {...aria} {...form.register('clientName')} placeholder="Acme Retail Ltd" />}
        </FormField>

        <FormField
          id="description"
          label="Description"
          error={form.formState.errors.description?.message}
          className="sm:col-span-2"
        >
          {(aria) => (
            <textarea
              {...aria}
              {...form.register('description')}
              rows={3}
              className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
              placeholder="What this project covers, and who it is for."
            />
          )}
        </FormField>
      </FieldGroup>

      <FieldGroup title="Ownership & schedule">
        <Controller
          control={form.control}
          name="projectManagerId"
          render={({ field }) => (
            <FormField
              id="projectManagerId"
              label="Project manager"
              required
              error={form.formState.errors.projectManagerId?.message}
              hint="Who SLA escalations reach. Any active resource, whatever their role."
            >
              {(aria) => (
                <SearchableDropdown
                  {...aria}
                  options={managers}
                  value={managers.find((u) => u.id === field.value) ?? null}
                  onChange={(u) => field.onChange(u?.id ?? 0)}
                  getKey={(u) => String(u.id)}
                  getLabel={(u) => u.displayName}
                  getSearchable={(u) => [u.email ?? '']}
                  placeholder="Search resources…"
                />
              )}
            </FormField>
          )}
        />

        <Controller
          control={form.control}
          name="status"
          render={({ field }) => (
            <FormField
              id="status"
              label="Status"
              required
              hint={PROJECT_STATUSES.find((s) => s.value === field.value)?.hint}
            >
              {(aria) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger {...aria}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {PROJECT_STATUSES.map((s) => (
                      <SelectItem key={s.value} value={s.value}>
                        {s.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </FormField>
          )}
        />

        <FormField id="startDate" label="Start date" error={form.formState.errors.startDate?.message}>
          {(aria) => <Input {...aria} type="date" {...form.register('startDate')} />}
        </FormField>

        <FormField
          id="endDate"
          label="Target end date"
          error={form.formState.errors.endDate?.message}
        >
          {(aria) => <Input {...aria} type="date" {...form.register('endDate')} />}
        </FormField>
      </FieldGroup>

      <FieldGroup title="Appearance & assignment" columns={2}>
        <Controller
          control={form.control}
          name="colourTag"
          render={({ field }) => (
            <FormField
              id="colourTag"
              label="Colour tag"
              hint="From the design tokens. It is how this project is recognised in the ribbon and the grid."
            >
              {(aria) => (
                <div
                  {...aria}
                  role="radiogroup"
                  className="flex flex-wrap items-center gap-2 pt-1"
                >
                  {COLOUR_TAGS.map((c) => (
                    <button
                      key={c.value}
                      type="button"
                      role="radio"
                      aria-checked={field.value === c.value}
                      aria-label={c.label}
                      title={c.label}
                      onClick={() => field.onChange(c.value)}
                      style={{ backgroundColor: c.value }}
                      className={cn(
                        'h-7 w-7 rounded-full ring-offset-2 focus:outline-none focus:ring-2 focus:ring-primary',
                        field.value === c.value && 'ring-2 ring-content',
                      )}
                    />
                  ))}
                </div>
              )}
            </FormField>
          )}
        />

        <Controller
          control={form.control}
          name="autoAssignRule"
          render={({ field }) => (
            <FormField
              id="autoAssignRule"
              label="Auto-assign rule"
              hint="Who an unassigned new ticket goes to. The full Settings tab is B-019."
            >
              {(aria) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger {...aria}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {AUTO_ASSIGN_RULES.map((r) => (
                      <SelectItem key={r.value} value={r.value}>
                        {r.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </FormField>
          )}
        />

        {isEdit ? (
          <ReadOnlyField
            label="Ticket IDs issued"
            value={ticketsIssued}
            hint="Allocated by the ticket sequence. Non-zero is what fixes the project code."
          />
        ) : null}
      </FieldGroup>

      <div className="flex justify-end gap-3">
        <Button asChild type="button" variant="secondary">
          <Link to="/masters/projects">Cancel</Link>
        </Button>
        <Button type="submit" disabled={isSaving}>
          {isSaving ? 'Saving…' : isEdit ? 'Save project' : 'Create project'}
        </Button>
      </div>
    </form>
  )
}
