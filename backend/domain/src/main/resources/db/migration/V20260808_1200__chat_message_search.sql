-- =====================================================================
-- D-053 · full-text search over chat message bodies (blueprint §7.6).
--
-- Stream D · Debashis. **Stream A please review** — this adds an index to
-- chat_messages, which D-050 also touched.
--
-- Why FULLTEXT rather than LIKE '%term%':
--   A leading wildcard cannot use an index, so every search would scan
--   chat_messages end to end. That table has no natural ceiling — it grows
--   for as long as the product is used — so the scan gets slower forever
--   while the query keeps returning the right answer. That is the shape of
--   problem nobody notices until it is expensive to fix.
--
-- InnoDB maintains this index through a deferred cache rather than
-- synchronously on INSERT, so posting a message does not pay the tokenising
-- cost inline. Chat is insert-heavy; that ordering matters.
--
-- NOTE — `innodb_ft_min_token_size` is 3 by default, so two-letter words
-- ("QA", "UI") are never indexed and cannot be found. Lowering it to 2 is a
-- server variable plus a rebuild of this index, which is an infrastructure
-- decision rather than a migration. ChatSearch.MIN_TERM_LENGTH mirrors the
-- default and ChatEngineIT asserts the two still agree.
-- =====================================================================

ALTER TABLE chat_messages
  ADD FULLTEXT KEY ft_chat_messages_body (body);
