using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace PaymentPlatform;

public sealed class MigrationRouter(IQuoteBackend legacy, IQuoteBackend modern)
{
    private int percentage;
    public void SetPercentage(int value)
    {
        if (value is < 0 or > 100) throw new RuleException("invalid_percentage");
        Volatile.Write(ref percentage, value);
    }
    public static int Bucket(Guid id) => (int)(BinaryPrimitives.ReadUInt32BigEndian(
        SHA256.HashData(Encoding.UTF8.GetBytes(id.ToString("D")))) % 10_000);
    public string Route(Guid id) => Bucket(id) < Volatile.Read(ref percentage) * 100 ? "modern" : "legacy";
    public Quote Read(QuoteRequest request)
    {
        request.Validate();
        return (Route(request.Id) == "modern" ? modern : legacy).Read(request);
    }
    public bool Compare(QuoteRequest request)
    {
        request.Validate();
        return legacy.Read(request) == modern.Read(request);
    }
}
