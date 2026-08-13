import { describe, expect, it } from 'vitest'

import type { ProjectDetail } from '@/api/generated/model/projectDetail'

import {
  COLOUR_TAGS,
  emptyProjectForm,
  isCodeEditable,
  projectFormSchema,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
} from './projectForm'

/**
 * B-016 · the mapping between the S-10 form and the request, which is the part
 * worth testing on its own and is unreachable behind a rendered page.
 */

const detail = (over: Partial<ProjectDetail> = {}): ProjectDetail => ({
  id: 1,
  projectCode: 'CRM',
  name: 'Client CRM Platform',
  clientName: 'Acme Retail Ltd',
  description: 'The client-facing CRM',
  projectManager: { id: 2, displayName: 'Priya Sharma', role: 'PM' },
  colourTag: '#4F46E5',
  startDate: '2026-01-05',
  endDate: '2026-12-18',
  status: 'ACTIVE',
  isActive: true,
  autoAssignRule: 'LEAST_LOADED',
  ticketsIssued: 347,
  ...over,
})

describe('validation', () => {
  it('rejects a code that is not a legal ticket prefix', () => {
    // It becomes `CRM-26-00347`. A dash or a space in it would produce a ticket
    // ID nothing can parse back.
    for (const projectCode of ['C', 'crm-2', 'MY PROJECT', '9CRM', 'TOOLONGACODE']) {
      const result = projectFormSchema.safeParse({ ...emptyProjectForm, projectCode, name: 'X', projectManagerId: 2 })
      expect(result.success, projectCode).toBe(false)
    }
  })

  it('accepts a lower-case code, because it is upper-cased on the way out', () => {
    const result = projectFormSchema.safeParse({
      ...emptyProjectForm, projectCode: 'crm', name: 'CRM', projectManagerId: 2,
    })
    expect(result.success).toBe(true)
    expect(toWriteRequest(result.data!).projectCode).toBe('CRM')
  })

  it('requires a project manager — S-10 asterisks it', () => {
    // Without one there is nobody for the SLA engine to escalate to.
    const result = projectFormSchema.safeParse({ ...emptyProjectForm, projectCode: 'NEW', name: 'X' })
    expect(result.success).toBe(false)
    expect(result.error?.issues.some((i) => i.path[0] === 'projectManagerId')).toBe(true)
  })

  it('rejects a target end date before the start date, keyed on endDate', () => {
    const result = projectFormSchema.safeParse({
      ...emptyProjectForm,
      projectCode: 'NEW', name: 'X', projectManagerId: 2,
      startDate: '2026-09-01', endDate: '2026-08-01',
    })
    expect(result.success).toBe(false)
    expect(result.error?.issues[0].path).toEqual(['endDate'])
  })

  it('allows a one-day project and an open-ended one', () => {
    const base = { ...emptyProjectForm, projectCode: 'NEW', name: 'X', projectManagerId: 2 }
    expect(projectFormSchema.safeParse({ ...base, startDate: '2026-08-13', endDate: '2026-08-13' }).success).toBe(true)
    expect(projectFormSchema.safeParse({ ...base, startDate: '2026-08-13', endDate: '' }).success).toBe(true)
  })
})

describe('toWriteRequest', () => {
  it('turns every blank optional into null, not an empty string', () => {
    // Storing '' would make "no description" two values every reader has to
    // handle.
    const request = toWriteRequest({
      ...emptyProjectForm, projectCode: 'NEW', name: 'Greenfield', projectManagerId: 2,
    })

    expect(request.clientName).toBeNull()
    expect(request.description).toBeNull()
    expect(request.startDate).toBeNull()
    expect(request.endDate).toBeNull()
  })

  it('trims what it sends', () => {
    const request = toWriteRequest({
      ...emptyProjectForm,
      projectCode: '  new  ', name: '  Greenfield  ', clientName: '  Acme  ', projectManagerId: 2,
    })

    expect(request.projectCode).toBe('NEW')
    expect(request.name).toBe('Greenfield')
    expect(request.clientName).toBe('Acme')
  })

  it('defaults to the conservative status and rule', () => {
    // MANUAL because round-robin and least-loaded both hand live work to
    // somebody without a human deciding.
    const request = toWriteRequest({
      ...emptyProjectForm, projectCode: 'NEW', name: 'Greenfield', projectManagerId: 2,
    })

    expect(request.status).toBe('ACTIVE')
    expect(request.autoAssignRule).toBe('MANUAL')
    expect(request.colourTag).toBe(COLOUR_TAGS[0].value)
  })
})

describe('toFormValues', () => {
  it('round-trips a stored project without changing anything', () => {
    const request = toPatchRequest(toFormValues(detail()))

    expect(request).toMatchObject({
      projectCode: 'CRM',
      name: 'Client CRM Platform',
      clientName: 'Acme Retail Ltd',
      description: 'The client-facing CRM',
      projectManagerId: 2,
      colourTag: '#4F46E5',
      startDate: '2026-01-05',
      endDate: '2026-12-18',
      status: 'ACTIVE',
      autoAssignRule: 'LEAST_LOADED',
    })
  })

  it('turns every stored null into the empty string the inputs need', () => {
    // Not undefined: React switches an input from uncontrolled to controlled on
    // first keystroke and warns.
    const values = toFormValues(detail({
      clientName: null, description: null, startDate: null, endDate: null,
    }))

    expect(values.clientName).toBe('')
    expect(values.description).toBe('')
    expect(values.startDate).toBe('')
    expect(values.endDate).toBe('')
  })

  it('survives a project with no manager', () => {
    // A row predating the mandatory rule. The picker renders empty and the
    // schema refuses the save until one is chosen, which is the correct
    // behaviour — it must not crash on load.
    const values = toFormValues(detail({ projectManager: undefined }))

    expect(values.projectManagerId).toBe(0)
    expect(projectFormSchema.safeParse(values).success).toBe(false)
  })
})

describe('isCodeEditable', () => {
  it('is open on a create', () => {
    expect(isCodeEditable(null)).toBe(true)
  })

  it('is open until the project issues its first ticket ID', () => {
    expect(isCodeEditable(detail({ ticketsIssued: 0 }))).toBe(true)
  })

  it('closes on the first issued ID, not on the first ticket row', () => {
    // ticketsIssued is projects.ticket_seq — codes ISSUED. A ticket created and
    // later deleted still had its code quoted in mail, so the count staying at
    // 1 is the point.
    expect(isCodeEditable(detail({ ticketsIssued: 1 }))).toBe(false)
    expect(isCodeEditable(detail({ ticketsIssued: 347 }))).toBe(false)
  })
})
