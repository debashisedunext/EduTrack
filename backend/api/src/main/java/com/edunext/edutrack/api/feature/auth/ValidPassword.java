package com.edunext.edutrack.api.feature.auth;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A-028 · the composition half of blueprint §10.3 — "min 8 chars with upper +
 * lower + digit + symbol".
 *
 * <h2>Why an annotation rather than a check in the service</h2>
 *
 * <p>PLAN.md §2.2 (deviation D-4) makes Bean Validation the single source of
 * truth for request-shape rules: springdoc turns these into schema, orval turns
 * that into Zod, and the browser and the server enforce one rule written once.
 * A composition check buried in a service is invisible to both, so the frontend
 * would have to reimplement it and the two would drift — and the drift shows up
 * as a form that accepts a password the server then rejects.
 *
 * <p>The no-reuse rule is <b>not</b> here, and cannot be: it needs the user's
 * identity and three database rows, none of which a constraint validator has.
 * That half lives in {@link PasswordPolicy}. The split is principled rather than
 * accidental — this annotation expresses everything decidable from the request
 * alone, and the service expresses what needs the account's state.
 *
 * <h2>Why not a single regex</h2>
 *
 * <p>The idiomatic one-liner is four lookaheads:
 * {@code ^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,128}$}. It works,
 * and it is write-only: nobody edits it confidently, and — the part that matters
 * to a user — it can only ever answer "no". {@link PasswordComplexityValidator}
 * checks the four classes separately so the message can say <i>which</i> one is
 * missing, which is the difference between a form someone can complete and one
 * they abandon.
 *
 * <h2>Where the rule itself lives</h2>
 *
 * <p>{@link PasswordComplexity}, since B-013. This annotation is how a
 * <i>request field</i> opts into §10.3; the class is the rule, and it is public
 * because Stream B's Resource Master generates temporary passwords and has to
 * produce strings this would accept. This annotation stays package-private —
 * nothing outside {@code feature.auth} annotates a field with it, and a caller
 * that needs the policy needs to ask it rather than declare it.
 *
 * <h2>Where the bounds live</h2>
 *
 * <p>Length stays on {@code @Size} at the field, because 8–128 is the contract's
 * {@code Password} schema and springdoc emits {@code minLength}/{@code maxLength}
 * from it directly. This annotation deliberately does not re-state length: two
 * declarations of one bound is how they come to disagree.
 *
 * <p><b>Contract note, flagged for Debashis rather than edited quietly</b>
 * (Stream D owns {@code contracts/openapi.yaml}): the {@code Password} schema
 * describes these rules in its {@code description} but carries no
 * {@code pattern}, so the generated client does not enforce them. Adding one
 * would complete the D-4 loop. Until then this is server-side only, and the
 * server is the side that counts.
 */
@Documented
@Constraint(validatedBy = PasswordComplexityValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface ValidPassword {

    String message() default "Password must contain an upper-case letter, a lower-case letter, "
            + "a digit and a symbol.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
