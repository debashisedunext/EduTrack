package com.edunext.edutrack.domain;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The {@code domain} module carries no application class of its own — {@code
 * api} and {@code worker} each bring theirs. {@code @DataJpaTest} walks up the
 * package tree looking for a {@code @SpringBootConfiguration}, so B-005's
 * mapping test needs one here or it cannot build a context at all.
 *
 * <p>Test scope only. Nothing ships with this.
 */
@SpringBootApplication
class DomainTestApplication {
}
