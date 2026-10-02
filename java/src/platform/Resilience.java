package platform;

import java.util.concurrent.Semaphore;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import static platform.Contracts.*;

public final class Resilience {
    private Resilience() {}
    public static final class TransientFailure extends RuntimeException {}
    public enum CircuitState { Closed, Open, HalfOpen }
    public static final class CircuitBreaker {
        private final LongSupplier clock;
        private final int threshold;
        private final long cooldown;
        private int failures;
        private long openedAt;
        private CircuitState state = CircuitState.Closed;
        public CircuitBreaker(LongSupplier clock, int threshold, long cooldown) {
            if (threshold <= 0 || cooldown <= 0) throw new RuleException("invalid_policy");
            this.clock = clock; this.threshold = threshold; this.cooldown = cooldown;
        }
        public synchronized CircuitState state() { return state; }
        public synchronized <T> T execute(Supplier<T> operation) {
            if (state == CircuitState.Open) {
                if (clock.getAsLong() - openedAt < cooldown) throw new RuleException("circuit_open");
                state = CircuitState.HalfOpen;
            }
            try {
                T result = operation.get(); failures = 0; state = CircuitState.Closed; return result;
            } catch (TransientFailure error) {
                if (++failures >= threshold || state == CircuitState.HalfOpen) { state = CircuitState.Open; openedAt = clock.getAsLong(); }
                throw error;
            } catch (RuntimeException error) {
                failures = 0; state = CircuitState.Closed; throw error;
            }
        }
    }
    public static <T> T retry(Supplier<T> operation, int attempts, long baseDelay, LongConsumer delay) {
        if (attempts < 1 || attempts > 10 || baseDelay < 0 || baseDelay > 60_000) throw new RuleException("invalid_policy");
        for (int i = 0; ; i++) {
            try { return operation.get(); }
            catch (TransientFailure error) { if (i + 1 >= attempts) throw error; delay.accept(baseDelay * (1L << i)); }
        }
    }
    public static final class Bulkhead {
        private final Semaphore slots;
        public Bulkhead(int capacity) {
            if (capacity <= 0) throw new RuleException("invalid_policy");
            slots = new Semaphore(capacity);
        }
        public <T> T execute(Supplier<T> operation) {
            if (!slots.tryAcquire()) throw new RuleException("bulkhead_full");
            try { return operation.get(); } finally { slots.release(); }
        }
    }
}
