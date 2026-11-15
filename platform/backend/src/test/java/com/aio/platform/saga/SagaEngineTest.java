package com.aio.platform.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.aio.platform.saga.Saga.Context;
import com.aio.platform.saga.Saga.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:sagas;MODE=PostgreSQL"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // real commits, like production
class SagaEngineTest {
    @Autowired SagaInstanceRepo instances;
    @Autowired SagaEventRepo events;
    @Autowired PlatformTransactionManager transactions;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T00:00:00Z"));
    final List<String> trace = new ArrayList<>();
    final List<String> finished = new ArrayList<>();

    @BeforeEach
    void clean() {
        events.deleteAll();
        instances.deleteAll();
    }

    @Test
    void runsStepsInOrderAndSucceeds() {
        SagaEngine engine = engine(def("happy", 8, Duration.ofHours(1), ok("a"), ok("b"), ok("c")));
        SagaInstance s = engine.start("happy", "k1", Map.of());
        drain(engine);
        assertThat(trace).containsExactly("a", "b", "c");
        assertThat(reload(s).status).isEqualTo(SagaInstance.SUCCEEDED);
        assertThat(outcomes(s)).containsExactly("STARTED", "DONE", "DONE", "DONE", "SUCCEEDED");
        assertThat(finished).containsExactly("happy:SUCCEEDED");
    }

    @Test
    void startIsIdempotentPerBusinessKey() {
        SagaEngine engine = engine(def("once", 8, Duration.ofHours(1), ok("a")));
        Long first = engine.start("once", "same", Map.of("x", 1)).id;
        Long second = engine.start("once", "same", Map.of("x", 2)).id;
        assertThat(second).isEqualTo(first);
        assertThat(instances.count()).isEqualTo(1);
    }

    @Test
    void dataWrittenByOneStepIsVisibleToTheNext() {
        SagaEngine engine = engine(def("data", 8, Duration.ofHours(1),
                step("write", c -> { c.put("previous", "v1"); return Result.done(); }),
                step("read", c -> { trace.add("saw " + c.str("previous") + " for " + c.str("tag")); return Result.done(); })));
        engine.start("data", "d1", Map.of("tag", "v2"));
        drain(engine);
        assertThat(trace).contains("saw v1 for v2");
    }

    @Test
    void retriesWithBackoffThenCompensatesCompletedStepsInReverse() {
        SagaEngine engine = engine(def("flaky", 3, Duration.ofHours(1),
                ok("a"), ok("b"), step("c", c -> Result.retry("remote 503"))));
        SagaInstance s = engine.start("flaky", "f1", Map.of());
        drain(engine);                                   // a, b done; c retried once
        SagaInstance mid = reload(s);
        assertThat(mid.attempts).isEqualTo(1);
        assertThat(mid.nextAttemptAt).isEqualTo(clock.instant().plusSeconds(5));
        assertThat(engine.advance(s.id)).isFalse();      // not due yet

        clock.advance(Duration.ofSeconds(5));
        drain(engine);                                   // attempt 2, next in 10 s
        clock.advance(Duration.ofSeconds(10));
        drain(engine);                                   // attempt 3 = max -> compensate b, then a

        SagaInstance end = reload(s);
        assertThat(end.status).isEqualTo(SagaInstance.COMPENSATED);
        assertThat(trace).containsExactly("a", "b", "undo b", "undo a");
        assertThat(end.lastError).isEqualTo("remote 503");
        assertThat(finished).containsExactly("flaky:COMPENSATED");
    }

    @Test
    void permanentFailureCompensatesImmediately() {
        SagaEngine engine = engine(def("fail", 8, Duration.ofHours(1), ok("a"), step("b", c -> Result.fail("rejected"))));
        SagaInstance s = engine.start("fail", "x1", Map.of());
        drain(engine);
        assertThat(trace).containsExactly("a", "undo a");
        assertThat(reload(s).status).isEqualTo(SagaInstance.COMPENSATED);
    }

    @Test
    void failingFirstStepEndsCompensatedWithNothingToUndo() {
        SagaEngine engine = engine(def("first", 8, Duration.ofHours(1), step("a", c -> Result.fail("bad input"))));
        SagaInstance s = engine.start("first", "x2", Map.of());
        drain(engine);
        assertThat(reload(s).status).isEqualTo(SagaInstance.COMPENSATED);
        assertThat(trace).isEmpty();
    }

    @Test
    void exceptionsAreTransientFailures() {
        SagaEngine engine = engine(def("boom", 8, Duration.ofHours(1), step("a", c -> { throw new IllegalStateException("timeout"); })));
        SagaInstance s = engine.start("boom", "b1", Map.of());
        drain(engine);
        SagaInstance after = reload(s);
        assertThat(after.status).isEqualTo(SagaInstance.RUNNING);
        assertThat(after.attempts).isEqualTo(1);
        assertThat(after.lastError).contains("IllegalStateException: timeout");
    }

