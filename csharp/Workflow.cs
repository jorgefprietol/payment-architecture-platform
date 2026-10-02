namespace PaymentPlatform;

public enum Stage { New, Reserving, Checking, Settling, Compensating, Completed, Compensated }
public enum Fact { Start, Reserved, Approved, Rejected, Settled, SettlementFailed, Released }
public sealed record WorkflowEvent(Guid Id, Guid SagaId, Fact Fact);
public sealed record Command(string Id, Guid SagaId, string Action);
public sealed record Snapshot
{
    public Guid Id { get; }
    public Stage Stage { get; }
    public IReadOnlyList<WorkflowEvent> Seen { get; }
    public Snapshot(Guid id, Stage stage, IReadOnlyList<WorkflowEvent> seen)
    { Id = id; Stage = stage; Seen = Array.AsReadOnly(seen.ToArray()); }
    public static Snapshot Empty(Guid id) => new(id, Stage.New, Array.Empty<WorkflowEvent>());
}
public sealed record Transition(Snapshot Snapshot, IReadOnlyList<Command> Commands);

// Stateless orchestration: the adapter must commit snapshot + commands atomically.
// Each receiver must deduplicate Command.Id before applying its local transaction.
public static class Workflow
{
    public static Transition Handle(Snapshot state, WorkflowEvent message)
    {
        if (state.Id == Guid.Empty || message.Id == Guid.Empty || message.SagaId != state.Id)
            throw new RuleException("invalid_event");
        var prior = state.Seen.FirstOrDefault(x => x.Id == message.Id);
        if (prior is not null)
        {
            if (prior != message) throw new RuleException("idempotency_conflict");
            return new(state, Array.Empty<Command>());
        }
        var (next, action) = (state.Stage, message.Fact) switch
        {
            (Stage.New, Fact.Start) => (Stage.Reserving, "reserve"),
            (Stage.Reserving, Fact.Reserved) => (Stage.Checking, "check-risk"),
            (Stage.Checking, Fact.Approved) => (Stage.Settling, "settle"),
            (Stage.Checking, Fact.Rejected) => (Stage.Compensating, "release"),
            (Stage.Settling, Fact.SettlementFailed) => (Stage.Compensating, "release"),
            (Stage.Settling, Fact.Settled) => (Stage.Completed, ""),
            (Stage.Compensating, Fact.Released) => (Stage.Compensated, ""),
            _ => throw new RuleException("unexpected_event")
        };
        var snapshot = new Snapshot(state.Id, next, state.Seen.Append(message).ToArray());
        return new(snapshot, action.Length == 0 ? Array.Empty<Command>() :
            Array.AsReadOnly(new[] { new Command($"{state.Id:D}:{action}", state.Id, action) }));
    }
}

// Choreography alternative: local participants respond to facts, with the same effect keys.
public static class Choreography
{
    public static Command? React(Guid sagaId, Fact fact)
    {
        if (sagaId == Guid.Empty) throw new RuleException("invalid_event");
        var action = fact switch
        {
            Fact.Start => "reserve", Fact.Reserved => "check-risk", Fact.Approved => "settle",
            Fact.Rejected or Fact.SettlementFailed => "release", _ => ""
        };
        return action.Length == 0 ? null : new($"{sagaId:D}:{action}", sagaId, action);
    }
}
