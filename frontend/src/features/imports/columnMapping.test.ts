import { describe, expect, it } from 'vitest'

import type { ImportSchemaField } from '@/api/generated/model'

import {
  applyPreset,
  isMappingComplete,
  missingRequiredFields,
  sharedColumns,
  unmappedSourceColumns,
  withColumn,
} from './columnMapping'

/**
 * B-033 · step 3's rules.
 *
 * These are pure for a reason worth stating: the interesting cases are
 * combinatorial — a preset saved against a file whose columns have since been
 * renamed, a required column nobody mapped, two fields reading one column — and
 * each of them through a rendered table of twenty `<select>`s would be a fixture
 * exercise rather than a test of the rule.
 */

function field(
  name: string,
  header: string,
  required = false,
  naturalKey = false,
): ImportSchemaField {
  return { name, header, required, naturalKey, type: 'TEXT' }
}

const FIELDS: ImportSchemaField[] = [
  field('clientCode', 'Client Code', true, true),
  field('name', 'Name', true),
  field('primaryEmail', 'Primary Email'),
  field('supportEmail', 'Support Email'),
]

describe('what blocks Next', () => {
  /**
   * §4B.3: "Unmapped required columns block the Next button." Both required
   * fields, in the schema's own order — a list ordered by whatever the user
   * touched last reads like a different complaint each time it appears.
   */
  it('names every unmapped required field, in schema order', () => {
    expect(missingRequiredFields(FIELDS, {}).map((f) => f.header)).toEqual([
      'Client Code',
      'Name',
    ])
  })

  it('is satisfied once the required fields have a column', () => {
    const mapping = { clientCode: 'Code', name: 'Account' }

    expect(missingRequiredFields(FIELDS, mapping)).toEqual([])
    expect(isMappingComplete(FIELDS, mapping)).toBe(true)
  })

  /** An optional field left unmapped is the ordinary case, not a problem. */
  it('does not care about unmapped optional fields', () => {
    expect(isMappingComplete(FIELDS, { clientCode: 'Code', name: 'Account' })).toBe(true)
  })

  /**
   * A key present with an empty value must not count as mapped. This is the
   * failure that would enable Next over a required column nobody chose — and it
   * is exactly what a `<select>` left on its empty option produces if the mapping
   * stores the value rather than removing the key.
   */
  it('treats a blank column as not mapped, not as mapped-to-nothing', () => {
    expect(missingRequiredFields(FIELDS, { clientCode: '', name: 'Account' }).map((f) => f.name))
      .toEqual(['clientCode'])
  })
})

describe('clearing a field', () => {
  /**
   * The key goes, rather than being set to `''`. Absence needs one
   * representation: an empty string still counts in `Object.keys().length`, which
   * is what the "n of m mapped" summary reads, and the server strips it on save —
   * so leaving it would make the screen disagree with the preset it just saved.
   */
  it('removes the key rather than storing an empty string', () => {
    const cleared = withColumn({ clientCode: 'Code', name: 'Account' }, 'name', '')

    expect(cleared).toEqual({ clientCode: 'Code' })
    expect('name' in cleared).toBe(false)
  })

  it('does not mutate the mapping it was given', () => {
    const original = { clientCode: 'Code' }
    withColumn(original, 'name', 'Account')

    expect(original).toEqual({ clientCode: 'Code' })
  })
})

describe('columns the import will not read', () => {
  /**
   * Not an error — a file exported from another system carries columns this
   * import has no home for, and refusing it would be wrong. Reported because the
   * alternative is somebody discovering after the commit that the Account Manager
   * column they filled in was never read, which is a real absence:
   * `ClientImportSchema` deliberately has no column for it.
   */
  it('lists the file’s columns that no field reads', () => {
    const headers = ['Code', 'Account', 'Account Manager', 'Notes']

    expect(unmappedSourceColumns(headers, { clientCode: 'Code', name: 'Account' }))
      .toEqual(['Account Manager', 'Notes'])
  })

  it('counts a column read by any field as read', () => {
    expect(unmappedSourceColumns(['Email'], { primaryEmail: 'Email', supportEmail: 'Email' }))
      .toEqual([])
  })
})

describe('applying a saved preset', () => {
  it('takes the entries whose column is in this file', () => {
    const applied = applyPreset(
      { clientCode: 'Code', name: 'Account' },
      ['Code', 'Account', 'Notes'],
      FIELDS,
    )

    expect(applied.mapping).toEqual({ clientCode: 'Code', name: 'Account' })
    expect(applied.droppedColumns).toEqual([])
    expect(applied.droppedFields).toEqual([])
  })

  /**
   * The case this function exists for. A preset is saved from one export and
   * applied to the next; the next one may have had a column renamed.
   *
   * Carrying the entry over would be the worse bug of the two available: the
   * mapping would name a heading that is in no `<select>` on screen, so the row
   * would render as unmapped while `missingRequiredFields` counted it as mapped —
   * and Next would be enabled over a required column that is not mapped at all.
   */
  it('drops an entry whose column this file does not have, and says which', () => {
    const applied = applyPreset(
      { clientCode: 'Code', name: 'Account Name' },
      ['Code', 'Account'],
      FIELDS,
    )

    expect(applied.mapping).toEqual({ clientCode: 'Code' })
    expect(applied.droppedColumns).toEqual([{ field: 'name', column: 'Account Name' }])
    // Name is still unmapped, so Next stays blocked — which is the half that
    // matters. Carrying the stale entry over would have enabled it.
    expect(isMappingComplete(FIELDS, applied.mapping)).toBe(false)
  })

  /**
   * The server refuses to *save* a preset naming an undeclared field (422), so
   * this covers rows saved before a field was renamed — which that refusal cannot
   * reach retroactively.
   */
  it('drops an entry whose target field the schema no longer declares', () => {
    const applied = applyPreset(
      { clientCode: 'Code', faxNumber: 'Fax' },
      ['Code', 'Fax'],
      FIELDS,
    )

    expect(applied.mapping).toEqual({ clientCode: 'Code' })
    expect(applied.droppedFields).toEqual(['faxNumber'])
  })

  /**
   * Exact matching, for `HeaderMatcher`'s reason: normalising "Support Email"
   * onto "Email" would put the helpdesk address in the account contact field, in
   * a mapping the user was shown and skimmed past.
   */
  it('matches headings exactly rather than approximately', () => {
    const applied = applyPreset({ primaryEmail: 'Email' }, ['E-mail'], FIELDS)

    expect(applied.mapping).toEqual({})
    expect(applied.droppedColumns).toEqual([{ field: 'primaryEmail', column: 'Email' }])
  })
})

describe('two fields reading one column', () => {
  /**
   * Legitimate, and the mapping can express it: one `Email` column feeding both
   * email fields is a real file. Surfaced rather than blocked because the other
   * way it happens is a slip — one `<select>` changed on a misread row.
   */
  it('reports the sharing without treating it as an error', () => {
    const shared = sharedColumns({
      clientCode: 'Code',
      primaryEmail: 'Email',
      supportEmail: 'Email',
    })

    expect([...shared.keys()]).toEqual(['Email'])
    expect(shared.get('Email')).toEqual(['primaryEmail', 'supportEmail'])
  })

  it('says nothing in the ordinary case', () => {
    expect(sharedColumns({ clientCode: 'Code', name: 'Account' }).size).toBe(0)
  })
})
