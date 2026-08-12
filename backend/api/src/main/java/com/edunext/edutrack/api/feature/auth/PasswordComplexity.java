package com.edunext.edutrack.api.feature.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * B-013 · blueprint §10.3's composition rule, in one place.
 *
 * <p>This class holds no policy that was not already here. It is an extraction:
 * {@link PasswordComplexityValidator} used to own the four predicates and the
 * length bounds lived as literals on {@code @Size}, and B-013 needed a fourth
 * caller — {@code TemporaryPasswords}, in {@code feature.masters.resources},
 * which has to <b>generate</b> a password §10.3 would accept rather than judge
 * one somebody typed.
 *
 * <h2>Why this exists rather than a second copy of the rules</h2>
 *
 * <p>A generator and a validator that each state the same policy separately are
 * a pair that drifts, and drifts <b>silently</b>: nothing re-checks composition
 * at login, so if §10.3 were strengthened here and the generator left alone, the
 * organisation would go on issuing temporary passwords its own stated policy
 * rejects and no request would fail. The failure would surface as an auditor's
 * question, months later. One statement of the rule and a test on the other side
 * of the seam turns that into a red build.
 *
 * <p>Before this, the rules were written down three times in Java — here, in the
 * generator's alphabets, and a third time in {@code TemporaryPasswordsTest},
 * whose own comment named the duplication and the reason for it. That comment is
 * what this class answers.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>The no-reuse rule and the expiry.</b> Both need the account and its
 * history, so they stay in {@link PasswordPolicy}; this class is the half
 * decidable from the string alone. The split is the same one
 * {@link ValidPassword}'s javadoc draws, and moving either half across it would
 * mean a constraint validator holding a repository.
 *
 * <p><b>The annotation stays package-private.</b> Nothing outside
 * {@code feature.auth} needs to <i>annotate</i> a field with §10.3 — the two
 * request records that do are both here. What another feature needs is to
 * <i>ask</i> the rule, which is what this class is for, and widening the
 * annotation instead would have exported a Bean Validation constraint that only
 * ever has callers in one package.
 *
 * <p><b>Cross-stream note.</b> {@code feature/auth} is Stream A's path and this
 * file is Stream B's edit, made deliberately rather than quietly: §10.3 is
 * A-028's rule and the alternative was for Stream B to keep a second copy of it.
 * Nothing about what auth accepts changes — {@code PasswordComplexityValidator}
 * delegates to the same predicates it used to hold inline, and
 * {@code PasswordComplexityValidatorTest} passes unmodified, which is the point
 * of leaving it unmodified.
 */
public final class PasswordComplexity {

    /**
     * The contract's {@code Password} schema lower bound, and §10.3's "min 8
     * chars".
     *
     * <p>Public and constant so {@code @Size} can take it: springdoc emits
     * {@code minLength} from that annotation and orval turns it into Zod, so
     * this number reaches the browser. Two hand-written copies of a bound are
     * how the generated client comes to disagree with the server about what it
     * will accept.
     */
    public static final int MIN_LENGTH = 8;

    /**
     * Not decoration. Argon2id hashes whatever it is handed at 64 MB a go, so an
     * unbounded password field is a CPU-and-memory amplifier — one request with
     * a megabyte password costs the server far more than it costs the client to
     * send.
     */
    public static final int MAX_LENGTH = 128;

    private PasswordComplexity() {
    }

    /**
     * The four classes §10.3 requires, as a named set rather than four booleans.
     *
     * <p>The description is here rather than in the validator because it is the
     * only place that knows which predicate it belongs to, and a refusal that
     * says "must contain a digit" while testing for a symbol is a bug no test
     * catches by reading either half alone.
     */
    public enum CharacterClass {

        /**
         * Deliberately {@link Character#isUpperCase} rather than {@code [A-Z]}.
         * Passwords are {@code utf8mb4} end to end, and a rule that only
         * recognises ASCII tells a user whose name supplies their memorable
         * phrase that their alphabet does not count as letters.
         */
        UPPER("an upper-case letter", Character::isUpperCase),

        LOWER("a lower-case letter", Character::isLowerCase),

        DIGIT("a digit", Character::isDigit),

