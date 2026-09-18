import type { Db, ObJourney, ObStep } from '../db';

/**
 * Which implementation stage a running task belongs to.
 *
 * <h2>One copy, because here we can have one</h2>
 *
 * <p>The server folds stage groups onto implementation stages in three separate
 * SQL statements — `ObProjectReadRepository.STAGE_ROLLUP`,
 * `ObClientReadRepository#stepDotsOf` and `ObJourneyReadRepository#stagesOfJourney`
 * — and each carries a javadoc warning that the expression is copied and must
 * stay copied, because a fourth reader folding differently would produce a stage
 * that looks populated and opens empty.
 *
 * <p>The mock has no such constraint: it is TypeScript, so the fold lives here
 * once and every handler imports it. `onboardingProjects` builds the ribbon from
 * it and `onboardingSteps` files each task under it, so the two cannot disagree
 * about a key — which is the whole failure the server's warnings are about.
 *
 * <h2>The three fallbacks are the server's `COALESCE`, in order</h2>
 *
 * <ol>
 *   <li>the implementation stage where the group has one;</li>
 *   <li>the negated group id for the "Ungrouped" bucket, negated so it can
 *       never collide with a real stage id;</li>
 *   <li>`0` for a task whose template row has gone — counted rather than
 *       dropped, because dropping it leaves a plausible-looking percentage
 *       computed over the wrong total.</li>
 * </ol>
 */
export interface StageRef {
  key: number;
  name: string;
  sequence: number;
}

export const UNGROUPED_SEQUENCE = 9999;

export function stageOfStep(db: Db, journey: ObJourney, step: ObStep): StageRef {
  const templateSteps = db.obJourneyTemplateSteps.filter((t) => t.templateId === journey.templateId);
  /*
    By id where the fixture carries one, by name otherwise. The clone copies the
    name, so the fallback is exact for every seeded journey — and a step that
    matches neither still lands in the `0` bucket rather than vanishing.
  */
  const templateStep =
    templateSteps.find((t) => t.id === step.templateStepId)
    ?? templateSteps.find((t) => t.name === step.name);
  const group = db.obJourneyTemplateStages.find((g) => g.id === templateStep?.templateStageId);

  return {
    key: group?.implementationStageId ?? (group ? -group.id : 0),
    name: group?.name ?? 'Ungrouped',
    sequence: group?.sequence ?? UNGROUPED_SEQUENCE,
  };
}

/**
 * Every stage a journey's template publishes, whether or not it holds a task.
 *
 * <p>The roll-up is driven from these rather than from the tasks, so a stage
 * the template publishes and schedules nothing into still answers with
 * `taskCount: 0`. Five of the seven stages on the seeded corpus are in exactly
 * that state, and hiding them would make the ribbon a different length for
 * every module service.
 */
export function publishedStages(db: Db, journey: ObJourney): StageRef[] {
  return db.obJourneyTemplateStages
    .filter((g) => g.templateId === journey.templateId)
    .map((g) => ({
      key: g.implementationStageId ?? -g.id,
      name: g.name,
      sequence: g.sequence,
    }));
}
