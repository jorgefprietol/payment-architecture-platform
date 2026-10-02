using System.Text.Json;

namespace PaymentPlatform;

public static class Demo
{
    public static void Run()
    {
        var id = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var gate = new Gatekeeper(() => Environment.TickCount64, 10, 60_000);
        gate.Authorize(new("synthetic-user", new HashSet<string> { "quotes:read" }), "synthetic-user");
        var router = new MigrationRouter(new FixedFee(10), new FixedFee(20)); router.SetPercentage(25);
        var quote = router.Read(new(id, 1000, "USD"));
        var state = Snapshot.Empty(id); var commands = 0;
        foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Approved, Fact.Settled })
        {
            var transition = Workflow.Handle(state, new(Guid.NewGuid(), id, fact));
            state = transition.Snapshot; commands += transition.Commands.Count;
        }
        var analytics = new Analytics(); var entry = new TransferEvent(Guid.NewGuid(), id, 1, 1000, "USD");
        analytics.Apply(entry); var duplicateApplied = analytics.Apply(entry);
        var telemetry = new Telemetry(); telemetry.Record(false, 12);
        Console.WriteLine(telemetry.Log(DateTimeOffset.UtcNow, id, "quote-read", "ok"));
        Console.WriteLine(JsonSerializer.Serialize(new { correlationId = id, route = router.Route(id), feeMinor = quote.FeeMinor,
            sagaState = state.Stage.ToString(), commands, volumeMinor = analytics.Total("USD"), duplicateApplied }));
    }
}
