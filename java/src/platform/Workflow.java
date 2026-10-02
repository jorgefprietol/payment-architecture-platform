package platform;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static platform.Contracts.*;

public final class Workflow {
    private Workflow() {}
    public enum Stage { New, Reserving, Checking, Settling, Compensating, Completed, Compensated }
    public enum Fact { Start, Reserved, Approved, Rejected, Settled, SettlementFailed, Released }
    public record Event(UUID id, UUID sagaId, Fact fact) {}
    public record Command(String id, UUID sagaId, String action) {}
    public record Snapshot(UUID id, Stage stage, List<Event> seen) {
        public Snapshot { seen = List.copyOf(seen); }
        public static Snapshot empty(UUID id) { return new Snapshot(id, Stage.New, List.of()); }
    }
    public record Transition(Snapshot snapshot, List<Command> commands) {
        public Transition { commands = List.copyOf(commands); }
    }
    public static Transition handle(Snapshot state, Event message) {
        if (state.id() == null || state.id().equals(new UUID(0, 0)) || message.id() == null ||
            message.id().equals(new UUID(0, 0)) || !state.id().equals(message.sagaId())) throw new RuleException("invalid_event");
        var prior = state.seen().stream().filter(event -> event.id().equals(message.id())).findFirst();
        if (prior.isPresent()) {
            if (!prior.get().equals(message)) throw new RuleException("idempotency_conflict");
            return new Transition(state, List.of());
        }
        Stage next; String action;
        if (state.stage() == Stage.New && message.fact() == Fact.Start) { next = Stage.Reserving; action = "reserve"; }
        else if (state.stage() == Stage.Reserving && message.fact() == Fact.Reserved) { next = Stage.Checking; action = "check-risk"; }
        else if (state.stage() == Stage.Checking && message.fact() == Fact.Approved) { next = Stage.Settling; action = "settle"; }
        else if ((state.stage() == Stage.Checking && message.fact() == Fact.Rejected) ||
                 (state.stage() == Stage.Settling && message.fact() == Fact.SettlementFailed)) { next = Stage.Compensating; action = "release"; }
        else if (state.stage() == Stage.Settling && message.fact() == Fact.Settled) { next = Stage.Completed; action = ""; }
        else if (state.stage() == Stage.Compensating && message.fact() == Fact.Released) { next = Stage.Compensated; action = ""; }
        else throw new RuleException("unexpected_event");
        var seen = new ArrayList<>(state.seen()); seen.add(message);
        return new Transition(new Snapshot(state.id(), next, seen), action.isEmpty() ? List.of() :
            List.of(new Command(state.id() + ":" + action, state.id(), action)));
    }
    public static Command react(UUID sagaId, Fact fact) {
        if (sagaId == null || sagaId.equals(new UUID(0, 0))) throw new RuleException("invalid_event");
        String action = switch (fact) {
            case Start -> "reserve"; case Reserved -> "check-risk"; case Approved -> "settle";
            case Rejected, SettlementFailed -> "release"; default -> "";
        };
        return action.isEmpty() ? null : new Command(sagaId + ":" + action, sagaId, action);
    }
}
