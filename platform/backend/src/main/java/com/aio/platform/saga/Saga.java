package com.aio.platform.saga;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The saga programming model: definitions, steps, step results and the context steps run with. */
public final class Saga {
    private Saga() {}

    /** A saga type: an ordered list of steps plus an overall deadline. Register as a Spring bean. */
    public interface Definition {
        String type();

        List<Step> steps();

        /** Wall-clock budget from start; when exceeded while RUNNING, the saga compensates. */
        default Duration timeout() { return Duration.ofHours(1); }

        /** Attempts per step before the step counts as failed (then compensation starts). */
        default int maxAttempts() { return 8; }

        /** Called once when the saga reaches SUCCEEDED, COMPENSATED or FAILED (after the state is saved). */
        default void onFinished(Context ctx, String status) {}
    }

    /**
     * One idempotent step. {@link #execute} must be safe to run again after a crash ("ensure X", compare before
     * write, find before create). {@link #compensate} undoes a completed step and must be idempotent too.
     */
    public interface Step {
        String name();

        Result execute(Context ctx) throws Exception;

        default void compensate(Context ctx) throws Exception {}
    }

    /** Outcome of one execution of a step. */
    public sealed interface Result {
        /** Step complete; continue with the next one. */
        record Done() implements Result {}

        /** Not ready yet (e.g. waiting for a deployment); run this step again at {@code until}. Not a failure. */
        record Wait(Instant until, String reason) implements Result {}

        /** Transient failure; retried with backoff until {@link Definition#maxAttempts()}. */
        record Retry(String error) implements Result {}

        /** Permanent failure; compensation starts immediately. */
        record Fail(String error) implements Result {}

        static Result done() { return new Done(); }
        static Result waitUntil(Instant until, String reason) { return new Wait(until, reason); }
        static Result retry(String error) { return new Retry(error); }
        static Result fail(String error) { return new Fail(error); }
    }

    /** What a step sees: the saga's id, its JSON data as a map (changes are persisted), the time and attempt. */
    public static final class Context {
        private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
        private final SagaInstance instance;
        private final Map<String, Object> data;
        private final Instant now;

        Context(SagaInstance instance, Instant now) {
            this.instance = instance;
            this.now = now;
            this.data = parse(instance.data);
        }

        /** A context that is not backed by a stored saga, for unit tests and dry runs. */
        public static Context detached(Map<String, Object> data, Instant now) {
            SagaInstance s = new SagaInstance();
            s.data = write(new LinkedHashMap<>(data));
            return new Context(s, now);
        }

        public Long sagaId() { return instance.id; }
        public String businessKey() { return instance.businessKey; }
        public Instant now() { return now; }
        /** 0 for the first execution of the current step, then 1, 2, ... */
        public int attempt() { return instance.attempts; }

        public String str(String key) {
            Object v = data.get(key);
            return v == null ? null : String.valueOf(v);
        }

        public Long num(String key) {
            Object v = data.get(key);
            return v == null ? null : ((Number) v).longValue();
        }

        public boolean has(String key) { return data.get(key) != null; }

        /** Last error recorded on the saga (why it is compensating or failed); null when none. */
        public String lastError() { return instance.lastError; }

        public void put(String key, Object value) { data.put(key, value); }

        String json() { return write(data); }

        static Map<String, Object> parse(String json) {
            try {
                return json == null || json.isBlank() ? new LinkedHashMap<>()
                        : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalStateException("corrupt saga data", e);
            }
        }

        static String write(Map<String, Object> data) {
            try {
                return JSON.writeValueAsString(data);
            } catch (Exception e) {
                throw new IllegalArgumentException("saga data must be JSON-serialisable", e);
            }
        }
    }
}
