namespace PaymentPlatform;

public sealed class TransientFailure : Exception;
public enum CircuitState { Closed, Open, HalfOpen }

// Serialized calls keep the half-open probe unique. Use a dedicated instance per dependency.
public sealed class CircuitBreaker
{
    private readonly object gate = new();
    private readonly Func<long> clock;
    private readonly int threshold;
    private readonly long cooldown;
    private int failures;
    private long openedAt;
    public CircuitState State { get; private set; }
    public CircuitBreaker(Func<long> clock, int threshold, long cooldown)
    {
        if (threshold <= 0 || cooldown <= 0) throw new RuleException("invalid_policy");
        this.clock = clock; this.threshold = threshold; this.cooldown = cooldown;
    }
    public T Execute<T>(Func<T> operation)
    {
        lock (gate)
        {
            if (State == CircuitState.Open)
            {
                if (clock() - openedAt < cooldown) throw new RuleException("circuit_open");
                State = CircuitState.HalfOpen;
            }
            try
            {
                var result = operation(); failures = 0; State = CircuitState.Closed; return result;
            }
            catch (TransientFailure)
            {
                if (++failures >= threshold || State == CircuitState.HalfOpen)
                { State = CircuitState.Open; openedAt = clock(); }
                throw;
            }
            catch
            {
                // A validation failure proves transport is alive; it is never retried.
                failures = 0; State = CircuitState.Closed; throw;
            }
        }
    }
}

public static class Retry
{
    public static T Execute<T>(Func<T> operation, int attempts, long baseDelay, Action<long> delay)
    {
        if (attempts is < 1 or > 10 || baseDelay is < 0 or > 60_000) throw new RuleException("invalid_policy");
        for (var i = 0; ; i++)
        {
            try { return operation(); }
            catch (TransientFailure) when (i + 1 < attempts) { delay(baseDelay * (1L << i)); }
        }
    }
}

public sealed class Bulkhead
{
    private readonly SemaphoreSlim slots;
    public Bulkhead(int capacity)
    {
        if (capacity <= 0) throw new RuleException("invalid_policy");
        slots = new(capacity, capacity);
    }
    public T Execute<T>(Func<T> operation)
    {
        if (!slots.Wait(0)) throw new RuleException("bulkhead_full");
        try { return operation(); } finally { slots.Release(); }
    }
}
