package platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import static platform.Contracts.*;
import static platform.Workflow.*;
import static platform.Resilience.*;
import static platform.Analytics.*;

public final class Main {
    private record Test(String name, Runnable run) {}
    private static final List<Test> tests = new ArrayList<>();
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static void test(String name, Runnable run) { tests.add(new Test(name, run)); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("assertion_failed"); }
    private static void error(String code, Runnable run) {
        try { run.run(); } catch (RuleException exception) { check(exception.getMessage().equals(code)); return; }
        throw new AssertionError("expected_" + code);
    }
    private static void transientError(Runnable run) {
        try { run.run(); } catch (TransientFailure exception) { return; }
        throw new AssertionError("expected_transient");
    }
    private static QuoteRequest request() { return new QuoteRequest(ID, 1000, "USD"); }
    private static MigrationRouter router() { return new MigrationRouter(new FixedFee(10), new FixedFee(20)); }
    private static Snapshot advance(Snapshot state, Fact fact) { return handle(state, new Event(UUID.randomUUID(), ID, fact)).snapshot(); }
    private static TransferEvent event(long amount, String currency) { return new TransferEvent(UUID.randomUUID(), UUID.randomUUID(), 1, amount, currency); }
    private static TransferEvent event() { return event(100, "USD"); }

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--demo")) { Demo.run(); return; }
        if (java.util.Arrays.asList(args).contains("--serve")) { Api.run(); return; }
        if (java.util.Arrays.asList(args).contains("--healthcheck")) {
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8080), 2000); socket.setSoTimeout(2000);
                socket.getOutputStream().write("GET /health/ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                String status = reader.readLine(); System.exit(status != null && status.startsWith("HTTP/1.1 200 ") ? 0 : 1);
            } catch (java.io.IOException failure) { System.exit(1); }
            return;
        }
        test("migration_default_legacy", () -> check(router().read(request()).feeMinor() == 10));
        test("migration_full_modern", () -> { var r = router(); r.setPercentage(100); check(r.read(request()).feeMinor() == 20); });
        test("migration_rollback", () -> { var r = router(); r.setPercentage(100); r.setPercentage(0); check(r.read(request()).feeMinor() == 10); });
        test("migration_stable_canary", () -> {
            var r = router(); r.setPercentage(25); var other = router(); other.setPercentage(25);
            for (int i = 1; i <= 200; i++) {
                var key = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i));
                check(r.route(key).equals(other.route(key))); var before = r.route(key); r.setPercentage(50);
                check(!before.equals("modern") || r.route(key).equals("modern")); r.setPercentage(25);
            }
        });
        test("migration_reject_percentage", () -> { error("invalid_percentage", () -> router().setPercentage(-1)); error("invalid_percentage", () -> router().setPercentage(101)); });
        test("migration_dual_read_diff", () -> check(!router().compare(request())));
        test("migration_dual_read_equal", () -> check(new MigrationRouter(new FixedFee(10), new FixedFee(10)).compare(request())));
        test("contract_reject_invalid_quote", () -> error("invalid_request", () -> router().read(new QuoteRequest(ID, 0, "USD"))));
        test("saga_happy_path", () -> {
            var state = Snapshot.empty(ID);
            for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Approved, Fact.Settled)) state = advance(state, fact);
            check(state.stage() == Stage.Completed);
        });
        test("saga_risk_compensation", () -> {
            var state = Snapshot.empty(ID);
            for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Rejected, Fact.Released)) state = advance(state, fact);
            check(state.stage() == Stage.Compensated);
        });
        test("saga_settlement_compensation", () -> {
            var state = Snapshot.empty(ID);
            for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Approved, Fact.SettlementFailed, Fact.Released)) state = advance(state, fact);
            check(state.stage() == Stage.Compensated);
        });
        test("saga_duplicate_no_effect", () -> {
            var message = new Event(UUID.randomUUID(), ID, Fact.Start);
            var first = handle(Snapshot.empty(ID), message); var replay = handle(first.snapshot(), message);
            check(replay.commands().isEmpty() && replay.snapshot().seen().size() == 1);
        });
        test("saga_resume_snapshot", () -> {
            var state = advance(advance(Snapshot.empty(ID), Fact.Start), Fact.Reserved);
            // Round-trip a checkpoint's scalar fields and event identifiers without external libraries.
            String checkpoint = state.id() + "\n" + state.stage() + "\n" + String.join(",", state.seen().stream().map(e -> e.id() + ":" + e.fact()).toList());
            String[] lines = checkpoint.split("\n");
            var seen = java.util.Arrays.stream(lines[2].split(",")).map(value -> {
                var fields = value.split(":"); return new Event(UUID.fromString(fields[0]), ID, Fact.valueOf(fields[1]));
            }).toList();
            var recovered = new Snapshot(UUID.fromString(lines[0]), Stage.valueOf(lines[1]), seen);
            check(advance(advance(recovered, Fact.Approved), Fact.Settled).stage() == Stage.Completed);
        });
        test("saga_event_payload_conflict", () -> {
            var message = new Event(UUID.randomUUID(), ID, Fact.Start); var state = handle(Snapshot.empty(ID), message).snapshot();
            error("idempotency_conflict", () -> handle(state, new Event(message.id(), ID, Fact.Reserved)));
        });
        test("saga_snapshot_immutable", () -> {
            var events = new ArrayList<Event>(); events.add(new Event(UUID.randomUUID(), ID, Fact.Start));
            var snapshot = new Snapshot(ID, Stage.Reserving, events); events.clear(); check(snapshot.seen().size() == 1);
        });
        test("saga_terminal_no_new_effect", () -> {
            var state = Snapshot.empty(ID);
            for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Approved, Fact.Settled)) state = advance(state, fact);
            var terminal = state; error("unexpected_event", () -> advance(terminal, Fact.Start));
        });
        test("saga_out_of_order", () -> error("unexpected_event", () -> advance(Snapshot.empty(ID), Fact.Settled)));
        test("saga_wrong_correlation", () -> error("invalid_event", () -> handle(Snapshot.empty(ID), new Event(UUID.randomUUID(), UUID.randomUUID(), Fact.Start))));
        test("saga_pending_compensation", () -> {
            var state = Snapshot.empty(ID);
            for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Rejected)) state = advance(state, fact);
            check(state.stage() == Stage.Compensating); var pending = state;
            error("unexpected_event", () -> advance(pending, Fact.Settled));
        });
        test("choreography_effect_parity", () -> {
            var state = Snapshot.empty(ID);
            for (var fact : List.of(Fact.Start, Fact.Reserved, Fact.Approved, Fact.SettlementFailed, Fact.Released)) {
                var transition = handle(state, new Event(UUID.randomUUID(), ID, fact)); var command = react(ID, fact);
                check(java.util.Objects.equals(command, transition.commands().isEmpty() ? null : transition.commands().getFirst()));
                state = transition.snapshot();
            }
        });
        test("retry_transient_then_success", () -> {
            int[] calls = {0}; var delays = new ArrayList<Long>();
            int result = retry(() -> { if (++calls[0] < 3) throw new TransientFailure(); return 42; }, 3, 10, delays::add);
            check(result == 42 && calls[0] == 3 && delays.equals(List.of(10L, 20L)));
        });
        test("retry_bounded", () -> { int[] calls = {0}; transientError(() -> retry(() -> { calls[0]++; throw new TransientFailure(); }, 3, 10, n -> {})); check(calls[0] == 3); });
        test("retry_business_error_once", () -> { int[] calls = {0}; error("declined", () -> retry(() -> { calls[0]++; throw new RuleException("declined"); }, 3, 10, n -> {})); check(calls[0] == 1); });
        test("retry_invalid_policy", () -> error("invalid_policy", () -> retry(() -> 1, 11, 0, n -> {})));
        test("circuit_blocks_and_recovers", () -> {
            long[] now = {0}; var circuit = new CircuitBreaker(() -> now[0], 2, 100);
            transientError(() -> circuit.execute(() -> { throw new TransientFailure(); }));
            transientError(() -> circuit.execute(() -> { throw new TransientFailure(); }));
            error("circuit_open", () -> circuit.execute(() -> 1)); now[0] = 100;
            check(circuit.execute(() -> 42) == 42 && circuit.state() == CircuitState.Closed);
        });
        test("circuit_failed_probe_reopens", () -> {
            long[] now = {0}; var circuit = new CircuitBreaker(() -> now[0], 1, 100);
            transientError(() -> circuit.execute(() -> { throw new TransientFailure(); })); now[0] = 100;
            transientError(() -> circuit.execute(() -> { throw new TransientFailure(); })); now[0] = 199;
            error("circuit_open", () -> circuit.execute(() -> 1));
        });
        test("circuit_business_error_healthy", () -> {
            var circuit = new CircuitBreaker(() -> 0, 1, 100);
            error("declined", () -> circuit.execute(() -> { throw new RuleException("declined"); })); check(circuit.state() == CircuitState.Closed);
        });
        test("bulkhead_reject_and_release", () -> {
            var bulkhead = new Bulkhead(1);
            bulkhead.execute(() -> { error("bulkhead_full", () -> bulkhead.execute(() -> 1)); return 0; });
            error("declined", () -> bulkhead.execute(() -> { throw new RuleException("declined"); }));
            check(bulkhead.execute(() -> 42) == 42);
        });
        test("analytics_duplicate_event", () -> { var a = new Analytics(); var e = event(); check(a.apply(e) && !a.apply(e) && a.total("USD") == 100); });
        test("analytics_duplicate_transfer", () -> { var a = new Analytics(); var e = event(); a.apply(e); check(!a.apply(new TransferEvent(UUID.randomUUID(), e.transferId(), 1, e.amountMinor(), e.currency())) && a.total("USD") == 100); });
        test("analytics_payload_conflict", () -> { var a = new Analytics(); var e = event(); a.apply(e); error("idempotency_conflict", () -> a.apply(new TransferEvent(e.eventId(), e.transferId(), 1, 101, "USD"))); check(a.total("USD") == 100); });
        test("analytics_transfer_conflict", () -> { var a = new Analytics(); var e = event(); a.apply(e); error("idempotency_conflict", () -> a.apply(new TransferEvent(UUID.randomUUID(), e.transferId(), 1, 100, "EUR"))); check(a.total("EUR") == 0); });
        test("analytics_currency_isolation", () -> { var a = new Analytics(); a.apply(event(100, "USD")); a.apply(event(200, "EUR")); check(a.total("USD") == 100 && a.total("EUR") == 200); });
        test("analytics_contract_version", () -> { var a = new Analytics(); var e = event(); error("invalid_contract", () -> a.apply(new TransferEvent(e.eventId(), e.transferId(), 2, 100, "USD"))); check(a.total("USD") == 0); });
        test("analytics_overflow_atomic", () -> {
            var a = new Analytics(); a.apply(event(Long.MAX_VALUE, "USD")); var e = event(1, "USD");
            error("total_overflow", () -> a.apply(e)); error("total_overflow", () -> a.apply(e)); check(a.total("USD") == Long.MAX_VALUE);
        });
        test("analytics_concurrent_duplicates", () -> {
            var a = new Analytics(); var e = event();
            try (var pool = Executors.newFixedThreadPool(8)) {
                var jobs = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 100; i++) jobs.add(pool.submit(() -> a.apply(e)));
                for (var job : jobs) try { job.get(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            check(a.total("USD") == 100);
        });
        test("telemetry_alert_minimum", () -> { var t = new Telemetry(); t.record(true, 10); check(!t.alert(10, 0.1)); });
        test("telemetry_alert_error_budget", () -> { var t = new Telemetry(); for (int i = 0; i < 10; i++) t.record(i < 2, 10); check(t.alert(10, 0.1) && !t.alert(10, 0.2)); });
        test("telemetry_metrics", () -> { var t = new Telemetry(); t.record(false, 10); t.record(true, 20); check(t.metrics().equals("requests_total 2\nerrors_total 1\nduration_ms_total 30\n")); });
        test("telemetry_json_log", () -> {
            String json = new Telemetry().log(Instant.parse("2026-10-02T12:00:00Z"), ID, "quote\n\"read\"", "ok");
            check(json.contains("\"correlationId\":\"" + ID + "\"") && json.contains("quote\\u000a\\\"read\\\""));
        });
        test("gatekeeper_owner_and_scope", () -> {
            var g = new Gatekeeper(() -> 0, 2, 100);
            error("forbidden", () -> g.authorize(new Gatekeeper.Principal("alice", Set.of("quotes:read")), "bob"));
            error("forbidden", () -> g.authorize(new Gatekeeper.Principal("alice", Set.of()), "alice"));
        });
        test("gatekeeper_rate_window", () -> {
            long[] now = {0}; var g = new Gatekeeper(() -> now[0], 2, 100); var p = new Gatekeeper.Principal("alice", Set.of("quotes:read"));
            g.authorize(p, "alice"); g.authorize(p, "alice"); error("rate_limited", () -> g.authorize(p, "alice"));
            now[0] = 100; g.authorize(p, "alice");
        });
        test("gatekeeper_principal_isolation", () -> {
            var g = new Gatekeeper(() -> 0, 1, 100);
            g.authorize(new Gatekeeper.Principal("alice", Set.of("quotes:read")), "alice");
            g.authorize(new Gatekeeper.Principal("bob", Set.of("quotes:read")), "bob");
        });

        test("modularity_static_metrics", () -> {
            var graph = new ModuleAnalysis(List.of(new ModuleAnalysis.Module("api", 10, 0, "api", "api"), new ModuleAnalysis.Module("domain", 10, 5, "domain", "domain")),
                List.of(new ModuleAnalysis.Dependency("api", "domain", ModuleAnalysis.Coupling.Static)));
            var metrics = graph.metrics();
            check(metrics.get(0).efferent() == 1 && metrics.get(0).instability() == 1 && metrics.get(1).afferent() == 1 && metrics.get(1).abstractness() == 0.5);
        });
        test("modularity_async_independence", () -> {
            var graph = new ModuleAnalysis(List.of(new ModuleAnalysis.Module("ledger", 10, 0, "ledger", "ledger"), new ModuleAnalysis.Module("risk", 10, 0, "risk", "risk"), new ModuleAnalysis.Module("analytics", 10, 0, "analytics", "analytics")),
                List.of(new ModuleAnalysis.Dependency("ledger", "risk", ModuleAnalysis.Coupling.Synchronous), new ModuleAnalysis.Dependency("ledger", "analytics", ModuleAnalysis.Coupling.Asynchronous)));
            var groups = graph.operationalGroups(); check(groups.size() == 2 && groups.get(1).equals(List.of("ledger", "risk")));
        });
        test("modularity_shared_data_couples", () -> {
            var graph = new ModuleAnalysis(List.of(new ModuleAnalysis.Module("ledger", 10, 0, "ledger", "shared"), new ModuleAnalysis.Module("risk", 10, 0, "risk", "shared")), List.of());
            check(graph.operationalGroups().size() == 1);
        });
        test("modularity_invalid_dependency", () -> error("invalid_topology", () -> new ModuleAnalysis(List.of(new ModuleAnalysis.Module("ledger", 10, 0, "ledger", "ledger")),
            List.of(new ModuleAnalysis.Dependency("ledger", "missing", ModuleAnalysis.Coupling.Static)))));

        int failed = 0;
        for (var test : tests) {
            try { test.run().run(); System.out.println("PASS " + test.name()); }
            catch (RuntimeException | AssertionError failure) { failed++; System.out.println("FAIL " + test.name() + ": " + failure.getMessage()); }
        }
        System.out.println("RESULT tests=" + tests.size() + " failures=" + failed + " bucket=" + MigrationRouter.bucket(ID));
        var trace = new ArrayList<String>(); var canary = router();
        for (int i = 1; i <= 200; i++) {
            var key = UUID.fromString(String.format(java.util.Locale.ROOT, "00000000-0000-0000-0000-%012d", i));
            canary.setPercentage(25); var first = canary.route(key); canary.setPercentage(50);
            trace.add(key + "|" + MigrationRouter.bucket(key) + "|" + first + "|" + canary.route(key));
        }
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n", trace).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("TRACE migration=" + java.util.HexFormat.of().formatHex(digest));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        if (failed != 0) System.exit(1);
    }
}
