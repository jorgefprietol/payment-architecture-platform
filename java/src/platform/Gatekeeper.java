package platform;

import java.util.HashMap;
import java.util.Set;
import java.util.function.LongSupplier;
import static platform.Contracts.*;

public final class Gatekeeper {
    public record Principal(String subject, Set<String> scopes) {
        public Principal { scopes = Set.copyOf(scopes); }
    }
    private record Window(long start, int count) {}
    private final HashMap<String, Window> windows = new HashMap<>();
    private final LongSupplier clock;
    private final int limit;
    private final long windowMs;
    public Gatekeeper(LongSupplier clock, int limit, long windowMs) { this.clock = clock; this.limit = limit; this.windowMs = windowMs; }
    public synchronized void authorize(Principal principal, String owner) {
        if (limit <= 0 || windowMs <= 0) throw new RuleException("invalid_policy");
        if (principal.subject() == null || principal.subject().isBlank() || !principal.subject().equals(owner) ||
            !principal.scopes().contains("quotes:read")) throw new RuleException("forbidden");
        long now = clock.getAsLong();
        windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= windowMs);
        var current = windows.get(principal.subject());
        if (current == null) {
            if (windows.size() >= 10_000) throw new RuleException("gate_capacity");
            current = new Window(now, 0);
        }
        if (current.count() >= limit) throw new RuleException("rate_limited");
        windows.put(principal.subject(), new Window(current.start(), current.count() + 1));
    }
}