    @Test
    void waitReschedulesWithoutCountingAsFailureAndLogsOnce() {
        int[] checks = {0};
        SagaEngine engine = engine(def("wait", 8, Duration.ofHours(1),
                step("healthy", c -> ++checks[0] < 3 ? Result.waitUntil(c.now().plusSeconds(20), "Progressing") : Result.done())));
        SagaInstance s = engine.start("wait", "w1", Map.of());
        drain(engine);
        assertThat(reload(s).nextAttemptAt).isEqualTo(clock.instant().plusSeconds(20));
        clock.advance(Duration.ofSeconds(20));
        drain(engine);
        clock.advance(Duration.ofSeconds(20));
        drain(engine);
        SagaInstance end = reload(s);
        assertThat(end.status).isEqualTo(SagaInstance.SUCCEEDED);
        assertThat(end.attempts).isZero();
        assertThat(outcomes(s)).containsExactly("STARTED", "WAIT", "DONE", "SUCCEEDED");
    }

    @Test
    void deadlineCompensates() {
        SagaEngine engine = engine(def("slow", 8, Duration.ofMinutes(10), ok("a"),
                step("b", c -> Result.waitUntil(c.now().plusSeconds(60), "still deploying"))));
        SagaInstance s = engine.start("slow", "t1", Map.of());
        drain(engine);
        clock.advance(Duration.ofMinutes(11));
        drain(engine);
        assertThat(reload(s).status).isEqualTo(SagaInstance.COMPENSATED);
        assertThat(outcomes(s)).contains("TIMEOUT");
        assertThat(trace).containsExactly("a", "undo a");
    }

    @Test
    void failingCompensationEndsFailedForAHuman() {
        Saga.Step brokenUndo = new Saga.Step() {
            public String name() { return "a"; }
            public Result execute(Context c) { return Result.done(); }
            public void compensate(Context c) { throw new IllegalStateException("git push rejected"); }
        };
        SagaEngine engine = engine(def("stuck", 2, Duration.ofHours(1), brokenUndo, step("b", c -> Result.fail("no"))));
        SagaInstance s = engine.start("stuck", "s1", Map.of());
        drain(engine);
        clock.advance(Duration.ofMinutes(1));
        drain(engine);
        SagaInstance end = reload(s);
        assertThat(end.status).isEqualTo(SagaInstance.FAILED);
        assertThat(end.lastError).contains("compensation of a failed").contains("original error: no");
        assertThat(finished).containsExactly("stuck:FAILED");
    }

    @Test
    void leasedSagaIsNotRunTwice() {
        SagaEngine engine = engine(def("lease", 8, Duration.ofHours(1), ok("a")));
        SagaInstance s = engine.start("lease", "l1", Map.of());
        SagaInstance x = instances.findById(s.id).orElseThrow();
        x.lockedUntil = clock.instant().plusSeconds(60);
        instances.save(x);
        assertThat(engine.advance(s.id)).isFalse();
        assertThat(trace).isEmpty();
        clock.advance(Duration.ofSeconds(61));          // lease expired (worker crashed): safe to re-run
        assertThat(engine.advance(s.id)).isTrue();
        assertThat(trace).containsExactly("a");
    }

    @Test
    void signalMakesAWaitingSagaDueNow() {
        SagaEngine engine = engine(def("sig", 8, Duration.ofHours(1),
                step("await", c -> c.has("go") || c.attempt() > 0 ? Result.done() : Result.waitUntil(c.now().plusSeconds(600), "event"))));
        SagaInstance s = engine.start("sig", "g1", Map.of());
        drain(engine);
        assertThat(engine.runDue(10)).isZero();
        engine.signal("g1");
        assertThat(reload(s).nextAttemptAt).isEqualTo(clock.instant());
        assertThat(engine.anyActive()).isTrue();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(SagaEngine.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(SagaEngine.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(SagaEngine.backoff(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(SagaEngine.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }

    // ---------------------------------------------------------------- helpers

    private SagaEngine engine(Saga.Definition... defs) {
        var beans = new StaticListableBeanFactory(Map.of("clock", clock));
        return new SagaEngine(instances, events, List.of(defs), transactions, beans.getBeanProvider(Clock.class));
    }

    private void drain(SagaEngine engine) {
        for (int i = 0; i < 50 && engine.runDue(10) > 0; i++) { }
    }

    private SagaInstance reload(SagaInstance s) {
        return instances.findById(s.id).orElseThrow();
    }

    private List<String> outcomes(SagaInstance s) {
        return events.findBySagaIdOrderByIdAsc(s.id).stream().map(e -> e.outcome).toList();
    }

    private Saga.Step ok(String name) {
        return new Saga.Step() {
            public String name() { return name; }
            public Result execute(Context c) { trace.add(name); return Result.done(); }
            public void compensate(Context c) { trace.add("undo " + name); }
        };
    }

    private static Saga.Step step(String name, Function<Context, Result> body) {
        return new Saga.Step() {
            public String name() { return name; }
            public Result execute(Context c) { return body.apply(c); }
        };
    }

    private Saga.Definition def(String type, int maxAttempts, Duration timeout, Saga.Step... steps) {
        return new Saga.Definition() {
            public String type() { return type; }
            public List<Saga.Step> steps() { return List.of(steps); }
            public Duration timeout() { return timeout; }
            public int maxAttempts() { return maxAttempts; }
            public void onFinished(Context c, String status) { finished.add(type + ":" + status); }
        };
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
