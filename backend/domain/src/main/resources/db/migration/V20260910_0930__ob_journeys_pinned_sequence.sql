-- =====================================================================
-- A journey's position is pinned at instantiation, not read live from
-- the catalogue.
--
-- Source:  V20260910_0030__ob_many_active_services_per_product.sql — the
--            migration that made `ob_journey_templates.sequence` order
--            several live services within one product, and so made this
--            column's absence visible.
--          PUT /onboarding/journey-templates/order (C-123) — the OB-07
--            catalogue's up/down control, which renumbers every active
--            template 0..N-1.
--
-- WHY THE LIVE TEMPLATE SEQUENCE IS THE WRONG THING TO ORDER A SCHOOL BY.
--
-- `ObClientReadRepository.journeysOf` ordered OB-05's accordion strips by
-- `ob_journey_templates.sequence` through the journey's pinned template
-- row. That column is catalogue state, and the catalogue is reordered by
-- an admin long after clients have been boarded — so swapping two Module
-- Services in OB-07 silently reshuffled the ribbons of every school
-- already running them, including schools whose journeys had started,
-- whose steps were half done, and whose owners had been working the
-- strips in the order they were given.
--
-- That is the same class of mistake `template_id` and `service_name`
-- already exist to prevent one level down: an instantiated journey is
-- frozen against later catalogue edits, and its *position among the
-- client's other journeys* is part of what was instantiated. A school
-- boarded on Monday keeps Monday's order.
--
-- The catalogue keeps meaning what it meant — it orders instantiation and
-- it orders the next school boarded. It just stops rewriting history.
--
-- WHY NOT NULL DEFAULT 0 RATHER THAN NULLABLE.
--
-- Every journey has a position; there is no such thing as one without.
-- 0 is the catalogue's own first slot (`reorderCatalogue` numbers from
-- 0), so a row that somehow reaches this table without a sequence sorts
-- first and deterministically rather than last and by accident, and the
-- `ORDER BY` needs no COALESCE. The backfill below leaves no such row
-- behind anyway.
--
-- Not part of any unique key: two services of one product genuinely share
-- a position only if the catalogue is inconsistent, and ties break on
-- product name then service name then id, exactly as the read did before.
-- =====================================================================

ALTER TABLE ob_journeys
  ADD COLUMN sequence INT NOT NULL DEFAULT 0 AFTER template_id;

-- Every journey that exists was instantiated from a template whose
-- sequence at this moment is the closest thing there is to the sequence
-- it was born with. Not perfect for rows boarded before a reorder that
-- already happened — that history is not recoverable — but correct from
-- here on, which is the point of the column.
UPDATE ob_journeys j
  JOIN ob_journey_templates t ON t.id = j.template_id
   SET j.sequence = t.sequence;

-- The accordion reads one client's live journeys in this order and
-- nothing else does; a covering prefix keeps that a range scan rather
-- than a filesort per client.
ALTER TABLE ob_journeys
  ADD KEY ix_ob_journeys_client_sequence (ob_client_id, sequence, id);
