package com.edunext.edutrack.api.feature.fixtures;

import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * B-007 · loads the 200-ticket fixture corpus on startup, under the
 * {@code fixtures} profile.
 *
 * <pre>./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local,fixtures</pre>
 *
 * <p><b>Never a Flyway migration.</b> {@code SEED-MANIFEST.md} §5 flags this
 * directly: the corpus is dev/test data, not the reference data every
 * environment boots with, and {@code spring.flyway.locations} is one shared
 * classpath location between {@code api} and {@code worker} with nothing
 * separating a fixture migration from a real one. A profile-gated
 * {@link ApplicationRunner} is the isolation instead of a second Flyway
 * location — it simply never runs unless {@code fixtures} is explicitly
 * requested, the same guarantee {@code dev-noauth} (A-012) gives its fake
 * principal.
 *
 * <p><b>Safety, enforced the same way A-012 enforces it:</b> the constructor
 * refuses to complete unless {@code local} is also active, so a 200-ticket
 * corpus with fictional users, clients and hash-chain columns left NULL can
 * never reach a real environment. CI activates neither profile.
 *
 * <p><b>Idempotent by inspection.</b> {@link ReferenceDataFixture#alreadyLoaded()}
 * checks for the {@code CRM} project before anything is written; a second run
 * against a database that already has the corpus is a logged no-op rather
 * than a duplicate 200 tickets.
 *
 * <p><b>Two corpora, two checks.</b> B-101 adds the onboarding corpus — the
 * prototype's journey template and its eight demo clients — behind the same
 * profile and the same {@code local} refusal, but behind its own idempotency
 * check. They are separate deliberately: a developer who loaded tickets last
 * week and pulls this branch today needs the onboarding half to load without
 * the ticket half being written on top of itself, and either half is useful
 * without the other. The order is fixed rather than incidental — the
 * onboarding corpus reuses one {@code users} row that {@link
 * ReferenceDataFixture} creates, and says so if it is missing.
 */
@Component
@Profile("fixtures")
class FixtureLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FixtureLoader.class);

    private final ReferenceDataFixture referenceData;
    private final TicketFixtureGenerator ticketGenerator;
    private final OnboardingFixture onboarding;

    FixtureLoader(Environment environment, ReferenceDataFixture referenceData,
                  TicketFixtureGenerator ticketGenerator, OnboardingFixture onboarding) {
        if (!environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "The 'fixtures' profile loads 200 fictional tickets, users and clients with no "
                            + "hash-chain protection and must never run outside 'local'. Activate it as: "
                            + "local,fixtures. Refusing to start.");
        }
        this.referenceData = referenceData;
        this.ticketGenerator = ticketGenerator;
        this.onboarding = onboarding;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadTicketCorpus();
        loadOnboardingCorpus();
    }

    private void loadTicketCorpus() {
        if (referenceData.alreadyLoaded()) {
            log.info("B-007 fixture corpus already present (project CRM exists) — skipping.");
            return;
        }
        log.info("B-007: loading reference data (projects, users, clients, SLA policies)...");
        FixtureContext context = referenceData.load();

        log.info("B-007: generating 200 fixture tickets...");
        ticketGenerator.generate(context);

        log.info("B-007: fixture corpus loaded.");
    }

    private void loadOnboardingCorpus() {
        if (onboarding.alreadyLoaded()) {
            log.info("B-101 onboarding corpus already present (product ERP exists) — skipping.");
            return;
        }
        log.info("B-101: loading the onboarding corpus (journey templates, 8 clients, their journeys)...");
        onboarding.load();
        log.info("B-101: onboarding corpus loaded.");
    }
}
