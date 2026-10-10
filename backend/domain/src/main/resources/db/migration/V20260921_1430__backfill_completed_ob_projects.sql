-- =====================================================================
-- Projects that finished before anything stamped them finished.
--
-- Tables: ob_projects (data only — no DDL)
--
-- WHY THIS EXISTS
--
-- `ObProject.complete()` is documented as the earned transition — "every
-- journey of this project has completed" — and `ObProjectWriteService`
-- refuses a hand-set COMPLETED on exactly that ground. Nothing ever
-- called it. So `ob_projects.status` stayed RUNNING through projects
-- whose every module service had landed, and the Projects grid and the
-- project header, which both read that status, said so: a project
-- showing 100%, 1/1 modules done and 3 of 3 tasks completed was headed
-- "On time" rather than "Completed".
--
-- The caller now exists, in `ObJourneyStepLifecycleService.settleJourney`
-- beside the journey's own completion stamp. But it only fires when a
-- journey lands, and for a project that finished last month that event
-- is in the past and is not coming back. Without this backfill every
-- already-finished project stays RUNNING for ever.
--
-- WHAT IT TOUCHES
--
-- A project is finished here on the same condition the service uses: it
-- has at least one live journey, and no live journey is still running.
--
--   * Archived journeys are excluded, exactly as the service's own query
--     excludes them — a module service withdrawn from a client is not
--     work the project is waiting on, and counting it would leave the
--     project permanently one journey short of complete.
--   * A project with no journey at all is skipped. Nothing has finished
--     there; it has not started.
--   * Only RUNNING moves. ON_HOLD and DROPPED record a decision somebody
--     made rather than progress, and a backfill must not overwrite it.
--
-- `status_reason` is cleared with the status, which is what `complete()`
-- does: a reason belongs to the state it was written for.
--
-- This is data, not schema, and it is one-way in the ordinary sense —
-- re-running it is a no-op because the rows it moves no longer match.
-- =====================================================================

UPDATE ob_projects p
   SET p.status = 'COMPLETED',
       p.status_reason = NULL
 WHERE p.status = 'RUNNING'
   AND EXISTS (SELECT 1
                 FROM ob_journeys j
                WHERE j.project_id = p.id
                  AND j.archived_at IS NULL)
   AND NOT EXISTS (SELECT 1
                     FROM ob_journeys j
                    WHERE j.project_id = p.id
                      AND j.archived_at IS NULL
                      AND j.completed_at IS NULL);
