package com.edunext.edutrack.api.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A-037 · a controller does not reach a repository, and no package is named
 * after a layer.
 *
 * <h2>Why a controller holding a repository is worth a build failure</h2>
 *
 * <p>On its own it is a small thing — one query, inline, obviously correct. It
 * matters because of what it is a precondition for. Everything this codebase
 * enforces server-side is enforced in a service or a guard: the row scope
 * (A-034), the working-calendar arithmetic, the hash chain's per-ticket lock.
 * A controller with a repository is a route that can answer a question without
 * passing through any of them, and it will be copied, because the next person
 * writing a similar endpoint reads the nearest one. The rule is cheap now and
 * expensive to reintroduce after the fourth copy.
 *
 * <p>The reverse is a second reason: a repository in a controller signature
 * makes the handler untestable without a database, which is how a permission or
 * validation test quietly becomes an integration test.
 *
 * <h2>Two shapes of repository, and one thing that is not one</h2>
 *
 * <p>Half of them are Spring Data interfaces and half are {@code @Repository}
 * classes over {@code JdbcClient} — {@code ProjectMasterRepository},
 * {@code ResourceWriteRepository}, the SLA ones. A rule that caught only the
 * first would pass while a controller autowired the second, so the predicate
 * below is the union.
 *
 * <p><b>It deliberately does not match a type merely nested inside a
 * repository.</b> {@code ProjectController} builds a
 * {@code ProjectMasterRepository.ProjectFilter} — a record that is a query
 * parameter object, not a door to the database. Widening the rule to catch it
 * would be enforcing where a record is declared, which is a naming opinion, and
 * the fix it would force (move the record) buys nothing. Stated here so the
 * next person to "tighten" this rule knows it was considered.
 */
class LayeringRulesTest {

    /**
     * Both shapes. {@code org.springframework.data.repository.Repository} is
     * the marker every Spring Data interface inherits — including the three
     * append-only ones, which extend it bare precisely so they inherit nothing
     * else.
     */
    private static final DescribedPredicate<JavaClass> REPOSITORIES =
            assignableTo(org.springframework.data.repository.Repository.class)
                    .or(annotatedWith(org.springframework.stereotype.Repository.class))
                    .as("Spring Data repositories or @Repository beans");

    @Test
    void noControllerReachesARepositoryDirectly() {
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(RestController.class)
                .or().areAnnotatedWith(Controller.class)
                .or().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat(REPOSITORIES)
                .because("""
                        every server-side rule this system has — the row scope, the working \
                        calendar, the per-ticket lock — lives in a service or a guard, so a \
                        handler that can query without one is a handler that can answer \
                        without them. Inject the feature's service instead.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * CLAUDE.md: "Never create shared {@code controllers/} or {@code services/}
     * folders — that is what makes four developers collide daily." Nothing
     * violates this today; the rule exists so that stays true, because the
     * first such folder is always added innocently and is never the last.
     *
     * <p>Written as "no class resides in" rather than "no package named X
     * exists" on purpose: a rule whose {@code that()} clause matches nothing
     * fails ArchUnit's empty-should check, so a rule phrased the obvious way
     * round would be red on a clean codebase.
     */
    @Test
    void noPackageIsNamedAfterALayer() {
        ArchRule rule = noClasses()
                .should().resideInAnyPackage(
                        "..controllers..",
                        "..services..",
                        "..repositories..",
                        "..entities..",
                        "..dtos..")
                .because("""
                        the backend is packaged by feature, not by layer: \
                        api/feature/tickets/ holds its own controller, service, repository \
                        and DTOs. Layer folders are shared by everybody and therefore \
                        conflict for everybody.""");

        rule.check(ProductionClasses.get());
    }
}
