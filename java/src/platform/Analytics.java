package platform;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static platform.Contracts.*;

public final class Analytics {
    public record TransferEvent(UUID eventId, UUID transferId, int schemaVersion, long amountMinor, String currency) {}
    private final Map<UUID, TransferEvent> inbox = new HashMap<>(), transfers = new HashMap<>();
    private final Map<String, Long> totals = new HashMap<>();
    public synchronized boolean apply(TransferEvent entry) {
        if (entry.eventId() == null || entry.transferId() == null || entry.eventId().equals(new UUID(0, 0)) ||
            entry.transferId().equals(new UUID(0, 0)) || entry.schemaVersion() != 1 || entry.amountMinor() <= 0 ||
            !("USD".equals(entry.currency()) || "EUR".equals(entry.currency()))) throw new RuleException("invalid_contract");
        var prior = inbox.get(entry.eventId());
        if (prior != null) {
            if (!prior.equals(entry)) throw new RuleException("idempotency_conflict");
            return false;
        }
        var transfer = transfers.get(entry.transferId());
        if (transfer != null) {
            if (transfer.amountMinor() != entry.amountMinor() || !transfer.currency().equals(entry.currency()))
                throw new RuleException("idempotency_conflict");
            inbox.put(entry.eventId(), entry); return false;
        }
        long next;
        try { next = Math.addExact(totals.getOrDefault(entry.currency(), 0L), entry.amountMinor()); }
        catch (ArithmeticException error) { throw new RuleException("total_overflow"); }
        inbox.put(entry.eventId(), entry); transfers.put(entry.transferId(), entry); totals.put(entry.currency(), next);
        return true;
    }
    public synchronized long total(String currency) { return totals.getOrDefault(currency, 0L); }
}
