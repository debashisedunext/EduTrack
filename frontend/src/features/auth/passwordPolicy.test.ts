import { describe, expect, it } from 'vitest';

import {
  estimateStrength,
  evaluateRequirements,
  meetsPolicy,
  MAX_LENGTH,
} from './passwordPolicy';

const met = (password: string, id: string) =>
  evaluateRequirements(password).find((requirement) => requirement.id === id)?.met;

describe('the composition rules mirror the server', () => {
  it('accepts a password satisfying all four classes', () => {
    expect(meetsPolicy('Correct-Horse-1!')).toBe(true);
  });

  it.each([
    ['correct-horse-1!', 'upper'],
    ['CORRECT-HORSE-1!', 'lower'],
    ['Correct-Horse-x!', 'digit'],
    ['CorrectHorse1x', 'symbol'],
  ])('%s fails the %s rule alone', (password, id) => {
    expect(met(password, id)).toBe(false);
    expect(meetsPolicy(password)).toBe(false);
  });

  it('always returns all five requirements, even for an empty field', () => {
    // The checklist is read before anything is typed, so it cannot be a list of
    // failures — it has to be the specification.
    expect(evaluateRequirements('')).toHaveLength(5);
    expect(evaluateRequirements('').filter((requirement) => requirement.met)).toEqual([]);
  });

  it('rejects anything longer than the contract allows', () => {
    // `Password` is @maxLength 128. A field that lets someone type 200
    // characters and then fails server-side wastes the one they liked.
    expect(meetsPolicy(`Aa1!${'x'.repeat(MAX_LENGTH)}`)).toBe(false);
  });
});

describe('the classes are Unicode, not ASCII', () => {
  // `PasswordComplexityValidator` uses Character.isUpperCase rather than [A-Z]
  // precisely so a user's own alphabet counts. A regex here that disagreed
  // would show a red cross against a password the server accepts — the failure
  // mode is a user who cannot work out why the form will not let them proceed,
  // and it would only ever be hit by the people whose names are not in ASCII.
  it('counts non-ASCII letters', () => {
    expect(met('Ümlaut-Wörter-1!', 'upper')).toBe(true);
    expect(met('ümlaut-wörter-1!', 'lower')).toBe(true);
  });

  it('counts a currency mark as a symbol', () => {
    // The allow-list implementation refuses this while insisting on a symbol.
    expect(met('CorrectHorse1£', 'symbol')).toBe(true);
  });

  it('does not let whitespace discharge the symbol rule', () => {
    // A trailing space is the most common accidental "symbol" there is.
    expect(met('CorrectHorse1 ', 'symbol')).toBe(false);
  });
});

describe('the strength estimate is advice, not the rule', () => {
  it('rates a policy-satisfying but obvious password very weak', () => {
    // The whole reason strength and compliance are two separate readouts.
    expect(meetsPolicy('Password1!')).toBe(true);
    expect(estimateStrength('Password1!').score).toBe(0);
  });

  it('rewards length over punctuation', () => {
    expect(estimateStrength('correct horse battery staple').score).toBeGreaterThan(
      estimateStrength('Xk9!').score,
    );
  });

  it('does not let repetition buy length', () => {
    expect(estimateStrength('Aa1!aaaaaaaaaaaaaaaa').score).toBeLessThan(
      estimateStrength('Aa1!thicket-marrow-vane').score,
    );
  });

  it('does not let a keyboard run buy length', () => {
    expect(estimateStrength('Aa1!abcdefghijklmnop').score).toBeLessThan(
      estimateStrength('Aa1!thicket-marrow-vane').score,
    );
  });

  it('says nothing about an empty field', () => {
    expect(estimateStrength('')).toMatchObject({ score: 0, hint: '' });
  });

  it('stops advising once the password is good', () => {
    expect(estimateStrength('thicket-marrow-vane-1A').hint).toBe('');
  });
});
