import { Check, X } from 'lucide-react';

import { cn } from '@/lib/utils';

import { estimateStrength, evaluateRequirements } from './passwordPolicy';

/**
 * The strength meter S-02 asks for, plus the requirement checklist it needs to
 * be useful — A-030.
 *
 * ## Colour is never the only signal
 *
 * Blueprint §12.2. Each requirement carries a tick or a cross *and* its text, so
 * the list reads identically to someone who cannot distinguish the green from
 * the grey. The bar has a written label beside it for the same reason — a bar
 * that is only "more indigo" conveys nothing without colour vision, and nothing
 * at all to a screen reader.
 *
 * ## What is announced, and what is not
 *
 * The checklist is `aria-live="polite"`, because it changes as a direct result
 * of typing and a blind user otherwise has no way to know a rule was satisfied.
 * The strength bar is *not* live: it changes on nearly every keystroke, and
 * announcing "fair… fair… good" over a password field turns advice into an
 * interruption. Its value is still readable on demand through the label.
 *
 * The password itself never reaches an `aria-label`, a title or a data
 * attribute. That sounds obvious and is exactly how a near-miss password ends up
 * in a DOM snapshot in a bug report.
 */
export function PasswordStrengthMeter({
  password,
  /** Wire this to the field's `aria-describedby` so the rules are read with the input. */
  id,
}: {
  password: string;
  id: string;
}) {
  const requirements = evaluateRequirements(password);
  const strength = estimateStrength(password);

  return (
    <div id={id} className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <div
          className="flex h-1.5 flex-1 gap-1"
          // Not a progressbar role: this is an opinion about a value, not
          // progress towards completing anything, and a progressbar announces
          // itself as a task in flight.
          aria-hidden="true"
        >
          {[0, 1, 2, 3].map((segment) => (
            <div
              key={segment}
              className={cn(
                'h-full flex-1 rounded-chip transition-colors',
                segment < strength.score ? SEGMENT_COLOUR[strength.score] : 'bg-subtle',
              )}
            />
          ))}
        </div>
        <span className="w-20 text-right text-caption text-content-muted">
          {password ? strength.label : ''}
        </span>
      </div>

      {strength.hint ? (
        <p className="text-caption text-content-muted">{strength.hint}</p>
      ) : null}

      <ul className="flex flex-col gap-1" aria-live="polite">
        {requirements.map((requirement) => (
          <li
            key={requirement.id}
            className={cn(
              'flex items-center gap-1.5 text-caption',
              requirement.met ? 'text-success-text' : 'text-content-muted',
            )}
          >
            {requirement.met ? (
              <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
            ) : (
              <X aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
            )}
            {/*
              The state is in the text, not only in the icon and the colour.
              Without it a screen reader reads five identical lines whatever the
              password is, and the list becomes noise the user learns to ignore.
            */}
            <span className="sr-only">{requirement.met ? 'Met:' : 'Not met:'}</span>
            {requirement.label}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Weak reads as danger and strong as success — the semantic tokens, not new
 * colours. `tokens.css` is frozen and owned by Stream C; a bespoke red-to-green
 * ramp here would be four invented values on one screen.
 */
const SEGMENT_COLOUR: Record<number, string> = {
  0: 'bg-danger',
  1: 'bg-danger',
  2: 'bg-warning',
  3: 'bg-info',
  4: 'bg-success',
};
