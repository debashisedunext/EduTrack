import * as React from 'react';

import type { ObEscalationRung } from '@/api/generated/model/obEscalationRung';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';

import { useObSettings, useUpdateObSettings } from './obSettingsQueries';

/**
 * OB-11 — TAT & escalation settings. B-113.
 *
 * Layout follows the prototype's `vSettings()` in
 * `docs/prototype/onboarding.html`: one card with the two thresholds and the
 * escalation matrix, Save right-aligned, and a second card stating the clock
 * rules that are fixed by design.
 *
 * ## What this screen is for
 *
 * PHASE-2-BUILD-PLAN §2 locked three numbers: amber at 75% of TAT, the scanner
 * every 5 minutes, and the ladder at breach → +4 working hours → +8. Those are
 * seeds rather than the contract, and the reason they are editable at all is
 * that every one of them is a judgement about a particular organisation's pace
 * — and the version that is wrong for a client is the version nobody can change
 * without a release.
 *
 * ## One form, one Save, and the precondition that makes that safe
 *
 * The write is a wholesale replace, so two admins on this screen at once would
 * otherwise have the second silently discard the first's escalation matrix
 * along with the threshold they did not touch. The `ETag` read with the data is
 * sent back as `If-Match`; a 412 is surfaced as "reload", not swallowed.
 *
 * ## The number of rungs is not editable, and the screen says so
 *
 * `ObEscalationLevel` is closed and `uq_ob_escalations_open` is keyed on it, so
 * a fourth rung would have no level to be. The intervals are configuration; the
 * count is not. There is deliberately no "add rung" control — a button that
 * cannot work is worse than its absence.
 */
