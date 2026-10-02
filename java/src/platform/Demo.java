package platform;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static platform.Contracts.*;
import static platform.Workflow.*;

public final class Demo {
    private Demo() {}
    public static void run() {
        var id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var gate = new Gatekeeper(() -> System.nanoTime() / 1_000_000, 10, 60_000);
        gate.authorize(new Gatekeeper.Principal("synthetic-user", Set.of("quotes:read")), "synthetic-user");
        var router = new MigrationRouter(new FixedFee(10), new FixedFee(20)); router.setPercentage(25);
        var quote = router.read(new QuoteRequest(id, 1000, "USD"));
        var state = Snapshot.empty(id); int commands = 0;
        for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Approved, Fact.Settled)) {
            var transition = handle(state, new Event(UUID.randomUUID(), id, fact));
            state = transition.snapshot(); commands += transition.commands().size();
        }
        var analytics = new Analytics(); var entry = new Analytics.TransferEvent(UUID.randomUUID(), id, 1, 1000, "USD");
        analytics.apply(entry); boolean duplicateApplied = analytics.apply(entry);
        var telemetry = new Telemetry(); telemetry.record(false, 12);
        System.out.println(telemetry.log(Instant.now(), id, "quote-read", "ok"));
        System.out.println("{\"correlationId\":\"" + id + "\",\"route\":\"" + router.route(id) + "\",\"feeMinor\":" + quote.feeMinor() +
            ",\"sagaState\":\"" + state.stage() + "\",\"commands\":" + commands + ",\"volumeMinor\":" + analytics.total("USD") +
            ",\"duplicateApplied\":" + duplicateApplied + "}");
    }
}
