import * as React from 'react';

import type { ObEscalationRung } from '@/api/generated/model/obEscalationRung';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import { useObSettings, useUpdateObSettings } from './obSettingsQueries';

/**
 * OB-11 — TAT settings. B-113.
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
    return <p className="p-6 text-sm text-slate-500">Loading…</p>;
  }
  if (query.isError) {
    return (
      <p role="alert" className="m-6 rounded-md bg-red-50 p-3 text-sm text-red-800">
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
    <form onSubmit={submit} className="max-w-2xl p-6" aria-labelledby="ob-settings">
      <h1 id="ob-settings" className="text-xl font-semibold text-slate-900">
        TAT settings
      </h1>
      <p className="mt-1 text-sm text-slate-600">
        Changes take effect on the scanner's next pass. Steps that have already breached
        are not re-opened and escalations already sent are not re-sent.
      </p>

      {error ? (
        <p role="alert" className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-800">{error}</p>
      ) : null}
      {saved ? (
        <p role="status" className="mt-4 rounded-md bg-emerald-50 p-3 text-sm text-emerald-900">
          Saved.
        </p>
      ) : null}

      <label htmlFor="amber" className="mt-6 block text-sm font-medium text-slate-700">
        Amber threshold (% of TAT)
      </label>
      <p className="text-xs text-slate-500">
        Warn at this share of a step's turnaround time. Must be below 100 — a threshold of
        100% fires at the same moment as the breach it is meant to precede.
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

      <label htmlFor="cadence" className="mt-4 block text-sm font-medium text-slate-700">
        Scanner interval (minutes)
      </label>
      <Input
        id="cadence"
        className="mt-1 w-32"
        type="number"
        min={1}
        max={60}
        value={draft.scannerIntervalMinutes}
        onChange={(e) => setDraft({ ...draft, scannerIntervalMinutes: e.target.value })}
        required
      />

      <h2 className="mt-8 text-sm font-semibold text-slate-900">Escalation ladder</h2>
      <p className="text-xs text-slate-500">
        Working hours after the breach, not clock hours — a Friday evening breach escalates
        on Monday morning. Each rung must fire after the one before it.
      </p>

      <table className="mt-3 w-full text-sm">
        <thead>
          <tr className="text-left text-slate-500">
            <th scope="col" className="py-1 font-medium">Level</th>
            <th scope="col" className="py-1 font-medium">After (working hours)</th>
            <th scope="col" className="py-1 font-medium">Goes to</th>
          </tr>
        </thead>
        <tbody>
          {draft.ladder.map((rung, index) => (
            <tr key={rung.level}>
              <th scope="row" className="py-1 pr-4 text-left font-normal text-slate-900">
                {rung.level}
              </th>
              <td className="py-1 pr-4">
                <Input
                  aria-label={`${rung.level} after working hours`}
                  className="w-24"
                  type="number"
                  min={0}
                  value={String(rung.afterWorkingHours)}
                  onChange={(e) => setDraft({
                    ...draft,
                    ladder: draft.ladder.map((r, i) =>
                      i === index ? { ...r, afterWorkingHours: Number(e.target.value) } : r),
                  })}
                  required
                />
              </td>
              <td className="py-1">
                <select
                  aria-label={`${rung.level} recipient`}
                  className="rounded-md border border-slate-300 p-2 text-sm"
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
            </tr>
          ))}
        </tbody>
      </table>

      {ladderProblem ? (
        <p role="alert" className="mt-2 text-sm text-red-800">{ladderProblem}</p>
      ) : null}

      <Button type="submit" className="mt-8" disabled={update.isPending || ladderProblem !== null}>
        {update.isPending ? 'Saving…' : 'Save'}
      </Button>

      {query.data?.settings.updatedBy ? (
        <p className="mt-4 text-xs text-slate-500">
          Last changed by {query.data.settings.updatedBy.displayName}
          {query.data.settings.updatedAt
            ? ` on ${new Date(query.data.settings.updatedAt).toLocaleString()}`
            : ''}.
        </p>
      ) : null}
    </form>
  );
}

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