export function ObSettingsPage() {
  const query = useObSettings();
  const update = useUpdateObSettings();

  const [draft, setDraft] = React.useState<Draft | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [saved, setSaved] = React.useState(false);

  // Seeded from the server once. Re-seeding on every render would overwrite
  // what the admin is typing each time the query refetched in the background.
  React.useEffect(() => {
    if (query.data && draft === null) {
      setDraft({
        amberThresholdPercent: String(query.data.settings.amberThresholdPercent),
        scannerIntervalMinutes: String(query.data.settings.scannerIntervalMinutes),
        ladder: query.data.settings.ladder.map((rung) => ({ ...rung })),
      });
    }
  }, [query.data, draft]);

  if (query.isPending) {
    return <p className="p-6 text-sm text-content-muted">Loading…</p>;
  }
  if (query.isError) {
    return (
      <p
        role="alert"
        className="m-6 rounded-card bg-level-critical-soft p-3 text-sm text-danger-text"
      >
        {query.error.status === 403
          ? 'Onboarding settings are OB Admin only.'
          : 'These settings could not be loaded.'}
      </p>
    );
  }
  if (!draft) {
    return null;
  }

  const ladderProblem = firstLadderProblem(draft.ladder);

  // The prototype offers the three cadences the scanner is tuned for. A value
  // the server already holds outside that set still has to be representable,
  // or opening this screen would silently rewrite it on the next Save.
  const cadenceOptions = CADENCE_MINUTES.includes(draft.scannerIntervalMinutes)
    ? CADENCE_MINUTES
    : [...CADENCE_MINUTES, draft.scannerIntervalMinutes].sort(
        (a, b) => Number(a) - Number(b),
      );

  function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSaved(false);
    update.mutate(
      {
        data: {
          amberThresholdPercent: Number(draft!.amberThresholdPercent),
          scannerIntervalMinutes: Number(draft!.scannerIntervalMinutes),
          ladder: draft!.ladder,
        },
        etag: query.data?.etag ?? null,
      },
      {
        onSuccess: () => {
          setSaved(true);
          setDraft(null);
        },
        onError: (caught) => setError(messageFor(caught)),
      },
    );
  }

  return (
    <div className="mx-auto w-full max-w-3xl p-6">
      <header>
        <h1 id="ob-settings" className="text-h1 text-content">
          TAT &amp; escalation settings
        </h1>
        <p className="mt-0.5 text-sm text-content-muted">
          Picked up by the TAT scanner on its next sweep. All maths use the working
          calendar. Steps that have already breached are not re-opened and escalations
          already sent are not re-sent.
        </p>
      </header>

      {error ? (
        <p
          role="alert"
          className="mt-4 rounded-card bg-level-critical-soft p-3 text-sm text-danger-text"
        >
          {error}
        </p>
      ) : null}
      {saved ? (
        <p
          role="status"
          className="mt-4 rounded-card bg-level-low-soft p-3 text-sm text-success-text"
        >
          Saved.
        </p>
      ) : null}

      <form
        onSubmit={submit}
        aria-labelledby="ob-settings"
        className="mt-4 rounded-card border border-border bg-surface p-5 shadow-rest"
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="amber" className="block text-sm font-medium text-content">
              Amber warning threshold (% of TAT used)
            </label>
            <p className="mt-0.5 text-caption text-content-muted">
              Warn at this share of a step's turnaround time. Must be below 100 — a
              threshold of 100% fires at the same moment as the breach it is meant to
              precede.
            </p>
            <Input
              id="amber"
              className="mt-1 w-32"
              type="number"
              min={1}
              max={99}
              value={draft.amberThresholdPercent}
              onChange={(e) => setDraft({ ...draft, amberThresholdPercent: e.target.value })}
              required
            />
          </div>
          <div>
            <label htmlFor="cadence" className="block text-sm font-medium text-content">
              Scanner cadence
            </label>
            <select
              id="cadence"
              className="mt-1 block h-10 w-full rounded-control border border-border bg-surface px-3 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
              value={draft.scannerIntervalMinutes}
              onChange={(e) => setDraft({ ...draft, scannerIntervalMinutes: e.target.value })}
            >
              {cadenceOptions.map((minutes) => (
                <option key={minutes} value={minutes}>
                  Every {minutes} {minutes === '1' ? 'minute' : 'minutes'}
                </option>
              ))}
            </select>
          </div>
        </div>

        <h2 className="mt-6 text-h3 text-content">Escalation matrix on TAT breach</h2>
        <p className="mt-0.5 text-caption text-content-muted">
          Working hours after the breach, not clock hours — a Friday evening breach
          escalates on Monday morning. Each rung must fire after the one before it.
        </p>

        <TableContainer className="mt-3">
          <Table>
            <TableHeader>
              <tr>
                <TableHead scope="col" className="w-16">
                  Level
                </TableHead>
                <TableHead scope="col">Who is notified (email + WhatsApp)</TableHead>
                <TableHead scope="col" className="w-[280px]">
                  Trigger
                </TableHead>
              </tr>
            </TableHeader>
            <TableBody>
              {draft.ladder.map((rung, index) => (
                <TableRow key={rung.level}>
                  <th
                    scope="row"
                    className="px-4 py-3 text-left align-middle text-sm font-semibold text-content"
                  >
                    {rung.level}
                  </th>
                  <td className="px-4 py-3 align-middle">
                    <select
                      aria-label={`${rung.level} recipient`}
                      className="h-10 rounded-control border border-border bg-surface px-3 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
                      value={rung.recipient}
                      onChange={(e) => setDraft({
                        ...draft,
                        ladder: draft.ladder.map((r, i) =>
                          i === index
                            ? { ...r, recipient: e.target.value as ObEscalationRung['recipient'] }
                            : r),
                      })}
                    >
                      <option value="STEP_OWNER">Step owner</option>
                      <option value="BACKUP_OWNER">Backup owner</option>
                      <option value="ONBOARDING_MANAGER">Onboarding manager</option>
                      <option value="OB_ADMIN">OB admin</option>
                    </select>
                  </td>
                  <td className="px-4 py-3 align-middle">
                    <span className="flex items-center gap-2">
                      <Input
                        aria-label={`${rung.level} after working hours`}
                        className="w-24"
                        type="number"
                        min={0}
                        value={String(rung.afterWorkingHours)}
                        onChange={(e) => setDraft({
                          ...draft,
                          ladder: draft.ladder.map((r, i) =>
                            i === index
                              ? { ...r, afterWorkingHours: Number(e.target.value) }
                              : r),
                        })}
                        required
                      />
                      <span className="text-caption text-content-muted">
                        working hrs after breach
                      </span>
                    </span>
                  </td>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>

        {ladderProblem ? (
          <p role="alert" className="mt-2 text-sm text-danger-text">{ladderProblem}</p>
        ) : null}

        <div className="mt-4 flex justify-end">
          <Button type="submit" disabled={update.isPending || ladderProblem !== null}>
            {update.isPending ? 'Saving…' : 'Save settings'}
          </Button>
        </div>

        {query.data?.settings.updatedBy ? (
          <p className="mt-4 text-caption text-content-muted">
            Last changed by {query.data.settings.updatedBy.displayName}
            {query.data.settings.updatedAt
              ? ` on ${new Date(query.data.settings.updatedAt).toLocaleString()}`
              : ''}.
          </p>
        ) : null}
      </form>

      <section
        aria-labelledby="ob-clock-rules"
        className="mt-4 rounded-card border border-border bg-surface p-5 shadow-rest"
      >
        <h2 id="ob-clock-rules" className="text-h3 text-content">
          Clock rules (fixed by design)
        </h2>
        <ul className="mt-2.5 flex list-disc flex-col gap-1.5 pl-5 text-sm text-content">
          <li>
            <strong>Waiting on client</strong> pauses the TAT clock — that time is
            attributed to the client, not the owner.
          </li>
          <li>
            <strong>Blocked (internal)</strong> does <strong>not</strong> pause the
            clock — an internal blockage is still our time. A Manager may pause with a
            logged reason.
          </li>
          <li>
            Weekends, org holidays and the owner's approved leave never count against a
            TAT.
          </li>
        </ul>
      </section>
    </div>
  );
}

/** The prototype's three cadences. Stored as strings because the draft is. */
const CADENCE_MINUTES = ['2', '5', '15'];

interface Draft {
  amberThresholdPercent: string;
  scannerIntervalMinutes: string;
  ladder: ObEscalationRung[];
}

/**
 * The ascending rule, checked here as well as on the server.
 *
 * Not a substitute for the server's check — that one is authoritative and this
 * screen is not the only caller. It exists so the admin is told before they
 * press Save rather than after, which is C-109's argument about the completion
 * gate: an owner who presses a button and reads a reason code has been sent
 * looking for something the form in front of them could have said.
 */
function firstLadderProblem(ladder: ObEscalationRung[]): string | null {
  for (let i = 1; i < ladder.length; i++) {
    if (ladder[i].afterWorkingHours <= ladder[i - 1].afterWorkingHours) {
      return `${ladder[i].level} must fire after ${ladder[i - 1].level}. `
        + 'A ladder whose last rung reaches a manager before the owner it was meant to '
        + 'give a chance to is not a ladder.';
    }
  }
  return null;
}

function messageFor(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.status === 412) {
      return 'Somebody else changed these settings while you were editing. '
        + 'Reload the page and reapply your change.';
    }
    if (caught.status === 403) {
      return 'Onboarding settings are OB Admin only.';
    }
  }
  return 'That did not save. Please try again.';
}

export default ObSettingsPage;
