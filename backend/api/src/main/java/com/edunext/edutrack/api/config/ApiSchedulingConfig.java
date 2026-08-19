package com.edunext.edutrack.api.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A-065 · scheduling in the {@code api} module, which had none until now.
 *
 * <h2>Why there is a scheduler here at all</h2>
 *
 * <p>TEAM-PLAN §6 puts every scheduled job in {@code worker/}, and until this
 * task every one of them was there. The scheduled report cannot be:
 * {@code worker} depends on {@code domain} and not on {@code api}, while the
 * eighteen report runners, {@code ReportCatalogue} and {@code ReportScope} all
 * live in {@code api/feature/reports}. The choices were to move three thousand
 * lines of report code into {@code domain}, to give a mail worker a dependency
 * on the web module, or to put one timer beside the code it drives. This is the
 * third.
 *
 * <p>It is worth being explicit that this is a real change in what the api
 * process does — it now has a background thread pool and does work nobody
 * requested. {@code ScheduledReportRunner} is the only job on it, it holds a
 * lock while it runs, and it is switchable off with
 * {@code edutrack.reports.scheduler-enabled}.
 *
 * <h2>The lock, and what happens without it</h2>
 *
 * <p>The api runs on more than one instance in anything but a laptop. Without a
 * lock, every instance would sweep, and the same schedule would be claimed,
 * run and emailed once per instance — three copies of the same report to the
 * same people, which is the failure people notice immediately and the one that
 * makes them turn scheduling off.
 *
 * <p>Redis rather than JDBC, for D-011's reason restated: the JDBC provider
 * needs its own {@code shedlock} table and therefore a migration, for a lock
 * that holds no business data, while Redis is already required here for refresh
 * tokens and the JWT blacklist.
 *
 * <p><b>The failure mode to know about</b> is the one
 * {@code SchedulerLockConfig} names in {@code worker}: a Redis failover can
 * drop a held lock, so two instances could sweep in that window. For the SLA
 * scanners that is harmless because their writes are idempotent. Here it is
 * not quite — a duplicated sweep can send one report twice — so the window is
 * narrowed rather than ignored: {@code lockAtLeastFor} keeps the lock beyond
 * the sweep's own duration, and a schedule's {@code next_run_at} is advanced
 * before the mail is enqueued for the following pass. A duplicate report is
 * an annoyance; the property this must never break is that a run cannot email
 * rows its owner may not see, and that is decided per run by
 * {@code ReportScheduleRepository.callerFor} rather than by this lock.
 *
 * <h2>🔴 Opt-in, and the switch is on the configuration rather than in the job</h2>
 *
 * <p>The first draft defaulted this on ({@code matchIfMissing = true}) and kept
 * an {@code enabled} field inside {@code ScheduledReportRunner.sweep()}. That
 * is the obvious arrangement and it does not work, for a reason worth writing
 * down: <b>ShedLock's interceptor wraps the scheduled method</b>, so it reaches
 * Redis before any flag inside the method body is read. Every integration test
 * in this module — dozens of contexts, none of which run Redis — logged a
 * {@code RedisConnectionFailureException} every five minutes, for a schedule
 * none of them had asked for.
 *
 * <p>With the property off there is no {@code @EnableScheduling} and no
 * {@link LockProvider}, so {@code @Scheduled} is inert metadata and nothing
 * reaches Redis. The runner bean still exists, which is what lets a test drive
 * {@code runDue()} itself — the property {@code StatsRefreshWorker}'s field
 * flag was protecting, kept without needing the field.
 *
 * <p>It is switched on in {@code application.yml}, so the shipped jar
 * schedules and a bare test context does not.
 */
@Configuration
@ConditionalOnProperty(name = "edutrack.reports.scheduler-enabled", havingValue = "true")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ApiSchedulingConfig {

    /** Keeps EduTrack's locks in their own keyspace on a shared Redis, as {@code worker} does. */
    static final String LOCK_KEY_PREFIX = "edutrack:lock";

    @Bean
    public LockProvider apiLockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, LOCK_KEY_PREFIX);
    }
}
