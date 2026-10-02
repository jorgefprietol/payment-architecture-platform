namespace PaymentPlatform;

// Principal and scopes must come from a verified identity adapter, never request headers.
public sealed record Principal(string Subject, IReadOnlySet<string> Scopes);
public sealed class Gatekeeper(Func<long> clock, int limit, long windowMs)
{
    private readonly object gate = new();
    private readonly Dictionary<string, (long Start, int Count)> windows = new();
    public void Authorize(Principal principal, string owner)
    {
        if (limit <= 0 || windowMs <= 0) throw new RuleException("invalid_policy");
        if (string.IsNullOrWhiteSpace(principal.Subject) || principal.Subject != owner || !principal.Scopes.Contains("quotes:read"))
            throw new RuleException("forbidden");
        lock (gate)
        {
            var now = clock();
            foreach (var key in windows.Where(x => now - x.Value.Start >= windowMs).Select(x => x.Key).ToArray())
                windows.Remove(key);
            if (!windows.TryGetValue(principal.Subject, out var current))
            {
                if (windows.Count >= 10_000) throw new RuleException("gate_capacity");
                current = (now, 0);
            }
            if (current.Count >= limit) throw new RuleException("rate_limited");
            windows[principal.Subject] = (current.Start, current.Count + 1);
        }
    }
}
