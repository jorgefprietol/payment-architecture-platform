using System.Text;
using System.Text.Json;

namespace PaymentPlatform;

// One process owns this directory. A checkpoint includes inbox, snapshot and outbox.
public sealed class DurableSagas : IDisposable
{
    private readonly string root;
    private readonly FileStream ownership;
    private readonly object sync = new();
    private sealed record State(Snapshot Snapshot, List<Command> Pending, List<Command> Delivered);
    public DurableSagas(string directory)
    {
        root = Path.GetFullPath(directory); Directory.CreateDirectory(root);
        ownership = new FileStream(Path.Combine(root, "writer.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        foreach (var file in Directory.GetFiles(root, "*.saga")) Load(Guid.Parse(Path.GetFileNameWithoutExtension(file)));
    }
    private string PathFor(Guid id) => Path.Combine(root, id.ToString("D") + ".saga");
    private State? Load(Guid id)
    {
        if (!File.Exists(PathFor(id))) return null;
        var lines = File.ReadAllLines(PathFor(id), Encoding.UTF8);
        if (lines.Length < 3 || lines[0] != "PAYMENT-SAGA|1" || lines[1] != id.ToString("D")) throw new IOException("invalid_checkpoint");
        var seen = new List<WorkflowEvent>(); var pending = new List<Command>(); var delivered = new List<Command>();
        foreach (var line in lines.Skip(3))
        {
            var fields = line.Split('|');
            if (fields.Length != 3) throw new IOException("invalid_checkpoint");
            if (fields[0] == "event") seen.Add(new(Guid.Parse(fields[1]), id, Enum.Parse<Fact>(fields[2])));
            else if (fields[0] is "pending" or "delivered")
            {
                if (fields[2] is not ("reserve" or "check-risk" or "settle" or "release") || fields[1] != $"{id:D}:{fields[2]}") throw new IOException("invalid_checkpoint");
                (fields[0] == "pending" ? pending : delivered).Add(new(fields[1], id, fields[2]));
            }
            else throw new IOException("invalid_checkpoint");
        }
        var reconstructed = Snapshot.Empty(id); var expectedCommands = new List<Command>();
        foreach (var e in seen) { var transition = Workflow.Handle(reconstructed, e); reconstructed = transition.Snapshot; expectedCommands.AddRange(transition.Commands); }
        if (reconstructed.Stage.ToString() != lines[2] || reconstructed.Seen.Count != seen.Count || pending.Count > 1 ||
            !expectedCommands.SequenceEqual(delivered.Concat(pending))) throw new IOException("invalid_checkpoint");
        foreach (var c in delivered) if (!File.Exists(EffectPath(c)) || File.ReadAllText(EffectPath(c)) != c.Id + "\n") throw new IOException("invalid_receipt");
        return new(reconstructed, pending, delivered);
    }
    private static void AtomicWrite(string path, string text)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            { file.Write(Encoding.UTF8.GetBytes(text)); file.Flush(true); }
            File.Move(temporary, path, true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    private void Save(State state)
    {
        var text = new StringBuilder($"PAYMENT-SAGA|1\n{state.Snapshot.Id:D}\n{state.Snapshot.Stage}\n");
        foreach (var e in state.Snapshot.Seen) text.Append($"event|{e.Id:D}|{e.Fact}\n");
        foreach (var c in state.Pending) text.Append($"pending|{c.Id}|{c.Action}\n");
        foreach (var c in state.Delivered) text.Append($"delivered|{c.Id}|{c.Action}\n");
        AtomicWrite(PathFor(state.Snapshot.Id), text.ToString());
    }
    private string EffectPath(Command c) => Path.Combine(root, $"{c.SagaId:D}.{c.Action}.effect");
    private string View(State state) => JsonSerializer.Serialize(new {
        id = state.Snapshot.Id, stage = state.Snapshot.Stage.ToString(), events = state.Snapshot.Seen.Count,
        pending = state.Pending.Select(c => c.Id), delivered = state.Delivered.Select(c => c.Id),
        effects = state.Pending.Concat(state.Delivered).Count(c => File.Exists(EffectPath(c)))
    });
    public string Read(Guid id)
    { lock (sync) return View(Load(id) ?? throw new RuleException("not_found")); }
    public string Apply(Guid id, Guid eventId, Fact fact)
    {
        lock (sync)
        {
            var state = Load(id) ?? new State(Snapshot.Empty(id), new(), new());
            var transition = Workflow.Handle(state.Snapshot, new(eventId, id, fact));
            if (transition.Snapshot.Seen.Count == state.Snapshot.Seen.Count) return View(state);
            if (state.Pending.Count != 0) throw new RuleException("pending_commands");
            var next = new State(transition.Snapshot, transition.Commands.ToList(), state.Delivered);
            Save(next); return View(next);
        }
    }
    public string Dispatch(Guid id, Action? afterEffect = null)
    {
        lock (sync)
        {
            var state = Load(id) ?? throw new RuleException("not_found");
            foreach (var command in state.Pending)
            {
                var receipt = command.Id + "\n"; var path = EffectPath(command);
                // Receiver's receipt IS the local effect, committed before the sender's acknowledgement.
                if (File.Exists(path)) { if (File.ReadAllText(path) != receipt) throw new IOException("effect_conflict"); }
                else AtomicWrite(path, receipt);
                afterEffect?.Invoke();
            }
            if (state.Pending.Count > 0) { state = new(state.Snapshot, new(), state.Delivered.Concat(state.Pending).ToList()); Save(state); }
            return View(state);
        }
    }
    public void DispatchAll(Action? afterEffect = null)
    { lock (sync) foreach (var file in Directory.GetFiles(root, "*.saga")) Dispatch(Guid.Parse(Path.GetFileNameWithoutExtension(file)), afterEffect); }
    public void Dispose() => ownership.Dispose();
}
