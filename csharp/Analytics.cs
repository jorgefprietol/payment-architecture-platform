using System.Text.Json;

namespace PaymentPlatform;

public sealed record TransferEvent(Guid EventId, Guid TransferId, int SchemaVersion, long AmountMinor, string Currency);

// Inbox and read model commit under one lock. This adapter is intentionally in-memory.
public sealed class Analytics
{
    private readonly object gate = new();
    private readonly Dictionary<Guid, TransferEvent> inbox = new();
    private readonly Dictionary<Guid, TransferEvent> transfers = new();
    private readonly Dictionary<string, long> totals = new();
    public bool Apply(TransferEvent entry)
    {
        lock (gate)
        {
            if (entry.EventId == Guid.Empty || entry.TransferId == Guid.Empty || entry.SchemaVersion != 1 ||
                entry.AmountMinor <= 0 || entry.Currency is not ("USD" or "EUR")) throw new RuleException("invalid_contract");
            if (inbox.TryGetValue(entry.EventId, out var prior))
            {
                if (prior != entry) throw new RuleException("idempotency_conflict");
                return false;
            }
            if (transfers.TryGetValue(entry.TransferId, out var transfer))
            {
                if (transfer.AmountMinor != entry.AmountMinor || transfer.Currency != entry.Currency)
                    throw new RuleException("idempotency_conflict");
                inbox.Add(entry.EventId, entry); return false;
            }
            // Calculate before mutation: overflow must leave inbox and totals unchanged.
            long next;
            try { next = checked(totals.GetValueOrDefault(entry.Currency) + entry.AmountMinor); }
            catch (OverflowException) { throw new RuleException("total_overflow"); }
            inbox.Add(entry.EventId, entry); transfers.Add(entry.TransferId, entry); totals[entry.Currency] = next;
            return true;
        }
    }
    public long Total(string currency) { lock (gate) return totals.GetValueOrDefault(currency); }
}

public sealed class Telemetry
{
    private readonly object gate = new();
    private long requests, errors, elapsedMs;
    public void Record(bool failed, long durationMs)
    {
        if (durationMs < 0) throw new RuleException("invalid_duration");
        lock (gate) { requests++; if (failed) errors++; elapsedMs += durationMs; }
    }
    public bool Alert(int minimumRequests, double errorBudget)
    {
        if (minimumRequests <= 0 || !double.IsFinite(errorBudget) || errorBudget is < 0 or > 1)
            throw new RuleException("invalid_policy");
        lock (gate) return requests >= minimumRequests && (double)errors / requests > errorBudget;
    }
    public string Log(DateTimeOffset now, Guid correlationId, string operation, string outcome) =>
        JsonSerializer.Serialize(new { timestamp = now.UtcDateTime.ToString("O"), correlationId,
            service = "payment-platform", operation, outcome });
    public string Metrics()
    {
        lock (gate) return $"requests_total {requests}\nerrors_total {errors}\nduration_ms_total {elapsedMs}\n";
    }
}
