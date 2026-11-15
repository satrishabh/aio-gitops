package com.aio.platform.saga;

import com.aio.platform.saga.Saga.Context;
import com.aio.platform.saga.Saga.Definition;
import com.aio.platform.saga.Saga.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrated sagas backed by PostgreSQL (docs/architecture/AIO-V2-ARCHITECTURE.md §3.1).
 *
 * <p>Each {@link #advance} call runs at most one step of one saga, in three phases:
 * <ol>
 *   <li>claim: a short transaction takes a lease ({@code lockedUntil}) under optimistic locking;</li>
 *   <li>execute: the step runs <b>outside</b> any transaction (remote calls never hold DB locks);</li>
 *   <li>persist: a short transaction stores the outcome, the next due time and the history events.</li>
 * </ol>
 * A crash between 2 and 3 re-runs the step once the lease expires, which is why steps must be idempotent.
 * Failed or timed-out sagas compensate completed steps in reverse order.
 */
@Service
public class SagaEngine {
    private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final SagaInstanceRepo instances;
    private final SagaEventRepo events;
    private final Map<String, Definition> definitions;
    private final TransactionTemplate tx;
    private final Clock clock;
    private volatile Runnable wakeListener = () -> {};

    public SagaEngine(SagaInstanceRepo instances, SagaEventRepo events, List<Definition> definitions,
                      PlatformTransactionManager transactions, ObjectProvider<Clock> clock) {
        this.instances = instances;
        this.events = events;
        this.definitions = definitions.stream().collect(Collectors.toMap(Definition::type, Function.identity()));
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Starts a saga, or returns the existing one with the same business key (idempotent start: a duplicate
     * webhook or a double click does not create a second instance).
     */
    public SagaInstance start(String type, String businessKey, Map<String, Object> data) {
        Definition d = definition(type);
        Optional<SagaInstance> existing = instances.findByBusinessKey(businessKey);
        if (existing.isPresent()) return existing.get();
        Instant now = clock.instant();
        SagaInstance s = new SagaInstance();
        s.type = type;
        s.businessKey = businessKey;
        s.data = Context.write(new java.util.LinkedHashMap<>(data));
        s.nextAttemptAt = now;
        s.deadlineAt = now.plus(d.timeout());
        s.createdAt = now;
        s.updatedAt = now;
        SagaInstance saved;
        try {
            saved = tx.execute(st -> {
                SagaInstance x = instances.saveAndFlush(s);
                events.save(SagaEvent.of(x.id, d.steps().isEmpty() ? null : d.steps().get(0).name(), "STARTED", type, now));
                return x;
            });
        } catch (DataIntegrityViolationException raced) {
            return instances.findByBusinessKey(businessKey).orElseThrow(() -> raced);
        }
        wakeListener.run();
        return saved;
    }

    public Optional<SagaInstance> find(String businessKey) {
        return instances.findByBusinessKey(businessKey);
    }

    public List<SagaEvent> history(Long sagaId) {
        return events.findBySagaIdOrderByIdAsc(sagaId);
    }

    /** True while any saga is running or compensating (used by the keep-awake lease). */
    public boolean anyActive() {
        return instances.countByStatusIn(List.of(SagaInstance.RUNNING, SagaInstance.COMPENSATING)) > 0;
    }

    /** Earliest time a saga needs work, or null when none is active. */
    public Instant nextDue() {
        return instances.nextDue();
    }

    /** Makes a waiting saga due now (e.g. an external event arrived: CI finished, Argo became healthy). */
    public void signal(String businessKey) {
        instances.findByBusinessKey(businessKey).filter(SagaInstance::active).ifPresent(s -> {
            try {
                tx.executeWithoutResult(st -> {
                    SagaInstance x = instances.findById(s.id).orElseThrow();
                    x.nextAttemptAt = clock.instant();
                    instances.save(x);
                });
            } catch (ObjectOptimisticLockingFailureException busy) {
                // being advanced right now; it will re-evaluate anyway
            }
            wakeListener.run();
        });
    }

    void onWake(Runnable listener) {
        this.wakeListener = listener;
    }

    /** Advances every due saga by at most one step; returns how many steps ran. */
    public int runDue(int limit) {
        int n = 0;
        for (Long id : instances.due(clock.instant(), PageRequest.of(0, limit))) {
            if (advance(id)) n++;
        }
        return n;
    }

    /** Runs at most one step (or one compensation) of saga {@code id}; false if it was not due or not claimable. */
    public boolean advance(Long id) {
        Instant now = clock.instant();
        SagaInstance s;
        try {
            s = tx.execute(st -> {
                SagaInstance x = instances.findById(id).orElse(null);
                if (x == null || !x.active()) return null;
                if (x.nextAttemptAt != null && x.nextAttemptAt.isAfter(now)) return null;
                if (x.lockedUntil != null && x.lockedUntil.isAfter(now)) return null;
                x.lockedUntil = now.plus(LEASE);
                return instances.saveAndFlush(x);
            });
        } catch (ObjectOptimisticLockingFailureException taken) {
            return false;
        }
        if (s == null) return false;

        Definition d = definition(s.type);
        Context ctx = new Context(s, now);
        List<SagaEvent> history = new ArrayList<>();
        if (SagaInstance.RUNNING.equals(s.status)) runStep(d, s, ctx, now, history);
        else compensateStep(d, s, ctx, now, history);

        s.data = ctx.json();
        s.lockedUntil = null;
        s.updatedAt = now;
        try {
            tx.executeWithoutResult(st -> {
                instances.saveAndFlush(s);
                events.saveAll(history);
            });
        } catch (ObjectOptimisticLockingFailureException lost) {
            log.warn("saga {} changed while step ran; outcome discarded, step will run again", id);
            return false;
        }
        if (!s.active()) {
            try {
                d.onFinished(ctx, s.status);
            } catch (Exception e) {
                log.warn("saga {} ({}) onFinished hook failed: {}", id, s.type, e.toString());
            }
        }
        return true;
    }

    private void runStep(Definition d, SagaInstance s, Context ctx, Instant now, List<SagaEvent> history) {
        List<Saga.Step> steps = d.steps();
        if (s.deadlineAt != null && now.isAfter(s.deadlineAt)) {
            history.add(SagaEvent.of(s.id, name(steps, s.step), "TIMEOUT", "deadline " + s.deadlineAt + " exceeded", now));
            beginCompensation(s, "timed out in step " + name(steps, s.step), now, history, steps);
            return;
        }
        Saga.Step step = steps.get(s.step);
        Result r;
        try {
            r = step.execute(ctx);
        } catch (Exception e) {
            r = Result.retry(describe(e));
        }
        switch (r) {
            case Result.Done done -> {
                history.add(SagaEvent.of(s.id, step.name(), "DONE", null, now));
                ctx.put("_wait", null);
                s.step++;
                s.attempts = 0;
                s.lastError = null;
                if (s.step >= steps.size()) {
                    s.status = SagaInstance.SUCCEEDED;
                    s.nextAttemptAt = null;
                    history.add(SagaEvent.of(s.id, null, "SUCCEEDED", null, now));
                } else {
                    s.nextAttemptAt = now;
                }
            }
            case Result.Wait w -> {
                String marker = step.name() + ":" + w.reason();
                if (!marker.equals(ctx.str("_wait"))) {      // log each distinct wait once, not every re-check
                    history.add(SagaEvent.of(s.id, step.name(), "WAIT", w.reason(), now));
                    ctx.put("_wait", marker);
                }
                s.nextAttemptAt = w.until();
            }
            case Result.Retry retry -> {
                s.attempts++;
                s.lastError = retry.error();
                if (s.attempts >= d.maxAttempts()) {
                    history.add(SagaEvent.of(s.id, step.name(), "FAIL", "gave up after " + s.attempts + " attempts: " + retry.error(), now));
                    beginCompensation(s, retry.error(), now, history, steps);
                } else {
                    history.add(SagaEvent.of(s.id, step.name(), "RETRY", retry.error(), now));
                    s.nextAttemptAt = now.plus(backoff(s.attempts));
                }
            }
            case Result.Fail fail -> {
                history.add(SagaEvent.of(s.id, step.name(), "FAIL", fail.error(), now));
                beginCompensation(s, fail.error(), now, history, steps);
            }
        }
    }

    /** Completed steps are 0..step-1; the failed step itself is not compensated (steps must fail atomically). */
    private void beginCompensation(SagaInstance s, String reason, Instant now, List<SagaEvent> history, List<Saga.Step> steps) {
        s.lastError = reason;
        s.status = SagaInstance.COMPENSATING;
        s.step = Math.min(s.step, steps.size()) - 1;
        s.attempts = 0;
        s.nextAttemptAt = now;
        if (s.step < 0) finishCompensated(s, now, history);
    }

    private void compensateStep(Definition d, SagaInstance s, Context ctx, Instant now, List<SagaEvent> history) {
        if (s.step < 0) {
            finishCompensated(s, now, history);
            return;
        }
        Saga.Step step = d.steps().get(s.step);
        try {
            step.compensate(ctx);
            history.add(SagaEvent.of(s.id, step.name(), "COMPENSATED", null, now));
            s.step--;
            s.attempts = 0;
            s.nextAttemptAt = now;
            if (s.step < 0) finishCompensated(s, now, history);
        } catch (Exception e) {
            s.attempts++;
            if (s.attempts >= d.maxAttempts()) {
                s.status = SagaInstance.FAILED;
                s.nextAttemptAt = null;
                s.lastError = "compensation of " + step.name() + " failed: " + describe(e) + " (original error: " + s.lastError + ")";
                history.add(SagaEvent.of(s.id, step.name(), "FAILED", "needs manual attention: " + describe(e), now));
            } else {
                history.add(SagaEvent.of(s.id, step.name(), "COMPENSATION_RETRY", describe(e), now));
                s.nextAttemptAt = now.plus(backoff(s.attempts));
            }
        }
    }

    private static void finishCompensated(SagaInstance s, Instant now, List<SagaEvent> history) {
        s.status = SagaInstance.COMPENSATED;
        s.step = -1;
        s.nextAttemptAt = null;
        history.add(SagaEvent.of(s.id, null, "COMPENSATED", s.lastError, now));
    }

    /** 5 s, 10 s, 20 s, ... capped at 5 min. */
    static Duration backoff(int attempt) {
        long seconds = 5L << Math.min(Math.max(attempt - 1, 0), 10);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    private Definition definition(String type) {
        Definition d = definitions.get(type);
        if (d == null) throw new IllegalArgumentException("unknown saga type " + type);
        return d;
    }

    private static String name(List<Saga.Step> steps, int i) {
        return i >= 0 && i < steps.size() ? steps.get(i).name() : null;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
