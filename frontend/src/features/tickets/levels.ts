import type { Level } from '@/api/generated/model/level'
import type { Priority } from '@/api/generated/model/priority'

/**
 * C-072 · which levels a picker may offer, from S-12's priority master.
 *
 * `GET /masters/priorities` is active-only *by default*, which is the only
 * reason a retired level has not yet reached a picker — three call sites map
 * `data` straight into their options and none of them look at `isActive`.
 * That default is due to widen to match `listTaskTypes` and `listModules`
 * ([`DEPENDENCIES.md`](../../../../docs/DEPENDENCIES.md) row 23), and on the
 * day it does, every one of those pickers starts offering levels an Admin
 * retired in S-12. Filtering here rather than relying on the query parameter
 * makes the screens correct under either default, and means the widening is
 * Stream B's edit alone.
 *
 * `isActive !== false` rather than `isActive === true`: the field is optional
 * on the contract's `Priority`, and a row that omits it is a row the server
 * chose not to say anything about — treating silence as "retired" would empty
 * every picker against any response that predates the field.
 *
 * Order is the master's, untouched. S-12 owns the sequence levels appear in,
 * and `LevelPicker`'s chips are read left-to-right as a severity scale.
 */
export function selectableLevels(priorities: readonly Priority[] | undefined): Level[] {
  return (priorities ?? [])
    .filter((p) => p.isActive !== false)
    .map((p) => p.level)
    .filter((l): l is Level => l != null)
}

/**
 * The same list, plus one level that must appear whether or not the master
 * still lists it — the level a ticket is *currently* at.
 *
 * A ticket raised at CRITICAL keeps that level after CRITICAL is retired, and
 * S-20's level dialog opens with the current level checked. Drop it from the
 * options and the radio group renders with nothing selected, which reads as
 * "this ticket has no level" and makes the no-op ("I opened this by mistake")
 * unavailable. Retiring a level stops new tickets reaching it; it does not
 * force every ticket already there to move.
 *
 * Appended rather than inserted in master order, because a retired row has no
 * position in a sequence it is no longer part of.
 */
export function levelsIncludingCurrent(
  priorities: readonly Priority[] | undefined,
  current: Level,
): Level[] {
  const active = selectableLevels(priorities)
  return active.includes(current) ? active : [...active, current]
}
