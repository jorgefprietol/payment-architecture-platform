using System.Text.Json;
using PaymentPlatform;

if (args.Contains("--demo", StringComparer.Ordinal)) { Demo.Run(); return; }
if (args.Contains("--serve", StringComparer.Ordinal))
{
    try { await Api.Run(); }
    catch (IOException failure) { Console.Error.WriteLine("server_start_failed: " + failure.Message); Environment.ExitCode = 1; }
    return;
}
if (args.Contains("--healthcheck", StringComparer.Ordinal))
{
    try { using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(3) }; Environment.ExitCode = (await client.GetAsync("http://127.0.0.1:8080/health/ready")).IsSuccessStatusCode ? 0 : 1; }
    catch (HttpRequestException) { Environment.ExitCode = 1; }
    catch (TaskCanceledException) { Environment.ExitCode = 1; }
    return;
}

var tests = new List<(string Name, Action Run)>();
void Test(string name, Action run) => tests.Add((name, run));
void Check(bool condition) { if (!condition) throw new Exception("assertion_failed"); }
void Error(string code, Action run)
{
    try { run(); } catch (RuleException e) { Check(e.Message == code); return; }
    throw new Exception("expected_" + code);
}
void Transient(Action run)
{
    try { run(); } catch (TransientFailure) { return; }
    throw new Exception("expected_transient");
}
var id = Guid.Parse("11111111-1111-1111-1111-111111111111");
QuoteRequest Request() => new(id, 1000, "USD");
MigrationRouter Router() => new(new FixedFee(10), new FixedFee(20));
Snapshot Advance(Snapshot state, Fact fact) => Workflow.Handle(state, new(Guid.NewGuid(), id, fact)).Snapshot;
TransferEvent Event(long amount = 100, string currency = "USD") => new(Guid.NewGuid(), Guid.NewGuid(), 1, amount, currency);

