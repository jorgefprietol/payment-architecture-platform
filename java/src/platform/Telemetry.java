package platform;

import java.time.Instant;
import java.util.UUID;
import static platform.Contracts.*;

public final class Telemetry {
    private long requests, errors, elapsedMs;
    public synchronized void record(boolean failed, long durationMs) {
        if (durationMs < 0) throw new RuleException("invalid_duration");
        requests++; if (failed) errors++; elapsedMs += durationMs;
    }
    public synchronized boolean alert(int minimumRequests, double errorBudget) {
        if (minimumRequests <= 0 || !Double.isFinite(errorBudget) || errorBudget < 0 || errorBudget > 1)
            throw new RuleException("invalid_policy");
        return requests >= minimumRequests && (double)errors / requests > errorBudget;
    }
    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    public String log(Instant now, UUID correlationId, String operation, String outcome) {
        return "{\"timestamp\":" + quote(now.toString()) + ",\"correlationId\":" + quote(correlationId.toString()) +
            ",\"service\":\"payment-platform\",\"operation\":" + quote(operation) + ",\"outcome\":" + quote(outcome) + "}";
    }
    public synchronized String metrics() {
        return "requests_total " + requests + "\nerrors_total " + errors + "\nduration_ms_total " + elapsedMs + "\n";
    }
}
