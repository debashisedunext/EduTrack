package com.edunext.edutrack.domain.journal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A-040 · a declared, reasoned bypass of {@link TicketJournal}.
 *
 * <p>{@code AppendOnlyRulesTest} forbids every class outside
 * {@code domain.journal} from holding one of the three append-only
 * repositories. A class that genuinely must — seed data with no caller and no
 * chain to extend — says so here, at the site, with the reason inline.
 *
 * <p>The form is lifted from {@code @UnscopedAccess} (A-037) and so is the
 * argument: an exemption list lives in a test file nobody opens except when it
 * fails, so it only ever grows, and a name added to it is invisible at the place
 * the bypass actually happens. Declared here it is greppable in one command and
 * shows up in the diff of the class that took it.
 *
 * <p>The reason is mandatory and the rule rejects a blank one, because
 * {@code @DirectAppend("")} is an exemption list with extra steps.
 *
 * <h2>What it costs to hold one</h2>
 *
 * <p>Everything the journal does is skipped: the per-ticket lock (PLAN.md §3.7),
 * the refusal of caller-written hashes, the correction pair, the one-open-hop
 * invariant behind {@code current_ticket_id}. From A-042 onwards it also means
 * rows written with no {@code row_hash} at all, which A-044's nightly verifier
 * has to either special-case or report. That is the real price, and it is why
 * this should be a short list that gets shorter.
 *
 * <p>It grants nothing at runtime. The A-008 triggers and the A-010 grants still
 * apply; the only thing waived is the build-time rule.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DirectAppend {

    /** Why this class writes an append-only table without the journal. Must not be blank. */
    String value();
}