Test("migration_default_legacy", () => Check(Router().Read(Request()).FeeMinor == 10));
Test("migration_full_modern", () => { var r = Router(); r.SetPercentage(100); Check(r.Read(Request()).FeeMinor == 20); });
Test("migration_rollback", () => { var r = Router(); r.SetPercentage(100); r.SetPercentage(0); Check(r.Read(Request()).FeeMinor == 10); });
Test("migration_stable_canary", () =>
{
    var r = Router(); r.SetPercentage(25); var other = Router(); other.SetPercentage(25);
    for (var i = 1; i <= 200; i++)
    {
        var key = Guid.Parse($"00000000-0000-0000-0000-{i:000000000000}");
        Check(r.Route(key) == other.Route(key));
        var before = r.Route(key); r.SetPercentage(50);
        Check(before != "modern" || r.Route(key) == "modern"); r.SetPercentage(25);
    }
});
Test("migration_reject_percentage", () => { Error("invalid_percentage", () => Router().SetPercentage(-1)); Error("invalid_percentage", () => Router().SetPercentage(101)); });
Test("migration_dual_read_diff", () => Check(!Router().Compare(Request())));
Test("migration_dual_read_equal", () => Check(new MigrationRouter(new FixedFee(10), new FixedFee(10)).Compare(Request())));
Test("contract_reject_invalid_quote", () => Error("invalid_request", () => Router().Read(new(id, 0, "USD"))));
Test("saga_happy_path", () =>
{
    var state = Snapshot.Empty(id);
    foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Approved, Fact.Settled }) state = Advance(state, fact);
    Check(state.Stage == Stage.Completed);
});
Test("saga_risk_compensation", () =>
{
    var state = Snapshot.Empty(id);
    foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Rejected, Fact.Released }) state = Advance(state, fact);
    Check(state.Stage == Stage.Compensated);
});
Test("saga_settlement_compensation", () =>
{
    var state = Snapshot.Empty(id);
    foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Approved, Fact.SettlementFailed, Fact.Released }) state = Advance(state, fact);
    Check(state.Stage == Stage.Compensated);
});
Test("saga_duplicate_no_effect", () =>
{
    var message = new WorkflowEvent(Guid.NewGuid(), id, Fact.Start);
    var first = Workflow.Handle(Snapshot.Empty(id), message);
    var replay = Workflow.Handle(first.Snapshot, message);
    Check(replay.Commands.Count == 0 && replay.Snapshot.Seen.Count == 1);
});
Test("saga_resume_snapshot", () =>
{
    var state = Advance(Advance(Snapshot.Empty(id), Fact.Start), Fact.Reserved);
    var recovered = JsonSerializer.Deserialize<Snapshot>(JsonSerializer.Serialize(state))!;
    Check(Advance(Advance(recovered, Fact.Approved), Fact.Settled).Stage == Stage.Completed);
});
Test("saga_event_payload_conflict", () =>
{
    var message = new WorkflowEvent(Guid.NewGuid(), id, Fact.Start);
    var state = Workflow.Handle(Snapshot.Empty(id), message).Snapshot;
    Error("idempotency_conflict", () => Workflow.Handle(state, message with { Fact = Fact.Reserved }));
});
Test("saga_snapshot_immutable", () =>
{
    var events = new List<WorkflowEvent> { new(Guid.NewGuid(), id, Fact.Start) };
    var snapshot = new Snapshot(id, Stage.Reserving, events); events.Clear();
    Check(snapshot.Seen.Count == 1);
});
Test("saga_terminal_no_new_effect", () =>
{
    var state = Snapshot.Empty(id);
    foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Approved, Fact.Settled }) state = Advance(state, fact);
    Error("unexpected_event", () => Advance(state, Fact.Start));
});
Test("saga_out_of_order", () => Error("unexpected_event", () => Advance(Snapshot.Empty(id), Fact.Settled)));
Test("saga_wrong_correlation", () => Error("invalid_event", () => Workflow.Handle(Snapshot.Empty(id), new(Guid.NewGuid(), Guid.NewGuid(), Fact.Start))));
Test("saga_pending_compensation", () =>
{
    var state = Snapshot.Empty(id);
    foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Rejected }) state = Advance(state, fact);
    Check(state.Stage == Stage.Compensating);
    Error("unexpected_event", () => Advance(state, Fact.Settled));
});
Test("choreography_effect_parity", () =>
{
    var state = Snapshot.Empty(id);
    foreach (var fact in new[] { Fact.Start, Fact.Reserved, Fact.Approved, Fact.SettlementFailed, Fact.Released })
    {
        var transition = Workflow.Handle(state, new(Guid.NewGuid(), id, fact));
        var command = Choreography.React(id, fact);
        Check(command == (transition.Commands.Count == 0 ? null : transition.Commands[0])); state = transition.Snapshot;
    }
});
Test("retry_transient_then_success", () =>
{
    var calls = 0; var delays = new List<long>();
    var result = Retry.Execute(() => ++calls < 3 ? throw new TransientFailure() : 42, 3, 10, delays.Add);
    Check(result == 42 && calls == 3 && delays.SequenceEqual(new long[] { 10, 20 }));
});
Test("retry_bounded", () => { var calls = 0; Transient(() => Retry.Execute<int>(() => { calls++; throw new TransientFailure(); }, 3, 10, _ => { })); Check(calls == 3); });
Test("retry_business_error_once", () => { var calls = 0; Error("declined", () => Retry.Execute<int>(() => { calls++; throw new RuleException("declined"); }, 3, 10, _ => { })); Check(calls == 1); });
Test("retry_invalid_policy", () => Error("invalid_policy", () => Retry.Execute(() => 1, 11, 0, _ => { })));
Test("circuit_blocks_and_recovers", () =>
{
    long now = 0; var circuit = new CircuitBreaker(() => now, 2, 100);
    Transient(() => circuit.Execute<int>(() => throw new TransientFailure()));
    Transient(() => circuit.Execute<int>(() => throw new TransientFailure()));
    Error("circuit_open", () => circuit.Execute(() => 1)); now = 100;
    Check(circuit.Execute(() => 42) == 42 && circuit.State == CircuitState.Closed);
});
Test("circuit_failed_probe_reopens", () =>
{
    long now = 0; var circuit = new CircuitBreaker(() => now, 1, 100);
    Transient(() => circuit.Execute<int>(() => throw new TransientFailure())); now = 100;
    Transient(() => circuit.Execute<int>(() => throw new TransientFailure())); now = 199;
    Error("circuit_open", () => circuit.Execute(() => 1));
});
Test("circuit_business_error_healthy", () =>
{
    var circuit = new CircuitBreaker(() => 0, 1, 100);
    Error("declined", () => circuit.Execute<int>(() => throw new RuleException("declined")));
    Check(circuit.State == CircuitState.Closed);
});
Test("bulkhead_reject_and_release", () =>
{
    var bulkhead = new Bulkhead(1);
    bulkhead.Execute(() => { Error("bulkhead_full", () => bulkhead.Execute(() => 1)); return 0; });
    Error("declined", () => bulkhead.Execute<int>(() => throw new RuleException("declined")));
    Check(bulkhead.Execute(() => 42) == 42);
});
Test("analytics_duplicate_event", () => { var a = new Analytics(); var e = Event(); Check(a.Apply(e) && !a.Apply(e) && a.Total("USD") == 100); });
Test("analytics_duplicate_transfer", () => { var a = new Analytics(); var e = Event(); a.Apply(e); Check(!a.Apply(e with { EventId = Guid.NewGuid() }) && a.Total("USD") == 100); });
Test("analytics_payload_conflict", () => { var a = new Analytics(); var e = Event(); a.Apply(e); Error("idempotency_conflict", () => a.Apply(e with { AmountMinor = 101 })); Check(a.Total("USD") == 100); });
Test("analytics_transfer_conflict", () => { var a = new Analytics(); var e = Event(); a.Apply(e); Error("idempotency_conflict", () => a.Apply(e with { EventId = Guid.NewGuid(), Currency = "EUR" })); Check(a.Total("EUR") == 0); });
Test("analytics_currency_isolation", () => { var a = new Analytics(); a.Apply(Event(100)); a.Apply(Event(200, "EUR")); Check(a.Total("USD") == 100 && a.Total("EUR") == 200); });
Test("analytics_contract_version", () => { var a = new Analytics(); Error("invalid_contract", () => a.Apply(Event() with { SchemaVersion = 2 })); Check(a.Total("USD") == 0); });
Test("analytics_overflow_atomic", () =>
{
    var a = new Analytics(); a.Apply(Event(long.MaxValue)); var e = Event(1);
    Error("total_overflow", () => a.Apply(e)); Error("total_overflow", () => a.Apply(e)); Check(a.Total("USD") == long.MaxValue);
});
Test("analytics_concurrent_duplicates", () => { var a = new Analytics(); var e = Event(); Parallel.For(0, 100, _ => a.Apply(e)); Check(a.Total("USD") == 100); });
Test("telemetry_alert_minimum", () => { var t = new Telemetry(); t.Record(true, 10); Check(!t.Alert(10, 0.1)); });
Test("telemetry_alert_error_budget", () => { var t = new Telemetry(); for (var i = 0; i < 10; i++) t.Record(i < 2, 10); Check(t.Alert(10, 0.1) && !t.Alert(10, 0.2)); });
Test("telemetry_metrics", () => { var t = new Telemetry(); t.Record(false, 10); t.Record(true, 20); Check(t.Metrics() == "requests_total 2\nerrors_total 1\nduration_ms_total 30\n"); });
Test("telemetry_json_log", () =>
{
    using var json = JsonDocument.Parse(new Telemetry().Log(DateTimeOffset.Parse("2026-10-02T12:00:00Z"), id, "quote\n\"read\"", "ok"));
    Check(json.RootElement.GetProperty("correlationId").GetString() == id.ToString() && json.RootElement.GetProperty("operation").GetString() == "quote\n\"read\"");
});
Test("gatekeeper_owner_and_scope", () =>
{
    var g = new Gatekeeper(() => 0, 2, 100);
    Error("forbidden", () => g.Authorize(new("alice", new HashSet<string> { "quotes:read" }), "bob"));
    Error("forbidden", () => g.Authorize(new("alice", new HashSet<string>()), "alice"));
});
Test("gatekeeper_rate_window", () =>
{
    long now = 0; var g = new Gatekeeper(() => now, 2, 100); var p = new Principal("alice", new HashSet<string> { "quotes:read" });
    g.Authorize(p, "alice"); g.Authorize(p, "alice"); Error("rate_limited", () => g.Authorize(p, "alice"));
    now = 100; g.Authorize(p, "alice");
});
Test("gatekeeper_principal_isolation", () =>
{
    var g = new Gatekeeper(() => 0, 1, 100);
    g.Authorize(new("alice", new HashSet<string> { "quotes:read" }), "alice");
    g.Authorize(new("bob", new HashSet<string> { "quotes:read" }), "bob");
});

