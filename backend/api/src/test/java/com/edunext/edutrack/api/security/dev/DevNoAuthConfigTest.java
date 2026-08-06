package com.edunext.edutrack.api.security.dev;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-012. The three activation behaviours, each of which is a hard
 * requirement from the task line:
 *
 * <ol>
 *   <li>{@code dev-noauth} without {@code local} → the application REFUSES
 *       to start. This is the safety catch that keeps a fake auto-login out
 *       of every real environment.</li>
 *   <li>{@code local,dev-noauth} together → starts, filter registered.</li>
 *   <li>No {@code dev-noauth} (CI, production, plain local) → the config
 *       class does not load at all; zero footprint.</li>
 * </ol>
 */
class DevNoAuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevNoAuthConfig.class);

    @Test
    void refusesToStartWhenLocalProfileIsAbsent() {
        runner.withPropertyValues("spring.profiles.active=dev-noauth")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Refusing to start");
                });
    }

    @Test
    void startsAndRegistersTheFilterWithLocalAndDevNoAuthTogether() {
        runner.withPropertyValues("spring.profiles.active=local,dev-noauth")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    assertThat(context).hasSingleBean(DevNoAuthProperties.class);
                });
    }

    @Test
    void staysCompletelyDormantWithoutTheDevNoAuthProfile() {
        // Plain `local` — and, identically, CI and production, which activate
        // nothing. @Profile must keep every bean of this package out.
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                    assertThat(context).doesNotHaveBean(DevNoAuthProperties.class);
                });
    }

    @Test
    void configuredScopeReachesTheProperties() {
        runner.withPropertyValues(
                        "spring.profiles.active=local,dev-noauth",
                        "edutrack.dev-noauth.user-id=4",
                        "edutrack.dev-noauth.role=DEVELOPER",
                        "edutrack.dev-noauth.project-ids=1,2")
                .run(context -> {
                    DevNoAuthProperties props = context.getBean(DevNoAuthProperties.class);
                    assertThat(props.userId()).isEqualTo(4L);
                    assertThat(props.role()).isEqualTo("DEVELOPER");
                    assertThat(props.projectIds()).containsExactly(1L, 2L);
                });
    }
}
