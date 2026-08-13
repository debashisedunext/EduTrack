package com.edunext.edutrack.api.security.scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A-037 · a declared, reasoned bypass of {@link ScopedTickets}.
 *
 * <p>{@code ScopeGuardRulesTest} forbids every class in {@code api} outside
 * {@code security.scope} from touching {@code TicketRepository}. A handful of
 * classes legitimately must: they run with no authenticated caller, so there is
 * no scope to apply and {@link ScopedTickets} has nothing to ask. An inbound
 * email webhook is answering a mail server, not a user.
 *
 * <h2>Why an annotation and not a list in the test</h2>
 *
 * <p>A-033 set the precedent that {@code RouteAuthorizationTest} has "no
 * default and no exemption list", and the reason generalises: an exemption list
 * lives in a file nobody opens except when it fails, so it only ever grows, and
 * a name added to it is invisible at the place the bypass actually happens.
 * Here the exemption is declared <em>at the site</em>, carries the reason
 * inline, is greppable in one command, and shows up in the diff of the class
 * that took it. Adding a fourth is a visible act rather than an edit to a test.
 *
 * <p>The reason is mandatory and {@code ScopeGuardRulesTest} rejects a blank
 * one, because {@code @UnscopedAccess("")} is an exemption list with extra
 * steps.
 *
 * <h2>Class-level only</h2>
 *
 * <p>Deliberately not applicable to a method. ArchUnit resolves dependencies
 * per class, so a method-level bypass could not be enforced more narrowly than
 * this anyway — and a class that needs to read tickets unscoped should be small
 * enough that the whole class is the honest unit of the exemption. If it is
 * not, that is the finding.
 *
 * <h2>This is not authorisation</h2>
 *
 * <p>It grants nothing at runtime. It is a build-time assertion that a human
 * decided this caller has no scope to apply — not that any caller may see any
 * ticket. Anything answering an authenticated HTTP request goes through
 * {@link ScopedTickets}, and there is no reason string that changes that.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UnscopedAccess {

    /** Why this class has no caller to scope by. Must not be blank. */
    String value();
}
