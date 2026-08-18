package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.domain.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A-071 · builds {@link AuditInterceptor} and registers it against the API.
 *
 * <p>Scoped to {@code /api/**} rather than {@code /**} so that the SPA shell,
 * the actuator and springdoc's UI never reach it. The interceptor already
 * ignores anything that is not a {@code HandlerMethod}, so this is belt and
 * braces — but it is the difference between "audits the application" and
 * "audits every request the container sees", and the second is how an audit
 * table fills with health checks.
 *
 * <p>A {@code WebMvcConfigurer} and not {@code @EnableWebMvc}: the latter
 * switches off Boot's MVC auto-configuration wholesale, which would take
 * content negotiation, the Jackson setup and the static-resource handlers with
 * it. This adds one interceptor and changes nothing else.
 *
 * <h2>Why the interceptor is built here rather than being a {@code @Component}</h2>
 *
 * <p>It was one, and it broke nine {@code @WebMvcTest} slices — eight of them in
 * Streams B and D's directories. {@code @WebMvcTest} includes
 * {@code HandlerInterceptor} beans in its slice by design, so a scanned
 * interceptor is instantiated in every controller slice in the repository and
 * drags {@link AuditTrail} — and therefore a {@code JdbcClient}, and therefore a
 * {@code DataSource} — in with it. Those slices have no database, deliberately.
 *
 * <p>The alternative was a {@code @MockitoBean AuditTrail} line in nine test
 * files, eight belonging to other developers, added for a reason that has
 * nothing to do with what those tests are about — and then again in the tenth,
 * and the eleventh. Building the interceptor here keeps the whole accommodation
 * in one file of Stream A's: no slice sees it, the full application always does,
 * and {@code AuditInterceptorTest} constructs it directly either way.
 *
 * <p>{@link ObjectProvider} rather than a plain constructor argument for the
 * same reason one level up — this configuration <em>is</em> in the
 * {@code @WebMvcTest} slice, being a {@code WebMvcConfigurer}, so asking for
 * {@code AuditTrail} outright would merely move the failure here.
 */
@Configuration
class AuditWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AuditWebConfig.class);

    private final ObjectProvider<AuditTrail> auditTrail;

    AuditWebConfig(ObjectProvider<AuditTrail> auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        AuditTrail trail = auditTrail.getIfAvailable();
        if (trail == null) {
            // Only reachable in a controller slice, which has no datasource to
            // write to. Logged at WARN rather than passed over in silence: if
            // this line ever appears in a running application it means nothing
            // is being audited, and that is not a condition to discover from an
            // empty table months later.
            log.warn("audit: no AuditTrail available — request auditing is not active");
            return;
        }
        registry.addInterceptor(new AuditInterceptor(trail)).addPathPatterns("/api/**");
    }
}
