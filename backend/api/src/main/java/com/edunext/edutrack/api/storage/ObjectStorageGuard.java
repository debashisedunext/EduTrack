package com.edunext.edutrack.api.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Refuses to start a {@code prod} deployment that is not actually pointed at S3.
 *
 * <h2>Why a guard rather than documentation</h2>
 *
 * <p>Every value this checks has a working local default, which is what makes
 * the failure mode so poor: a deployment that sets none of them starts perfectly,
 * serves every screen, accepts uploads, and writes them to a {@code localhost:9000}
 * that is not there. The first symptom is a user's missing document, and the
 * place to look is a config file nobody changed. This is the same reasoning
 * {@code FixtureGuard} and {@code DevNoAuthConfig} were written on — a default
 * that is safe locally and wrong in production should stop the application, not
 * wait to be noticed.
 *
 * <h2>Why {@code @Profile("prod")} and not "whenever local is absent"</h2>
 *
 * <p>Integration tests run with <b>no</b> active profile and use the MinIO
 * defaults, so a guard keyed on the absence of {@code local} would refuse to
 * start every {@code @SpringBootTest} in the module — and would report it as an
 * object-storage misconfiguration, which is the least useful place to send
 * whoever is reading the failure. Keying on the presence of {@code prod} means
 * this class loads only where its assumptions hold.
 *
 * <p>The cost of that choice is that it checks nothing unless {@code prod} is
 * actually activated. That is acceptable because activating it is a single
 * deployment-time variable — {@code SPRING_PROFILES_ACTIVE=prod} — and a
 * deployment that has not set even that has not been configured at all.
 */
@Component
@Profile("prod")
class ObjectStorageGuard {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageGuard.class);

    ObjectStorageGuard(ObjectStorageProperties properties) {
        check(properties);
    }

    static void check(ObjectStorageProperties properties) {
        if (!properties.awsManaged()) {
            String host = properties.endpoint().toLowerCase(Locale.ROOT);
            if (host.contains("localhost") || host.contains("127.0.0.1") || host.contains("[::1]")) {
                throw new IllegalStateException(
                        "edutrack.storage.endpoint is '" + properties.endpoint() + "' under the prod "
                                + "profile — that is the local MinIO default. Leave S3_ENDPOINT blank to "
                                + "use real AWS S3, or set it to the S3-compatible host you mean.");
            }
        }

        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException("edutrack.storage.bucket must be set (S3_BUCKET).");
        }

        if (properties.region() == null || properties.region().isBlank()) {
            throw new IllegalStateException(
                    "edutrack.storage.region must be set (S3_REGION). SigV4 signs the region, so a "
                            + "wrong or missing one is a signature mismatch on every presigned URL.");
        }

        // Path-style is deprecated for buckets created after Sept 2020 and is not
        // merely suboptimal against them — the request does not resolve. It is also
        // the value browserEndpoint() derives the CSP origin from, so a wrong one
        // here blocks downloads twice over.
        if (properties.awsManaged() && properties.pathStyle()) {
            throw new IllegalStateException(
                    "edutrack.storage.path-style is true against real AWS S3. Set S3_PATH_STYLE=false; "
                            + "path-style addressing is deprecated and does not resolve for buckets "
                            + "created after September 2020.");
        }

        if (properties.hasStaticCredentials()) {
            if ("minioadmin".equals(properties.accessKey()) || "minioadmin".equals(properties.secretKey())) {
                throw new IllegalStateException(
                        "edutrack.storage is still using the committed minioadmin credentials under the "
                                + "prod profile. Blank S3_ACCESS_KEY and S3_SECRET_KEY to use the instance "
                                + "role, or set real ones.");
            }
            log.warn("object storage: using static credentials against {}. An instance role is preferred — "
                            + "blank S3_ACCESS_KEY and S3_SECRET_KEY to use one.",
                    properties.browserEndpoint());
        } else {
            log.info("object storage: bucket '{}' in {} via the default credentials chain (instance role), "
                            + "browser origin {}",
                    properties.bucket(), properties.region(), properties.browserEndpoint());
        }
    }
}
