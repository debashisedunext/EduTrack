/**
 * Blueprint §10.3's password rules, mirrored client-side so the user learns
 * what is wrong while typing rather than on submit.
 *
 * **The server is the authority, not this file.** `PasswordComplexityValidator`
 * and `PasswordPolicy` (A-028) decide whether a password is accepted; this is a
 * courtesy that makes the requirement visible. A screen that treats a green
 * checklist as permission to skip handling a 400 will eventually show a user a
 * satisfied checklist next to a rejected form.
 *
 * ## Two separate ideas, deliberately not merged
 *
 * A **requirement** is pass/fail and mirrors the server. A **strength estimate**
 * is advice and mirrors nothing. Collapsing them into one bar is the standard
 * mistake: `Password1!` satisfies every rule §10.3 has and is among the worst
 * passwords in existence, so a single bar would have to either call it strong —
 * lying — or call it weak while the submit button works, which reads as a bug.
 * The checklist says whether it will be accepted; the meter says whether it
 * should be.
 */

/** `Password` in the contract — `@minLength 8`, `@maxLength 128`. */
export const MIN_LENGTH = 8;
export const MAX_LENGTH = 128;

export interface PasswordRequirement {
  id: string;
  /** Phrased as the rule, not as a complaint — it is shown before anything is typed. */
  label: string;
  met: boolean;
}

/**
 * The four character classes, matched the way the server matches them.
 *
 * `PasswordComplexityValidator` uses `Character.isUpperCase` rather than `[A-Z]`
 * on purpose — passwords are `utf8mb4` end to end and an ASCII-only rule tells a
 * user whose memorable phrase is in their own alphabet that their letters do not
 * count. The Unicode property escapes below are the JavaScript equivalent; a
 * `/[A-Z]/` here would reintroduce, in the UI, exactly the bug the backend went
 * out of its way to avoid, and would show a red cross against a password the
 * server accepts.
 *
 * `Lu`/`Ll`/`Nd` correspond to Java's `isUpperCase`/`isLowerCase`/`isDigit`. The
 * mapping is exact for every alphabet a user will type and approximate only for
 * oddities like title-case digraphs, which is why the server still decides.
 */
const HAS_UPPER = /\p{Lu}/u;
const HAS_LOWER = /\p{Ll}/u;
const HAS_DIGIT = /\p{Nd}/u;

/**
 * "Symbol" by exclusion — anything that is not a letter, a digit or whitespace.
 *
 * The allow-list version (`[!@#$%^&*]`) quietly refuses `£`, `€`, `—` and every
 * mark outside someone's chosen eight, with a message insisting the user add a
 * symbol they have just added. Whitespace is excluded from *counting*, though
 * still permitted, so a trailing space cannot discharge the requirement.
 */
const HAS_SYMBOL = /[^\p{L}\p{Nd}\s]/u;

/**
 * Evaluate every rule. Always returns all five, met or not, because the
 * checklist is a specification the user reads before typing — hiding the
 * unsatisfied ones would leave an empty box on an empty field.
 */
export function evaluateRequirements(password: string): PasswordRequirement[] {
  return [
    {
      id: 'length',
      label: `At least ${MIN_LENGTH} characters`,
      met: password.length >= MIN_LENGTH && password.length <= MAX_LENGTH,
    },
    { id: 'upper', label: 'An upper-case letter', met: HAS_UPPER.test(password) },
    { id: 'lower', label: 'A lower-case letter', met: HAS_LOWER.test(password) },
    { id: 'digit', label: 'A digit', met: HAS_DIGIT.test(password) },
    { id: 'symbol', label: 'A symbol', met: HAS_SYMBOL.test(password) },
  ];
}

/** True when the server's composition and length rules would all pass. */
export function meetsPolicy(password: string): boolean {
  return evaluateRequirements(password).every((requirement) => requirement.met);
}

export type StrengthScore = 0 | 1 | 2 | 3 | 4;

export interface PasswordStrength {
  score: StrengthScore;
  label: string;
  /** Shown under the bar. Empty once there is nothing useful left to say. */
  hint: string;
}

/**
 * A handful of shapes that pass §10.3 while being trivially guessable.
 *
 * Not a dictionary — a real one is megabytes, and shipping zxcvbn to weigh a
 * bar that has no authority over anything would be a large dependency for
 * advice. These are the patterns that actually appear when a composition rule
 * is imposed: a capitalised word, a number, a bang.
 */
const PREDICTABLE = [
  /^pass/i,
  /^welcome/i,
  /^admin/i,
  /^letmein/i,
  /^qwert/i,
  /^abcd/i,
  /^edutrack/i,
  /^12345/,
];

/** Three or more of the same character in a row — `aaa`, `111`. */
const RUN_OF_SAME = /(.)\1{2,}/;

/** Four or more consecutive keyboard/alphabet steps — `abcd`, `4321`. */
function hasSequence(password: string): boolean {
  const lower = password.toLowerCase();
  let ascending = 1;
  let descending = 1;
  for (let i = 1; i < lower.length; i += 1) {
    const step = lower.charCodeAt(i) - lower.charCodeAt(i - 1);
    ascending = step === 1 ? ascending + 1 : 1;
    descending = step === -1 ? descending + 1 : 1;
    if (ascending >= 4 || descending >= 4) return true;
  }
  return false;
}

const LABELS: Record<StrengthScore, string> = {
  0: 'Very weak',
  1: 'Weak',
  2: 'Fair',
  3: 'Good',
  4: 'Strong',
};

/**
 * Advisory strength, driven mostly by length.
 *
 * Length is weighted above variety because that is what the arithmetic says:
 * adding a character multiplies the search space, while adding a fourth
 * character class multiplies it once. §10.3 already guarantees the classes, so
 * variety carries almost no information here — every password this meter sees
 * on a submitted form has all four.
 */
export function estimateStrength(password: string): PasswordStrength {
  if (!password) return { score: 0, label: LABELS[0], hint: '' };

  if (PREDICTABLE.some((pattern) => pattern.test(password))) {
    return {
      score: 0,
      label: LABELS[0],
      hint: 'This starts with a word attackers try first. A different word is worth more than another symbol.',
    };
  }

  let score = 0;
  if (password.length >= MIN_LENGTH) score += 1;
  if (password.length >= 12) score += 1;
  if (password.length >= 16) score += 1;
  if (password.length >= 20) score += 1;

  // Repetition and runs inflate length without adding entropy, so they must not
  // be allowed to buy the bonus above.
  if (RUN_OF_SAME.test(password) || hasSequence(password)) {
    score = Math.max(0, score - 2);
  }

  const bounded = Math.min(4, score) as StrengthScore;
  return {
    score: bounded,
    label: LABELS[bounded],
    hint:
      bounded >= 3
        ? ''
        : 'Length beats punctuation. Three unrelated words are stronger, and easier to remember, than a short password with more symbols.',
  };
}