        /**
         * "Symbol" as everything that is not a letter, a digit or whitespace —
         * rather than a fixed list like {@code !@#$%^&*}.
         *
         * <p>An allow-list is the common implementation and it quietly refuses
         * perfectly good passwords: {@code £}, {@code €}, {@code —} and every
         * currency or punctuation mark outside somebody's chosen eight are
         * rejected with a message insisting the user add a symbol they have just
         * added. Defining the class by exclusion has no such gap.
         *
         * <p>Whitespace is excluded from <i>counting</i> — a trailing space is
         * the most common accidental "symbol" there is, and letting it satisfy
         * the rule would mean {@code "Password1 "} passes while teaching
         * nothing. Whitespace is still permitted in a password; it just does not
         * discharge this requirement.
         */
        SYMBOL("a symbol", c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));

        private final String description;
        private final CharPredicate predicate;

        CharacterClass(String description, CharPredicate predicate) {
            this.description = description;
            this.predicate = predicate;
        }

        /** The words this class contributes to a refusal message. */
        public String description() {
            return description;
        }

        /**
         * {@code String.chars()}, which is UTF-16 code <i>units</i> rather than
         * code points — carried over from the inline implementation unchanged,
         * because B-013 was an extraction and quietly widening what auth accepts
         * while claiming not to would be the worse of the two bugs.
         *
         * <p><b>The difference is real but confined to the supplementary
         * planes.</b> A letter above U+FFFF arrives as a surrogate pair, and
         * {@code isUpperCase} on a lone surrogate is false, so such a letter does
         * not discharge {@code UPPER} — it falls to {@code SYMBOL} instead, since
         * a surrogate is neither letter, digit nor whitespace. Every alphabet in
         * live use is in the BMP and unaffected; the scripts this touches are the
         * historic and constructed ones. Emoji land on {@code SYMBOL} either way.
         *
         * <p>Left as a note rather than fixed here: switching to
         * {@code codePoints()} changes what {@code @ValidPassword} accepts, which
         * is A-028's call and not a Stream B branch's. <b>Flagged for Stream A.</b>
         */
        public boolean isSatisfiedBy(String password) {
            return password.chars().anyMatch(predicate::test);
        }
    }

    /**
     * Which of the four classes a password is missing, in declaration order.
     *
     * <p>Every class is tested; there is no early exit on the first miss. That
     * is what lets a refusal name all of them at once, which is the difference
     * between a form somebody can complete and one they resubmit four times.
     *
     * <p><b>Length is not checked here.</b> It belongs to {@code @Size}, which
     * owns the message for it, and a caller asking "which classes are missing"
     * about a four-character password wants the answer to be about classes. Use
     * {@link #isCompliant} for the combined question.
     *
     * @param password never null — callers that can see a null field have an
     *                 annotation that owns that case
     * @return an empty list when §10.3's composition rule is satisfied
     */
    public static List<CharacterClass> missingClasses(String password) {
        List<CharacterClass> missing = new ArrayList<>(CharacterClass.values().length);
        for (CharacterClass characterClass : CharacterClass.values()) {
            if (!characterClass.isSatisfiedBy(password)) {
                missing.add(characterClass);
            }
        }
        return missing;
    }

    /**
     * Whether §10.3 would accept this string on composition and length together.
     *
     * <p>The question a <b>generator</b> asks, and the reason this class is
     * public. {@link PasswordComplexityValidator} does not call it — on the
     * request path the two halves are owned by two annotations and answering
     * them together would produce a refusal that names length and classes in one
     * breath. A caller producing a password rather than judging one has no such
     * split: it wants one verdict, and the bounds are as much a part of "would
     * this be accepted" as the four classes are.
     *
     * <p>Says nothing about reuse or expiry — see {@link PasswordPolicy}, which
     * needs the account this string is for.
     */
    public static boolean isCompliant(String password) {
        return password != null
                && password.length() >= MIN_LENGTH
                && password.length() <= MAX_LENGTH
                && missingClasses(password).isEmpty();
    }

    /**
     * {@code IntPredicate} under a name that says what the {@code int} is.
     *
     * <p>The argument is a UTF-16 code unit widened to {@code int}, which is what
     * {@code String.chars()} produces — see
     * {@link CharacterClass#isSatisfiedBy}. Naming it {@code CodePointPredicate}
     * would have been the flattering description and the wrong one.
     */
    @FunctionalInterface
    private interface CharPredicate {
        boolean test(int codeUnit);
    }
}
