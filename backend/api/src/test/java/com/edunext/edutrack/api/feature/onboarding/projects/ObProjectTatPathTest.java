package com.edunext.edutrack.api.feature.onboarding.projects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project figure that used to be a sum.
 *
 * <p>The case that prompted it is {@link Parallel#twoIndependentServices()}: a
 * project boarded through two four-day services reported eight days and a
 * tentative completion a week late.
 */
class ObProjectTatPathTest {

    private static ObProjectTatPath.Node node(long id, int ownDays, Long... dependsOn) {
        return new ObProjectTatPath.Node(id, ownDays, Set.of(dependsOn));
    }

    @Nested
    @DisplayName("services that do not wait on each other")
    class Parallel {

        @Test
        @DisplayName("two independent services take as long as the longer one")
        void twoIndependentServices() {
            int days = ObProjectTatPath.longestPath(List.of(node(1, 4), node(2, 4)));

            assertThat(days).isEqualTo(4);
        }

        @Test
        @DisplayName("the longest of several, not their total")
        void severalIndependentServices() {
            int days = ObProjectTatPath.longestPath(List.of(node(1, 2), node(2, 9), node(3, 5)));

            assertThat(days).isEqualTo(9);
        }

        @Test
        @DisplayName("a single service is its own figure")
        void oneService() {
            assertThat(ObProjectTatPath.longestPath(List.of(node(1, 6)))).isEqualTo(6);
        }

        @Test
        @DisplayName("a project with no journeys is zero, as an unstarted project was before")
        void noServices() {
            assertThat(ObProjectTatPath.longestPath(List.of())).isZero();
        }
    }

    @Nested
    @DisplayName("services that do")
    class Chained {

        @Test
        @DisplayName("a dependency adds, because the two cannot overlap")
        void oneDependency() {
            // 2 waits on 1: 3 + 4 days end to end.
            int days = ObProjectTatPath.longestPath(List.of(node(1, 3), node(2, 4, 1L)));

            assertThat(days).isEqualTo(7);
        }

        @Test
        @DisplayName("the heaviest chain wins, not the longest one by count")
        void heaviestChain() {
            /*
              1 (2d) <- 2 (3d)      a two-service chain worth 5
              3 (9d)                one service worth 9, waiting on nothing
            */
            int days = ObProjectTatPath.longestPath(List.of(node(1, 2), node(2, 3, 1L), node(3, 9)));

            assertThat(days).isEqualTo(9);
        }

        @Test
        @DisplayName("a service waiting on two takes the slower of them")
        void twoDependencies() {
            // 3 waits on both 1 (2d) and 2 (6d). It starts when the slower ends.
            int days = ObProjectTatPath.longestPath(
                    List.of(node(1, 2), node(2, 6), node(3, 1, 1L, 2L)));

            assertThat(days).isEqualTo(7);
        }

        @Test
        @DisplayName("a chain of three adds all three")
        void chainOfThree() {
            int days = ObProjectTatPath.longestPath(
                    List.of(node(1, 1), node(2, 2, 1L), node(3, 3, 2L)));

            assertThat(days).isEqualTo(6);
        }

        /**
         * The edge lives on the template, so a product whose SIS template waits
         * on a Core template says nothing about a client who bought SIS alone.
         */
        @Test
        @DisplayName("a dependency this project was not boarded through adds nothing")
        void dependencyOutsideTheProject() {
            int days = ObProjectTatPath.longestPath(List.of(node(1, 4, 99L)));

            assertThat(days).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("a graph that should not exist")
    class Defensive {

        /**
         * Neither service could ever start, so no figure is right. Returning a
         * low one beats a read that never returns.
         */
        @Test
        @DisplayName("a cycle terminates rather than looping")
        void cycle() {
            int days = ObProjectTatPath.longestPath(List.of(node(1, 3, 2L), node(2, 4, 1L)));

            assertThat(days).isGreaterThan(0).isLessThanOrEqualTo(7);
        }

        @Test
        @DisplayName("a service that waits on itself is still counted once")
        void selfDependency() {
            assertThat(ObProjectTatPath.longestPath(List.of(node(1, 5, 1L)))).isEqualTo(5);
        }

        @Test
        @DisplayName("a service with no tasks contributes nothing of its own")
        void zeroTatService() {
            int days = ObProjectTatPath.longestPath(List.of(node(1, 0), node(2, 3, 1L)));

            assertThat(days).isEqualTo(3);
        }
    }
}
