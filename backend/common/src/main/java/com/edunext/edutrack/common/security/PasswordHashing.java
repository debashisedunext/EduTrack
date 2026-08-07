package com.edunext.edutrack.common.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A-020 · the one place EduTrack turns a password into a hash.
 *
 * <p>Parameters are blueprint §10.3, restated in PLAN.md §2.1: <b>Argon2id,
 * 64 MB of memory, 3 iterations</b>. They are constants rather than
 * configuration on purpose — the cost parameters are encoded into every stored
 * hash, so raising them later is a migration (re-hash on next successful
 * login), not a config change. A tunable knob invites someone to lower it in
 * an environment where logins feel slow, which is precisely the environment
 * where the hashes are worth the least.
 *
 * <p><b>Why this lives in {@code common}.</b> TEAM-PLAN.md §6 assigns hashing
 * here because more than one stream writes passwords: A-026 (forced change),
 * A-027 (reset) and Stream B's Resource Master all create them, while only
 * A-020 verifies them. A second encoder built with different parameters would
 * not fail loudly — it would write hashes that verify as <i>wrong password</i>
 * forever after.
 *
 * <p><b>Constant-time comparison</b> is {@link Argon2PasswordEncoder}'s own
 * guarantee: {@code matches} compares the derived bytes without an early
 * return, so it leaks nothing about how much of the hash matched. The other
 * half of A-020's timing requirement — not being fast when the <i>user</i> is
 * unknown — cannot live here; it belongs to the login flow and is handled by
 * the authentication service.
 */
public final class PasswordHashing {

    /** 64 MB, expressed in KiB as Argon2 specifies it. Blueprint §10.3. */
    private static final int MEMORY_KIB = 64 * 1024;

    /** Time cost. Blueprint §10.3. */
    private static final int ITERATIONS = 3;

    /**
     * Single-threaded. Argon2's parallelism is a memory-bandwidth trade, and a
     * value above 1 costs a thread per concurrent login for no defensive gain
     * at this memory setting.
     */
    private static final int PARALLELISM = 1;

    /** Both are the Argon2 reference defaults, in bytes. */
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;

    private PasswordHashing() {
    }

    /**
     * A {@link PasswordEncoder} configured to §10.3. Instances are stateless
     * and thread-safe; callers should hold one rather than build per request,
     * since construction is not the expensive part but hashing is.
     */
    public static PasswordEncoder argon2id() {
        return new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
