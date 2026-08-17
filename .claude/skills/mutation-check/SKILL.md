---
name: mutation-check
description: Prove an EduTrack test suite actually tests something by mutating the code under test and confirming a test fails. Invoke when asked to mutation-test, to check test quality, to verify tests are meaningful, or before claiming a security or append-only guarantee is covered. Catches tests that pass whatever the code does.
---

# Mutation check

A green suite proves the tests ran. It does not prove they would have noticed.
This asks the only question that settles it: **if I break this line, does a test
go red?**

There is no PIT harness in this repo. It is done by hand, which is fine — the
value is in choosing five mutations that matter, not five hundred that do not.

## Choose the mutations

Pick lines where being wrong would be *silent*. In this codebase that is,
roughly in order:

1. **Scope predicates** — anything in `ScopedTickets` or a repository `WHERE`
   that narrows rows by user. A broken one leaks another project's tickets and
   every test still passes, because the fixtures are usually all visible.
2. **Authorisation conditions** — `requested_by_id <> :senderId`,
   `asked_of_id = :senderId OR t.assigned_to = :senderId`, `@PreAuthorize`.
3. **Append-only guards** — anything that would let a `ticket_history` row be
   updated rather than compensated.
4. **The `WHERE` clause of a conditional UPDATE** — `answered_at IS NULL` is
   what makes a claim single-winner. Drop it and the tests still pass, because
   nothing in the happy path races.
5. **Boundary and null handling** — `>=` vs `>`, a tombstone's `isDeleted`
   guard, working-hours edges.

Do **not** mutate getters, DTO plumbing, logging, or anything whose failure
would be loud. A caught mutation there tells you nothing you did not know.

## Run one mutation

```bash
# 1. record the file so nothing is lost
cp <file> /tmp/mutation-backup.java

# 2. make ONE change
# 3. run only the tests that should catch it, for speed
cd backend && ./mvnw -q -pl api test -Dtest=StatusRequestIT

# 4. restore, always, before the next one
cp /tmp/mutation-backup.java <file>
```

Restore before every next mutation. Two live at once and you cannot attribute
which one a failure belongs to — this produced a round of noisy re-runs on D-045
that had to be redone one at a time.

## The three verdicts, and two of them are traps

**CAUGHT** — a test failed. Good, and only if:

> **The failure is a test assertion, not a compile error.**
>
> A mutation that does not compile is not caught by anything. `javac` refusing
> your edit tells you nothing about the suite. Read the output: `BUILD FAILURE`
> with a compilation error is a **void run** — reshape the mutation so it
> compiles, and try again.

**SURVIVED** — nothing failed. Before writing a test, ask which of these it is:

- *The line is untested.* Write the test. This is the finding you wanted.
- *The mutation was a no-op.* This is the trap that wasted a D-054 round: the
  "mutation" swapped one `ScopedTickets` call for another, and both applied the
  guard, so nothing changed semantically. A mutation must alter behaviour for
  some input, or SURVIVED means nothing.
- *The line is unreachable through the tests' path.* D-025 and D-056 both hit
  this — `requested_by_id <> :senderId` cannot be exercised while every fixture
  user is a different person. The fix is a test that gets to the line, not a
  test that asserts around it.

**VOID** — did not compile, or two mutations were live. Redo it.

## After a survivor, check the test you just wrote

Write the test, confirm it goes red under the mutation, restore, confirm it
goes green. A test that passes both ways proves nothing and is worse than
absent, because it looks like coverage.

Two real examples worth keeping in mind:

- A test "covering" an `isDeleted` guard passed with the guard removed, because
  a tombstone's body is already null — it was proving the withholding, not the
  guard. The comment on it said otherwise, which is how it survived review.
- Every fixture user in the chat integration tests was `ADMIN`, so four
  "may not ask" authorisation tests were passing for the wrong reason across
  two features. Fixed at the source — `insertUser` now defaults to `DEVELOPER` —
  not by patching each test.

Fixture defects are the common root cause. When two unrelated tests both look
too easy to satisfy, suspect the fixture before the tests.

## Report

Per mutation: the file and line, what was changed, the verdict, and — for a
survivor — which of the three explanations it was. State the count of void runs
if there were any; a mutation session where three of five did not compile is not
a clean bill of health, and reporting "2 caught" from it would be false.

Restore the file. Verify with `git status` before you finish — a mutation left
in the working tree is a deliberate bug that looks like a typo.
