import { describe, expect, it } from 'vitest'

import { ApiError } from '@/api/http'

import { BATCH_POLL_MS, batchPollInterval, commitRefusal, isTerminal, progressPercent } from './importQueries'

/**
 * B-035 · step 5's rules, as pure functions.
 *
 * Same reasoning as `validationPreview.test.ts` one step over: these are
 * decisions the screen makes on every poll, and the interesting cases are about
 * arithmetic and about which sentence a problem type earns. Asserting "the bar
 * is at 40%" through a rendered `<div style>` needs a query to say what one
 * function call says.
 */

function problem(type: string, detail?: string, status = 422): ApiError {
  return new ApiError(
    status,
    {
      type: `https://edutrack/errors/${type}`,
      title: 'Refused',
      status,
      ...(detail ? { detail } : {}),
    },
    new Response(null, { status }),
  )
}

describe('the progress bar arithmetic', () => {
  it('is a percentage of processed against total', () => {
    expect(progressPercent(50, 200)).toBe(25)
    expect(progressPercent(200, 200)).toBe(100)
  })

  /**
   * The rejected rows are counted before the job starts, so a file with six bad
   * rows begins partly filled. Leaving them out would leave the bar permanently
   * short of the end, which reads as a job that stalled.
   */
  it('counts rows that were rejected before the run started', () => {
    // 94 written of 100, 6 refused by the dry run — the run is over.
    expect(progressPercent(100, 100)).toBe(100)
    // And at the moment it began, it was already at 6%.
    expect(progressPercent(6, 100)).toBe(6)
  })

  /**
   * `total` is 0 on a batch that has not started. Dividing would produce `NaN`,
   * which React renders as a bar with no width and no explanation.
   */
  it('is 0 rather than NaN before a run has a size', () => {
    expect(progressPercent(0, 0)).toBe(0)
  })

  it('never exceeds 100, because a bar past the end is a rendering fault', () => {
    expect(progressPercent(150, 100)).toBe(100)
    expect(progressPercent(-5, 100)).toBe(0)
  })
})

describe('when the poll stops', () => {
  it('stops on a terminal status and not before', () => {
    expect(isTerminal('COMPLETED')).toBe(true)
    expect(isTerminal('FAILED')).toBe(true)
    expect(isTerminal('QUEUED')).toBe(false)
    expect(isTerminal('RUNNING')).toBe(false)
  })

  /**
   * A status this build has not heard of — a row written by a newer deploy. It
   * must not stop the poll, because a screen that stopped polling on an unknown
   * status would sit for ever on a run that was going to finish.
   */
  it('keeps polling through a status it does not recognise', () => {
    expect(isTerminal('PAUSED')).toBe(false)
    expect(isTerminal(undefined)).toBe(false)
  })

  /**
   * The one that would otherwise ship broken. `refetchInterval` has to return
   * `false` on a terminal status — a finished import left polling is a request
   * every two seconds, for as long as the tab is open, whose answer provably
   * cannot change.
   */
  it('returns false for a finished run and an interval for a live one', () => {
    expect(batchPollInterval('COMPLETED')).toBe(false)
    expect(batchPollInterval('FAILED')).toBe(false)
    expect(batchPollInterval('RUNNING')).toBe(BATCH_POLL_MS)
    expect(batchPollInterval('QUEUED')).toBe(BATCH_POLL_MS)
    // Nothing read yet — the first poll has to happen.
    expect(batchPollInterval(undefined)).toBe(BATCH_POLL_MS)
  })
})

describe('a commit refusal', () => {
  /**
   * The two this step adds, and the reason they are two. Both mean "this file
   * has bad rows" and the remedies are opposite — go back to your spreadsheet
   * versus stop asking for all-or-nothing — so a shared type would put an offer
   * on the screen that one of the cases cannot honour.
   */
  it('sends a file with nothing writable back to be re-previewed', () => {
    const refusal = commitRefusal(problem('import-nothing-to-commit', 'No row can be imported.'))

    expect(refusal.remedy).toBe('revalidate')
    expect(refusal.message).toBe('No row can be imported.')
  })

  it('treats an all-or-nothing refusal as its own condition', () => {
    expect(commitRefusal(problem('import-rejected-rows-present')).remedy).toBe('revalidate')
  })

  /**
   * A full commit queue clears on its own, so "try again in a moment" is
   * genuinely the right advice here rather than a shrug — which is why this is
   * `retry` and the two above are not.
   */
  it('tells the user to wait when every commit slot is taken', () => {
    expect(commitRefusal(problem('import-commit-queue-full', 'Too many imports.', 503)).remedy)
      .toBe('retry')
  })

  /**
   * The four the two steps share. Delegated rather than restated, so the
   * sentence a user reads about an expired upload is the same sentence whichever
   * button produced it.
   */
  it('reuses step 4’s remedies for the refusals the two steps share', () => {
    expect(commitRefusal(problem('import-upload-unavailable')).remedy).toBe('upload')
    expect(commitRefusal(problem('import-incomplete-mapping')).remedy).toBe('mapping')
    expect(commitRefusal(problem('import-unknown-column')).remedy).toBe('mapping')
    expect(commitRefusal(problem('import-unknown-field')).remedy).toBe('mapping')
  })

  it('says something useful when the request never reached the server', () => {
    const refusal = commitRefusal(new TypeError('network down'))

    expect(refusal.remedy).toBe('retry')
    expect(refusal.message).toMatch(/connection/i)
  })
})
