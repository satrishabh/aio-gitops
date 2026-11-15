package com.aio.platform.saga;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drives {@link SagaEngine} without fixed-rate polling: the thread sleeps until the earliest due saga
 * (capped at {@link #IDLE_CHECK}) or until {@link SagaEngine#start}/{@link SagaEngine#signal} wakes it.
 * With no active sagas it wakes only every few minutes and does one cheap indexed query, so it does not
 * keep a scaled-to-zero AIO busy.
 *
 * <p>Gated by {@code app.runtime.saga-workers-enabled} (env {@code AIO_SAGA_WORKERS_ENABLED}, default true).
 * It is separate from {@code AIO_WORKERS_ENABLED} because promotions now depend on it.
 */
@Component
public class SagaWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SagaWorker.class);
    static final Duration IDLE_CHECK = Duration.ofMinutes(5);
    private static final int BATCH = 20;

    private final SagaEngine engine;
    private final boolean enabled;
    private final Clock clock;
    private final Object monitor = new Object();
    private volatile boolean running;
    private boolean woken;
    private Thread thread;

    public SagaWorker(SagaEngine engine, @Value("${app.runtime.saga-workers-enabled:true}") boolean enabled,
                      ObjectProvider<Clock> clock) {
        this.engine = engine;
        this.enabled = enabled;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        engine.onWake(this::wake);
    }

    public void wake() {
        synchronized (monitor) {
            woken = true;
            monitor.notifyAll();
        }
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("saga worker disabled (app.runtime.saga-workers-enabled=false)");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "saga-worker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        wake();
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after the web server and JPA are up; stop first on shutdown. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void loop() {
        while (running) {
            try {
                if (engine.runDue(BATCH) > 0) continue;   // more may be due right away
                sleep(untilNextDue());
            } catch (Exception e) {
                log.warn("saga worker iteration failed: {}", e.toString());
                sleep(Duration.ofSeconds(10));
            }
        }
    }

    Duration untilNextDue() {
        Instant next = engine.nextDue();
        if (next == null) return IDLE_CHECK;
        Duration d = Duration.between(clock.instant(), next);
        if (d.isNegative()) return Duration.ofMillis(200);  // due but leased by a step in flight
        return d.compareTo(IDLE_CHECK) > 0 ? IDLE_CHECK : d;
    }

    private void sleep(Duration d) {
        synchronized (monitor) {
            if (woken) {
                woken = false;
                return;
            }
            try {
                monitor.wait(Math.max(1, d.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
            woken = false;
        }
    }
}