Module Mod(string name, int abstracts = 0, string? db = null) => new(name, 10, abstracts, name, db ?? name);
Test("modularity_static_metrics", () =>
{
    var graph = new ModuleAnalysis(new[] { Mod("api"), Mod("domain", 5) }, new[] { new Dependency("api", "domain", Coupling.Static) });
    var metrics = graph.Metrics();
    Check(metrics[0].Efferent == 1 && metrics[0].Instability == 1 && metrics[1].Afferent == 1 && metrics[1].Abstractness == 0.5);
});
Test("modularity_async_independence", () =>
{
    var graph = new ModuleAnalysis(new[] { Mod("ledger"), Mod("risk"), Mod("analytics") }, new[] {
        new Dependency("ledger", "risk", Coupling.Synchronous), new Dependency("ledger", "analytics", Coupling.Asynchronous) });
    var groups = graph.OperationalGroups(); Check(groups.Count == 2 && groups[1].SequenceEqual(new[] { "ledger", "risk" }));
});
Test("modularity_shared_data_couples", () =>
{
    var graph = new ModuleAnalysis(new[] { Mod("ledger", db: "shared"), Mod("risk", db: "shared") }, Array.Empty<Dependency>());
    Check(graph.OperationalGroups().Count == 1);
});
Test("modularity_invalid_dependency", () => Error("invalid_topology", () => new ModuleAnalysis(new[] { Mod("ledger") }, new[] { new Dependency("ledger", "missing", Coupling.Static) })));

var failed = 0;
foreach (var (name, run) in tests)
{
    try { run(); Console.WriteLine("PASS " + name); }
    catch (Exception error) { failed++; Console.WriteLine($"FAIL {name}: {error.Message}"); }
}
Console.WriteLine($"RESULT tests={tests.Count} failures={failed} bucket={MigrationRouter.Bucket(id)}");
var trace = new List<string>();
var canary = Router();
for (var i = 1; i <= 200; i++)
{
    var key = Guid.Parse($"00000000-0000-0000-0000-{i:000000000000}");
    canary.SetPercentage(25); var first = canary.Route(key); canary.SetPercentage(50);
    trace.Add($"{key:D}|{MigrationRouter.Bucket(key)}|{first}|{canary.Route(key)}");
}
Console.WriteLine("TRACE migration=" + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
    System.Text.Encoding.UTF8.GetBytes(string.Join("\n", trace)))).ToLowerInvariant());
Environment.ExitCode = failed == 0 ? 0 : 1;
