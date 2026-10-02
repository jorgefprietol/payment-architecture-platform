package platform;

import java.util.UUID;

public final class Contracts {
    private Contracts() {}
    public static final class RuleException extends RuntimeException {
        public RuleException(String code) { super(code); }
    }
    public record QuoteRequest(UUID id, long amountMinor, String currency) {
        public void validate() {
            if (id == null || id.equals(new UUID(0, 0)) || amountMinor <= 0 || amountMinor > 9_000_000_000_000L ||
                !("USD".equals(currency) || "EUR".equals(currency))) throw new RuleException("invalid_request");
        }
    }
    public record Quote(long feeMinor, String currency) {}
    public interface QuoteBackend { Quote read(QuoteRequest request); }
    public record FixedFee(long fee) implements QuoteBackend {
        public Quote read(QuoteRequest request) { request.validate(); return new Quote(fee, request.currency()); }
    }
}
