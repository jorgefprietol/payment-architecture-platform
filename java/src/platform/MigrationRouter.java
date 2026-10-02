package platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import static platform.Contracts.*;

public final class MigrationRouter {
    private final QuoteBackend legacy, modern;
    private volatile int percentage;
    public MigrationRouter(QuoteBackend legacy, QuoteBackend modern) { this.legacy = legacy; this.modern = modern; }
    public void setPercentage(int value) {
        if (value < 0 || value > 100) throw new RuleException("invalid_percentage");
        percentage = value;
    }
    public static int bucket(UUID id) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(id.toString().getBytes(StandardCharsets.UTF_8));
            return (int)(Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt()) % 10_000);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public String route(UUID id) { return bucket(id) < percentage * 100 ? "modern" : "legacy"; }
    public Quote read(QuoteRequest request) {
        request.validate(); return (route(request.id()).equals("modern") ? modern : legacy).read(request);
    }
    public boolean compare(QuoteRequest request) {
        request.validate(); return legacy.read(request).equals(modern.read(request));
    }
}
