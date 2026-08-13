package com.edunext.edutrack.api.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * A-033 · the switch that makes {@code @PreAuthorize} do anything.
 *
 * <p><b>Without it the annotations are documentation.</b> Spring Security does
 * not enable method security by default and does not warn about unprocessed
 * {@code @PreAuthorize} — the advisor simply is not registered, so every
 * annotated method runs for every authenticated caller and every one of them
 * reads, in the source, as protected. That is the worst available combination:
 * the check is absent and the code says it is present.
 * {@code RouteAuthorizationTest} therefore does not trust the annotations to be
 * enforced merely because they are written; it asserts a Developer is actually
 * refused a master write, through the real proxy.
 *
 * <p><b>Its own class, not a line on {@link SecurityConfig}.</b> They are
 * different mechanisms with different blast radii — the filter chain decides
 * per request path, this decides per invoked method, and a change to one is
 * rarely a change to the other. Keeping them apart also keeps the annotation off
 * a class that Spring instantiates early; {@code @EnableMethodSecurity} on a
 * {@code @Configuration} that also declares beans other configurations depend
 * on is a documented route to premature-initialisation warnings.
 *
 * <h2>What is deliberately left at its default</h2>
 *
 * <ul>
 *   <li><b>{@code prePostEnabled = true}</b> — the default, and the only style
 *       this codebase uses. Stated by omission rather than repeated.</li>
 *   <li><b>{@code securedEnabled} and {@code jsr250Enabled} stay off.</b> Three
 *       annotation vocabularies for one decision is three things a reviewer has
 *       to know to be sure a method is covered, and {@code @Secured} cannot
 *       express a permission check at all — it only takes authority literals
 *       with no expression around them. One vocabulary, so
 *       {@code RouteAuthorizationTest} has exactly one annotation to look
 *       for.</li>
 *   <li><b>{@code proxyTargetClass} is not forced.</b> Every controller in this
 *       codebase is a concrete class with no interface, so Spring uses CGLIB and
 *       the proxy carries the annotations. The trap to remember is the general
 *       one for proxy-based advice: a {@code private} or {@code final} handler
 *       method, or one called from inside the same bean, is not advised and the
 *       annotation on it silently does nothing. Handlers here are all
 *       package-private instance methods invoked by the dispatcher through the
 *       proxy, which is the advised path.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
