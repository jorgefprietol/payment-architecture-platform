namespace PaymentPlatform;

public sealed class RuleException(string code) : Exception(code);

public sealed record QuoteRequest(Guid Id, long AmountMinor, string Currency)
{
    public void Validate()
    {
        if (Id == Guid.Empty || AmountMinor <= 0 || AmountMinor > 9_000_000_000_000 || Currency is not ("USD" or "EUR"))
            throw new RuleException("invalid_request");
    }
}
public sealed record Quote(long FeeMinor, string Currency);
public interface IQuoteBackend { Quote Read(QuoteRequest request); }

// A read-only port permits dual-run comparison without executing two payments.
public sealed class FixedFee(long fee) : IQuoteBackend
{
    public Quote Read(QuoteRequest request) { request.Validate(); return new(fee, request.Currency); }
}
