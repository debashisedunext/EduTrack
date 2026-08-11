package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.common.security.PasswordHashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-029 · recovery codes — the way back in when the authenticator is gone.
 *
 * <p>A real {@code PasswordEncoder} is used for the redemption tests rather than
 * a mock: whether a code the user types matches the hash that was stored is
 * exactly the thing worth proving, and stubbing {@code matches} would assert
 * only that the service calls it.
 */
class RecoveryCodeServiceTest {

    private static final long USER_ID = 42L;

    private RecoveryCodeRepository codes;
    private PasswordEncoder passwordEncoder;
    private RecoveryCodeService service;

    @BeforeEach
    void setUp() {
        codes = mock(RecoveryCodeRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        // An unstubbed encoder returns null, and Mockito's anyString() does not
        // match null — so without this the "a hash was stored" assertions fail
        // on the argument matcher rather than on anything real.
        when(passwordEncoder.encode(anyString())).thenReturn("{argon2id}$hashed");
        service = new RecoveryCodeService(codes, passwordEncoder,
                new TotpProperties(null, null, null, null));
    }

    /** The real encoder, for the tests that verify a typed code against a stored hash. */
    private RecoveryCodeService serviceWithRealHashing() {
        return new RecoveryCodeService(codes, PasswordHashing.argon2id(),
                new TotpProperties(null, null, null, null));
    }

    // ── generation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the configured number of codes is issued")
    void issuesTheConfiguredCount() {
        List<String> issued = service.regenerateFor(USER_ID);

        assertThat(issued).hasSize(10);
        verify(codes, times(10)).insert(eq(USER_ID), anyString());
    }

    /**
     * A user who regenerates because they believe the old list was exposed must
     * not still have the exposed list working.
     */
    @Test
    @DisplayName("regenerating replaces the previous set rather than adding to it")
    void regeneratingReplacesTheOldSet() {
        service.regenerateFor(USER_ID);

        var order = inOrder(codes);
        order.verify(codes).deleteAllFor(USER_ID);
        order.verify(codes, times(10)).insert(eq(USER_ID), anyString());
    }

    /**
     * The plaintext exists exactly once — in what is returned here. Storing it
     * would make the whole hashed-at-rest posture pointless.
     */
    @Test
    @DisplayName("only hashes are persisted, never the codes themselves")
    void onlyHashesArePersisted() {
        when(passwordEncoder.encode(anyString())).thenReturn("{argon2id}$hashed");

        List<String> issued = service.regenerateFor(USER_ID);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(codes, times(10)).insert(eq(USER_ID), stored.capture());
        assertThat(stored.getAllValues()).allMatch(hash -> hash.equals("{argon2id}$hashed"));
        assertThat(stored.getAllValues()).doesNotContainAnyElementsOf(issued);
    }

    @Test
    @DisplayName("every issued code is unique")
    void issuedCodesAreUnique() {
        assertThat(service.regenerateFor(USER_ID)).doesNotHaveDuplicates();
    }

    /**
     * These are read off paper and typed by a person under stress. I, L, O are
     * excluded because they are read as 1 and 0; U because dropping it makes
     * accidental words far less likely.
     */
    @Test
    @DisplayName("codes avoid the characters that are misread on paper")
    void codesAvoidAmbiguousCharacters() {
        List<String> issued = service.regenerateFor(USER_ID);

        assertThat(issued).allSatisfy(code -> {
            assertThat(code).matches("[0-9A-Z]{5}-[0-9A-Z]{5}");
            assertThat(code).doesNotContain("I").doesNotContain("L")
                    .doesNotContain("O").doesNotContain("U");
        });
    }

    // ── redemption ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a code issued here verifies against the hash that was stored")
    void anIssuedCodeRedeems() {
        RecoveryCodeService real = serviceWithRealHashing();

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        List<String> issued = real.regenerateFor(USER_ID);
        verify(codes, times(10)).insert(eq(USER_ID), hashes.capture());

        when(codes.findUnused(USER_ID)).thenReturn(
                List.of(new RecoveryCodeRepository.StoredRecoveryCode(7L, hashes.getAllValues().getFirst())));
        when(codes.markUsed(eq(7L), any(Instant.class))).thenReturn(true);

        assertThat(real.redeem(USER_ID, issued.getFirst())).isTrue();
        verify(codes).markUsed(eq(7L), any(Instant.class));
    }

    /**
     * Normalising has to be applied identically when storing and redeeming. If
     * the two ever diverge every code silently stops working, and it looks like
     * the user mistyping rather than a bug.
     */
    @ParameterizedTest
    @ValueSource(strings = {"lower", "nohyphen", "spaces"})
    @DisplayName("a code redeems however the user retypes it")
    void redemptionNormalisesInput(String style) {
        RecoveryCodeService real = serviceWithRealHashing();

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        List<String> issued = real.regenerateFor(USER_ID);
        verify(codes, times(10)).insert(eq(USER_ID), hashes.capture());

        String code = issued.getFirst();
        String retyped = switch (style) {
            case "lower" -> code.toLowerCase(java.util.Locale.ROOT);
            case "nohyphen" -> code.replace("-", "");
            default -> code.replace("-", " ");
        };

        when(codes.findUnused(USER_ID)).thenReturn(
                List.of(new RecoveryCodeRepository.StoredRecoveryCode(7L, hashes.getAllValues().getFirst())));
        when(codes.markUsed(eq(7L), any(Instant.class))).thenReturn(true);

        assertThat(real.redeem(USER_ID, retyped)).isTrue();
    }

    @Test
    @DisplayName("an unrecognised code is refused and spends nothing")
    void anUnknownCodeIsRefused() {
        RecoveryCodeService real = serviceWithRealHashing();
        real.regenerateFor(USER_ID);

        when(codes.findUnused(USER_ID)).thenReturn(List.of(
                new RecoveryCodeRepository.StoredRecoveryCode(
                        7L, PasswordHashing.argon2id().encode("SOMETHINGELSE"))));

        assertThat(real.redeem(USER_ID, "AAAAA-BBBBB")).isFalse();
        verify(codes, never()).markUsed(anyLong(), any());
    }

    /**
     * The row-level {@code UPDATE … WHERE used_at IS NULL} is the arbiter. A
     * concurrent redemption that already won makes this call report false, so
     * exactly one caller ever spends a code.
     */
    @Test
    @DisplayName("losing the race to spend a code reports failure")
    void losingTheRaceReportsFailure() {
        RecoveryCodeService real = serviceWithRealHashing();
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        List<String> issued = real.regenerateFor(USER_ID);
        verify(codes, times(10)).insert(eq(USER_ID), hashes.capture());

        when(codes.findUnused(USER_ID)).thenReturn(
                List.of(new RecoveryCodeRepository.StoredRecoveryCode(7L, hashes.getAllValues().getFirst())));
        when(codes.markUsed(eq(7L), any(Instant.class))).thenReturn(false);

        assertThat(real.redeem(USER_ID, issued.getFirst())).isFalse();
    }

    /**
     * Stopping at the first match would let a caller learn roughly where in the
     * list their code sat from how long the answer took.
     */
    @Test
    @DisplayName("every stored hash is checked even after a match")
    void doesNotShortCircuitOnAMatch() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(codes.findUnused(USER_ID)).thenReturn(List.of(
                new RecoveryCodeRepository.StoredRecoveryCode(1L, "hash-1"),
                new RecoveryCodeRepository.StoredRecoveryCode(2L, "hash-2"),
                new RecoveryCodeRepository.StoredRecoveryCode(3L, "hash-3")));
        when(codes.markUsed(anyLong(), any())).thenReturn(true);

        service.redeem(USER_ID, "AAAAA-BBBBB");

        verify(passwordEncoder).matches(anyString(), eq("hash-1"));
        verify(passwordEncoder).matches(anyString(), eq("hash-2"));
        verify(passwordEncoder).matches(anyString(), eq("hash-3"));
        // The first match wins, despite all three being checked.
        verify(codes).markUsed(eq(1L), any());
    }

    @Test
    @DisplayName("a blank submission is refused without a database read")
    void aBlankSubmissionIsRefused() {
        assertThat(service.redeem(USER_ID, null)).isFalse();
        assertThat(service.redeem(USER_ID, "   ")).isFalse();

        verify(codes, never()).findUnused(anyLong());
    }

    @Test
    @DisplayName("only unused codes are considered")
    void onlyUnusedCodesAreConsidered() {
        when(codes.findUnused(USER_ID)).thenReturn(List.of());

        assertThat(service.redeem(USER_ID, "AAAAA-BBBBB")).isFalse();

        // findUnused, not findAll — a spent code can never match again, so
        // verifying against it costs an Argon2id run for nothing.
        verify(codes).findUnused(USER_ID);
    }
}
