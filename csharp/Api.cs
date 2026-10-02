using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace PaymentPlatform;

public static class Api
{
    public static async Task Run()
    {
        var token = Environment.GetEnvironmentVariable("API_TOKEN") ?? "";
        if (token.Length < 32) throw new InvalidOperationException("API_TOKEN must contain at least 32 characters");
        var percentage = int.Parse(Environment.GetEnvironmentVariable("MIGRATION_PERCENTAGE") ?? "25", System.Globalization.CultureInfo.InvariantCulture);
        var router = new MigrationRouter(new FixedFee(10), new FixedFee(20)); router.SetPercentage(percentage);
        var telemetry = new Telemetry();
        var gate = new Gatekeeper(() => Environment.TickCount64, 60, 60_000);
        var principal = new Principal("local-client", new HashSet<string> { "quotes:read" });
        using var sagas = new DurableSagas(Environment.GetEnvironmentVariable("SAGA_DATA_DIR") ?? "state");
        Action afterEffect = () => { if (Environment.GetEnvironmentVariable("SAGA_FAIL_AFTER_EFFECT") == "1") Environment.Exit(86); };
        bool Authorized(HttpContext context)
        {
            var value = context.Request.Headers.Authorization.ToString();
            return value.StartsWith("Bearer ", StringComparison.Ordinal) && CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(token), Encoding.UTF8.GetBytes(value[7..]));
        }
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls("http://" + (Environment.GetEnvironmentVariable("HTTP_HOST") ?? "0.0.0.0") + ":" + (Environment.GetEnvironmentVariable("HTTP_PORT") ?? "8080"));
        builder.WebHost.ConfigureKestrel(options => { options.Limits.MaxRequestBodySize = 4096; options.Limits.MaxConcurrentConnections = 128; });
        builder.Logging.ClearProviders(); builder.Logging.AddJsonConsole();
        var app = builder.Build();
        app.MapGet("/health/live", () => Results.Json(new { status = "ok" }));
        app.MapGet("/health/ready", () => Results.Json(new { status = "ok" }));
        app.MapGet("/metrics", () => Results.Text(telemetry.Metrics(), "text/plain; version=0.0.4"));
        IResult SagaRequest(HttpContext context, string id, string operation)
        {
            if (!Authorized(context)) return Results.Json(new { error = "unauthorized" }, statusCode: 401);
            try
            {
                gate.Authorize(principal, "local-client");
                if (!Guid.TryParseExact(id, "D", out var sagaId) || sagaId == Guid.Empty) throw new RuleException("invalid_request");
                string json;
                if (operation == "read") json = sagas.Read(sagaId);
                else if (operation == "dispatch") json = sagas.Dispatch(sagaId, afterEffect);
                else
                {
                    var eventText = context.Request.Query["eventId"].ToString(); var factText = context.Request.Query["fact"].ToString();
                    if (!Guid.TryParseExact(eventText, "D", out var eventId) || eventId == Guid.Empty ||
                        !Enum.TryParse<Fact>(factText, out var fact) || !Enum.IsDefined(fact) || fact.ToString() != factText) throw new RuleException("invalid_request");
                    json = sagas.Apply(sagaId, eventId, fact);
                }
                return Results.Text(json, "application/json");
            }
            catch (RuleException error)
            {
                var status = error.Message switch { "not_found" => 404, "idempotency_conflict" or "unexpected_event" or "pending_commands" => 409, "rate_limited" => 429, _ => 400 };
                if (status == 429) context.Response.Headers.RetryAfter = "60";
                return Results.Json(new { error = error.Message }, statusCode: status);
            }
            catch (IOException) { return Results.Json(new { error = "storage_unavailable" }, statusCode: 503); }
        }
        app.MapGet("/sagas/{id}", (HttpContext c, string id) => SagaRequest(c, id, "read"));
        app.MapPost("/sagas/{id}/events", (HttpContext c, string id) => SagaRequest(c, id, "event"));
        app.MapPost("/sagas/{id}/dispatch", (HttpContext c, string id) => SagaRequest(c, id, "dispatch"));
        app.MapGet("/quotes", (HttpContext context) =>
        {
            var timer = Stopwatch.StartNew(); var outcome = "ok";
            var correlationId = Guid.NewGuid();
            try
            {
                var authorization = context.Request.Headers.Authorization.ToString();
                if (!authorization.StartsWith("Bearer ", StringComparison.Ordinal) || !CryptographicOperations.FixedTimeEquals(
                    Encoding.UTF8.GetBytes(token), Encoding.UTF8.GetBytes(authorization[7..])))
                { outcome = "unauthorized"; return Results.Json(new { error = outcome }, statusCode: 401); }
                gate.Authorize(principal, "local-client");
                if (!Guid.TryParseExact(context.Request.Query["id"].ToString(), "D", out var id) ||
                    !long.TryParse(context.Request.Query["amountMinor"], System.Globalization.NumberStyles.None,
                        System.Globalization.CultureInfo.InvariantCulture, out var amount)) throw new RuleException("invalid_request");
                var suppliedCorrelation = context.Request.Headers["X-Correlation-ID"].ToString();
                if (suppliedCorrelation.Length > 0 && !Guid.TryParseExact(suppliedCorrelation, "D", out correlationId))
                    throw new RuleException("invalid_request");
                var quote = router.Read(new(id, amount, context.Request.Query["currency"].ToString()));
                context.Response.Headers["X-Correlation-ID"] = correlationId.ToString("D");
                return Results.Json(new { id, feeMinor = quote.FeeMinor, currency = quote.Currency, route = router.Route(id), correlationId });
            }
            catch (RuleException error)
            {
                outcome = error.Message;
                if (outcome == "rate_limited") context.Response.Headers.RetryAfter = "60";
                return Results.Json(new { error = outcome }, statusCode: outcome == "rate_limited" ? 429 : 400);
            }
            finally
            {
                telemetry.Record(outcome != "ok", timer.ElapsedMilliseconds);
                Console.WriteLine(telemetry.Log(DateTimeOffset.UtcNow, correlationId, "quote-read", outcome));
            }
        });
        using var stop = new CancellationTokenSource();
        var dispatcher = Task.Run(async () =>
        {
            while (!stop.IsCancellationRequested && Environment.GetEnvironmentVariable("SAGA_AUTODISPATCH") != "false")
            {
                try { sagas.DispatchAll(afterEffect); }
                catch (IOException) { Console.WriteLine("{\"operation\":\"saga-dispatch\",\"outcome\":\"storage_unavailable\"}"); }
                try { await Task.Delay(1000, stop.Token); } catch (OperationCanceledException) { break; }
            }
        });
        try { await app.RunAsync(); } finally { stop.Cancel(); await dispatcher; }
    }
}
