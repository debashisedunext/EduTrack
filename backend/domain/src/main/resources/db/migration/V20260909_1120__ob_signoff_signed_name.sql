-- =====================================================================
-- B-115 · the signatory's own name, which the acceptance record was
-- missing.
--
-- Adds `signed_name` to `ob_signoffs` and folds it into the constraint
-- that already binds the other two halves of a signature together.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-115 — "OB-09 — public
--            sign-off page"
--          contracts/openapi.yaml `ObSignoffAcceptRequest.acceptedName`
--            — required, 1..160, "typed by the signatory ... a name the
--            person entered themselves is what distinguishes acceptance
--            from a click"
--          docs/Onboarding-Module-Plan.md §8
--
-- WHY THE COLUMN DOES NOT ALREADY EXIST.
-- A-107 created this table for the tasks queued behind it and mapped
-- every column each would need — the link, the OTP, who was asked, who
-- answered, the IP and the user agent. `acceptedName` was declared later,
-- on the contract rather than on the table, and the gap only shows up at
-- the one route that has to store it. So this is an addition the original
-- design anticipated in shape but not in fact: it goes beside
-- `signed_ip` and `signed_user_agent` because it is the same kind of
-- fact, captured at the same instant, and read by the same B-116 PDF.
--
-- WHY IT IS PART OF ck_ob_signoffs_signed RATHER THAN A COLUMN THAT
-- HAPPENS TO BE FILLED.
-- The existing constraint says: signed means somebody signed, at a
-- moment. Recorded acceptance (PHASE-2-BUILD-PLAN decision 5) is the
-- v1 answer to "would this stand up", and what makes it stand up is
-- that all of it is present or none of it is. A row with `signed_at`
-- and a NULL `signed_name` is a signature nobody typed — exactly the
-- click the contract distinguishes acceptance from, and precisely what
-- B-116 would then have to print a blank line for. Binding the three
-- together at the column means a half-written acceptance is refused by
-- the database and not only by the service.
--
-- The IP and the user agent stay OUTSIDE the constraint, and the
-- asymmetry is deliberate: those are captured from the request and can
-- legitimately be absent (a proxy that strips them, a client with no
-- `User-Agent`), whereas the name is submitted by the signatory and its
-- absence means the form was not filled in. The same call
-- `ck_ob_client_requirements_met` makes about `met_by`, one table over.
--
-- WHY NOT NULL IS WRONG HERE.
-- Every PENDING, EXPIRED and CANCELLED row has no signatory and never
-- will, and there are such rows on develop already. A NOT NULL column
-- would need a backfill value that means "nobody signed this", which is
-- what NULL already means.
--
-- 160 matches the contract's maxLength, which in turn matches
-- `ob_client_contacts.name` — the signatory is normally that contact
-- typing their own name, and a column that could not hold what the
-- contact row holds would truncate the commonest input there is.
-- =====================================================================

ALTER TABLE ob_signoffs
  ADD COLUMN signed_name VARCHAR(160) NULL
    COMMENT 'B-115 · typed by the signatory on OB-09. Part of the recorded acceptance.'
    AFTER signed_by_contact_id;

-- WHY acceptance_note IS HERE TOO, AND WHY IT IS NOT objection_note.
-- `ObSignoffAcceptRequest.note` is optional and 2000 characters, and the
-- table had nowhere to put it. Accepting a field and discarding it is the
-- worst of the three options: the client types a caveat on the record
-- they are signing, gets a 200, and nobody ever sees it.
--
-- It is a second column rather than a reuse of `objection_note` because
-- the two are different facts with different consequences.
-- `ck_ob_signoffs_objection` reads "an objection carries its reason" and
-- B-117 reverts a step on the strength of it; a note attached to an
-- acceptance reverts nothing. Sharing the column would make that CHECK
-- unable to tell a reason from a remark, and would leave B-116's
-- certificate printing "objection" over a sentence that accepted.
ALTER TABLE ob_signoffs
  ADD COLUMN acceptance_note VARCHAR(2000) NULL
    COMMENT 'B-115 · optional remark typed with an acceptance. Not an objection reason.'
    AFTER signed_user_agent;

-- MySQL 8.4 cannot modify a CHECK in place; drop and re-add is the only
-- route, and both halves are in this one migration so no intermediate
-- state is ever committed.
ALTER TABLE ob_signoffs
  DROP CHECK ck_ob_signoffs_signed;

ALTER TABLE ob_signoffs
  ADD CONSTRAINT ck_ob_signoffs_signed
    CHECK ((signed_at IS NULL
            AND signed_by_contact_id IS NULL
            AND signed_name IS NULL)
        OR (signed_at IS NOT NULL
            AND signed_by_contact_id IS NOT NULL
            AND signed_name IS NOT NULL));
