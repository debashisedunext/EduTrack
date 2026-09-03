package com.edunext.edutrack.api.feature.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code fixtures} profile loads 200 fictional tickets, users and clients
 * with no hash-chain protection. {@link FixtureLoader} refuses to run outside
 * {@code local} — but that check only guards the loader, and the beans it
 * drives were plain {@code @Component}s, so they were built in every context
 * that scanned this package, production included.
 *
 * <p>That went unnoticed because nothing asserted it. {@code
 * ApplicationSmokeTest} stopped excluding JPA repositories in B-023, so the
 * repositories these beans need do exist there and they wire happily. The only
 * thing that ever complained was CI's packaged-jar smoke test, which runs a
 * leaner context — and it complained by failing to start, three weeks after
 * the beans were written.
 *
 * <p>So this test states the rule directly rather than relying on some other
 * context happening to be strict enough to notice: <strong>without the
 * profile, no bean from this package exists at all.</strong> A fifth fixture
 * bean added without {@code @Profile("fixtures")} fails here immediately,
 * naming itself, instead of in a CI job whose error points at whichever
 * repository it happened to ask for first.
 *
 * <p>The configuration below is copied verbatim from {@code
 * ApplicationSmokeTest} on purpose. Spring's TestContext framework caches
 * contexts by configuration, so an identical one is reused rather than built
 * again — this test costs no extra startup. Keep them in step.
 *
 * <p><b>The match is on the package prefix, not the package.</b> It was an
 * equality check until B-101, which is one sub-package deep — so the rule this
 * test states would have been silently switched off for the corpus that
 * arrived after it, in a test whose whole point is that nothing else was
 * asserting it. A prefix match costs nothing and cannot be outgrown the same
 * way.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class FixturesStayOutOfProductionTest {

    private static final String FIXTURES_PACKAGE = "com.edunext.edutrack.api.feature.fixtures";

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("no fixture bean is registered when the profile is off")
    void noFixtureBeanLeaksIntoAProductionContext() {
        List<String> leaked = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    // getType reads the definition's metadata; it does not
                    // instantiate, so asking cannot itself create the bean we
                    // are checking for the absence of.
                    Class<?> type = context.getType(name, false);
                    return type != null && type.getPackageName().startsWith(FIXTURES_PACKAGE);
                })
                .toList();

        assertThat(leaked)
                .as("fixture beans must carry @Profile(\"fixtures\") — these are built "
                        + "in every context that scans the package, production included")
                .isEmpty();
    }
}
