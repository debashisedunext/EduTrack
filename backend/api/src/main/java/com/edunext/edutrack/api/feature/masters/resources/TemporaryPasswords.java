package com.edunext.edutrack.api.feature.masters.resources;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * B-011 · the auto-generated temporary password S-08's Access section asks for.
 *
 * <p>Blueprint §10.3 sets the rules it has to satisfy: at least 8 characters
 * with an upper-case letter, a lower-case letter, a digit and a symbol. Those
 * rules are enforced on user-chosen passwords by {@code @ValidPassword}, which
 * is package-private in {@code feature.auth} and validates a request field —
 * neither of which helps a generator. What this class must guarantee instead is
 * that <b>every</b> string it produces would pass that annotation, and
 * {@code TemporaryPasswordsTest} asserts exactly that over a few thousand
 * samples rather than trusting the construction.
 *
 * <h2>Why the alphabets have holes in them</h2>
 *
 * <p>{@code O}/{@code 0} and {@code l}/{@code I}/{@code 1} are absent. A
 * temporary password is read off a screen and typed into a different one, often
 * from a screenshot pasted into a chat window, and the character somebody
 * mistypes is always one of those five. A failed first login on a fresh account
 * looks identical to a provisioning bug, and the admin who created the account
 * is the person who gets asked about it.
 *
 * <p>The symbol set is similarly narrowed. {@code ` " ' \ $ ;} and the
 * bracket family are excluded — not because anything here is unsafe with them
 * (the password is bound as a parameter and hashed before it is stored), but
 * because this string is going to be pasted into a shell, an email and a chat
 * message on its way to its owner, and each of those has its own opinion about
 * a backtick.
 *
 * <h2>Sixteen characters, not eight</h2>
 *
 * <p>Eight is the floor for a password a human chose and will remember. Nobody
 * remembers this one — it is used once and replaced, because the account is
 * created with {@code must_change_password} set. The only cost of length here
 * is transcription, which the alphabet choice above already addresses, and the
 * benefit is that a password sitting in an inbox until somebody gets round to
 * logging in is not worth attacking.
 */
final class TemporaryPasswords {

    private static final String UPPER = "ABCDEFGHJKMNPQRSTUVWXYZ";     // no I, no O
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";   // no l
    private static final String DIGIT = "23456789";                    // no 0, no 1
    private static final String SYMBOL = "!#%*+-=?@^_~";

    private static final String ALL = UPPER + LOWER + DIGIT + SYMBOL;

    private static final int LENGTH = 16;

    /**
     * Not {@code Random}, and not {@code ThreadLocalRandom}.
     *
     * <p>Both are seeded predictably enough that two accounts created in the
     * same second could be guessable from each other. This is a credential.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    /**
     * @return a password that satisfies §10.3 by construction, every time
     */
    static String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);

        // One of each class first, so the four rules cannot fail. Drawing 16
        // uniformly from the union and retrying on a miss would also work and
        // has an unbounded worst case for no benefit.
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGIT));
        chars.add(pick(SYMBOL));

        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }

        // Without this the first four positions would always be
        // upper-lower-digit-symbol, which hands an attacker three quarters of
        // the search space back — and would make every temporary password in
        // the system recognisable on sight as one.
        shuffle(chars);

        StringBuilder password = new StringBuilder(LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }

    /**
     * Fisher-Yates over {@link #RANDOM}.
     *
     * <p>{@code Collections.shuffle(list)} without a source uses a shared
     * {@code Random}, which is the thing the field comment rules out. The
     * two-argument form would be equivalent; this is written out so that a
     * later edit cannot drop the argument and silently downgrade the source.
     */
    private static void shuffle(List<Character> chars) {
        for (int i = chars.size() - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            Character swap = chars.get(i);
            chars.set(i, chars.get(j));
            chars.set(j, swap);
        }
    }
}
