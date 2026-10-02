package platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import static platform.Contracts.*;

public final class Api {
    private Api() {}
    private static void send(HttpExchange exchange, int status, String payload, String contentType) throws java.io.IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) { response.write(bytes); }
    }
    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new RuleException("invalid_request");
        return UUID.fromString(value);
    }
    public static void run() {
        String token = System.getenv().getOrDefault("API_TOKEN", "");
        if (token.length() < 32) throw new IllegalStateException("API_TOKEN must contain at least 32 characters");
        int percentage = Integer.parseInt(System.getenv().getOrDefault("MIGRATION_PERCENTAGE", "25"));
        var router = new MigrationRouter(new FixedFee(10), new FixedFee(20)); router.setPercentage(percentage);
        var telemetry = new Telemetry(); var gate = new Gatekeeper(() -> System.nanoTime() / 1_000_000, 60, 60_000);
        var principal = new Gatekeeper.Principal("local-client", Set.of("quotes:read"));
        try {
            var sagas = new DurableSagas(System.getenv().getOrDefault("SAGA_DATA_DIR", "state"));
            Runnable afterEffect = () -> { if ("1".equals(System.getenv("SAGA_FAIL_AFTER_EFFECT"))) Runtime.getRuntime().halt(86); };
            int port = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));
            var server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("HTTP_HOST", "0.0.0.0"), port), 128);
            var workers = Executors.newFixedThreadPool(8); server.setExecutor(workers);
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().startsWith("/sagas/")) {
                    sagaRequest(exchange, sagas, token, gate, principal, afterEffect); return;
                }
                if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json"); return; }
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/health/live") || path.equals("/health/ready")) { send(exchange, 200, "{\"status\":\"ok\"}", "application/json"); return; }
                if (path.equals("/metrics")) { send(exchange, 200, telemetry.metrics(), "text/plain; version=0.0.4"); return; }
                if (!path.equals("/quotes")) { send(exchange, 404, "{\"error\":\"not_found\"}", "application/json"); return; }
                long started = System.nanoTime(); String outcome = "ok"; var correlation = UUID.randomUUID();
                try {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ") || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
                        outcome = "unauthorized"; send(exchange, 401, "{\"error\":\"unauthorized\"}", "application/json"); return;
                    }
                    gate.authorize(principal, "local-client");
                    var query = new HashMap<String, String>(); String raw = exchange.getRequestURI().getRawQuery();
                    if (raw == null || raw.length() > 2048) throw new RuleException("invalid_request");
                    for (var part : raw.split("&")) {
                        var pair = part.split("=", 2);
                        if (pair.length != 2) throw new RuleException("invalid_request");
                        var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                        if (query.putIfAbsent(key, URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw new RuleException("invalid_request");
                    }
                    var id = uuid(query.get("id")); String amountText = query.get("amountMinor");
                    if (amountText == null || !amountText.matches("[0-9]+")) throw new RuleException("invalid_request");
                    var amount = Long.parseLong(amountText);
                    var quote = router.read(new QuoteRequest(id, amount, query.get("currency")));
                    String suppliedCorrelation = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                    if (suppliedCorrelation != null && !suppliedCorrelation.isEmpty()) correlation = uuid(suppliedCorrelation);
                    exchange.getResponseHeaders().set("X-Correlation-ID", correlation.toString());
                    send(exchange, 200, "{\"id\":\"" + id + "\",\"feeMinor\":" + quote.feeMinor() + ",\"currency\":\"" + quote.currency() +
                        "\",\"route\":\"" + router.route(id) + "\",\"correlationId\":\"" + correlation + "\"}", "application/json");
                } catch (IllegalArgumentException exception) {
                    outcome = "invalid_request"; send(exchange, 400, "{\"error\":\"invalid_request\"}", "application/json");
                } catch (RuleException exception) {
                    outcome = exception.getMessage();
                    if (outcome.equals("rate_limited")) exchange.getResponseHeaders().set("Retry-After", "60");
                    send(exchange, outcome.equals("rate_limited") ? 429 : 400, "{\"error\":\"" + outcome + "\"}", "application/json");
                } finally {
                    telemetry.record(!outcome.equals("ok"), (System.nanoTime() - started) / 1_000_000);
                    System.out.println(telemetry.log(Instant.now(), correlation, "quote-read", outcome));
                    exchange.close();
                }
            });
            var dispatcher = Executors.newSingleThreadScheduledExecutor();
            if (!"false".equals(System.getenv("SAGA_AUTODISPATCH"))) dispatcher.scheduleWithFixedDelay(() -> {
                try { sagas.dispatchAll(afterEffect); }
                catch (java.io.IOException failure) { System.out.println("{\"operation\":\"saga-dispatch\",\"outcome\":\"storage_unavailable\"}"); }
            }, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(2); workers.shutdown(); dispatcher.shutdown();
                try { dispatcher.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); sagas.close(); }
                catch (Exception failure) { System.err.println("saga_shutdown_failed"); }
            }));
            server.start(); System.out.println("{\"service\":\"payment-platform\",\"event\":\"started\",\"port\":" + port + "}");
        } catch (java.io.IOException exception) { throw new IllegalStateException("server_start_failed", exception); }
    }
    private static void sagaRequest(HttpExchange exchange, DurableSagas sagas, String token, Gatekeeper gate, Gatekeeper.Principal principal, Runnable afterEffect) throws java.io.IOException {
        try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}", "application/json"); return;
            }
            gate.authorize(principal, "local-client");
            var path = exchange.getRequestURI().getPath().split("/", -1);
            if (path.length < 3 || path.length > 4) throw new RuleException("not_found");
            var id = uuid(path[2]); if (id.equals(new UUID(0, 0))) throw new RuleException("invalid_request");
            String json;
            if (path.length == 3 && exchange.getRequestMethod().equals("GET")) json = sagas.read(id);
            else if (path.length == 4 && path[3].equals("dispatch") && exchange.getRequestMethod().equals("POST")) json = sagas.dispatch(id, afterEffect);
            else if (path.length == 4 && path[3].equals("events") && exchange.getRequestMethod().equals("POST")) {
                var query = new HashMap<String, String>(); String raw = exchange.getRequestURI().getRawQuery();
                if (raw == null || raw.length() > 2048) throw new RuleException("invalid_request");
                for (var part : raw.split("&")) {
                    var pair = part.split("=", 2); if (pair.length != 2) throw new RuleException("invalid_request");
                    if (query.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw new RuleException("invalid_request");
                }
                var eventId = uuid(query.get("eventId")); if (eventId.equals(new UUID(0, 0))) throw new RuleException("invalid_request");
                var fact = platform.Workflow.Fact.valueOf(query.getOrDefault("fact", ""));
                json = sagas.apply(id, eventId, fact);
            } else { send(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json"); return; }
            send(exchange, 200, json, "application/json");
        } catch (RuleException failure) {
            int status = switch (failure.getMessage()) { case "not_found" -> 404; case "idempotency_conflict", "unexpected_event", "pending_commands" -> 409; case "rate_limited" -> 429; default -> 400; };
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", "60");
            send(exchange, status, "{\"error\":\"" + failure.getMessage() + "\"}", "application/json");
        } catch (IllegalArgumentException failure) { send(exchange, 400, "{\"error\":\"invalid_request\"}", "application/json"); }
        catch (java.io.IOException failure) { send(exchange, 503, "{\"error\":\"storage_unavailable\"}", "application/json"); }
        finally { exchange.close(); }
    }
}
