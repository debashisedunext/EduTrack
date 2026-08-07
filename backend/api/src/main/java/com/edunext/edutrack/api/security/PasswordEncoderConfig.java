package com.edunext.edutrack.api.security;

import com.edunext.edutrack.common.security.PasswordHashing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A-020 · exposes the §10.3 Argon2id encoder as the application's single
 * {@link PasswordEncoder} bean.
 *
 * <p>Declaring it is not optional once {@code spring-boot-starter-security} is
 * present: Spring Security's default is
 * {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()}, which
 * hashes new passwords with <b>bcrypt</b> and prefixes them {@code {bcrypt}}.
 * That default would quietly win for anything that autowires a
 * {@code PasswordEncoder} — A-027's reset, Stream B's Resource Master — and the
 * hashes it wrote would be the wrong algorithm at the wrong cost, discovered
 * only by reading rows in the users table.
 *
 * <p>The parameters themselves live in {@code common} ({@link PasswordHashing}),
 * because Stream B writes passwords and Stream A verifies them. This class is
 * only the Spring wiring.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordHashing.argon2id();
    }
}
