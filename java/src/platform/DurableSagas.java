package platform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static platform.Contracts.*;
import static platform.Workflow.*;

/** Single writer, atomic checkpoint containing inbox, snapshot and pending commands. */
public final class DurableSagas implements AutoCloseable {
    private final Path root;
    private final FileChannel ownership;
    private final FileLock writer;
    private record State(Snapshot snapshot, List<Command> pending, List<Command> delivered) {}
    public DurableSagas(String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath(); Files.createDirectories(root);
        ownership = FileChannel.open(root.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        writer = ownership.tryLock();
        if (writer == null) { ownership.close(); throw new IOException("writer_already_active"); }
        try (var files = Files.newDirectoryStream(root, "*.saga")) { for (var file : files) load(UUID.fromString(file.getFileName().toString().replace(".saga", ""))); }
    }
    private Path pathFor(UUID id) { return root.resolve(id + ".saga"); }
    private State load(UUID id) throws IOException {
        if (!Files.exists(pathFor(id))) return null;
        var lines = Files.readAllLines(pathFor(id), StandardCharsets.UTF_8);
        if (lines.size() < 3 || !lines.get(0).equals("PAYMENT-SAGA|1") || !lines.get(1).equals(id.toString())) throw new IOException("invalid_checkpoint");
        var seen = new ArrayList<Event>(); var pending = new ArrayList<Command>(); var delivered = new ArrayList<Command>();
        for (var line : lines.subList(3, lines.size())) {
            var fields = line.split("\\|", -1);
            if (fields.length != 3) throw new IOException("invalid_checkpoint");
            if (fields[0].equals("event")) seen.add(new Event(UUID.fromString(fields[1]), id, Fact.valueOf(fields[2])));
            else if (fields[0].equals("pending") || fields[0].equals("delivered")) {
                if (!Set.of("reserve", "check-risk", "settle", "release").contains(fields[2]) || !fields[1].equals(id + ":" + fields[2])) throw new IOException("invalid_checkpoint");
                (fields[0].equals("pending") ? pending : delivered).add(new Command(fields[1], id, fields[2]));
            } else throw new IOException("invalid_checkpoint");
        }
        var snapshot = Snapshot.empty(id); var expectedCommands = new ArrayList<Command>();
        for (var event : seen) { var transition = handle(snapshot, event); snapshot = transition.snapshot(); expectedCommands.addAll(transition.commands()); }
        var recorded = new ArrayList<>(delivered); recorded.addAll(pending);
        if (!snapshot.stage().toString().equals(lines.get(2)) || snapshot.seen().size() != seen.size() || pending.size() > 1 || !expectedCommands.equals(recorded)) throw new IOException("invalid_checkpoint");
        for (var c : delivered) if (!Files.exists(effectPath(c)) || !Files.readString(effectPath(c)).equals(c.id() + "\n")) throw new IOException("invalid_receipt");
        return new State(snapshot, pending, delivered);
    }
    private static void atomicWrite(Path path, String text) throws IOException {
        var temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void save(State state) throws IOException {
        var text = new StringBuilder("PAYMENT-SAGA|1\n" + state.snapshot().id() + "\n" + state.snapshot().stage() + "\n");
        for (var e : state.snapshot().seen()) text.append("event|").append(e.id()).append('|').append(e.fact()).append('\n');
        for (var c : state.pending()) text.append("pending|").append(c.id()).append('|').append(c.action()).append('\n');
        for (var c : state.delivered()) text.append("delivered|").append(c.id()).append('|').append(c.action()).append('\n');
        atomicWrite(pathFor(state.snapshot().id()), text.toString());
    }
    private Path effectPath(Command c) { return root.resolve(c.sagaId() + "." + c.action() + ".effect"); }
    private static String ids(List<Command> commands) { return "[" + String.join(",", commands.stream().map(c -> "\"" + c.id() + "\"").toList()) + "]"; }
    private String view(State state) {
        long effects = java.util.stream.Stream.concat(state.pending().stream(), state.delivered().stream()).filter(c -> Files.exists(effectPath(c))).count();
        return "{\"id\":\"" + state.snapshot().id() + "\",\"stage\":\"" + state.snapshot().stage() + "\",\"events\":" + state.snapshot().seen().size() +
            ",\"pending\":" + ids(state.pending()) + ",\"delivered\":" + ids(state.delivered()) + ",\"effects\":" + effects + "}";
    }
    public synchronized String read(UUID id) throws IOException {
        var state = load(id); if (state == null) throw new RuleException("not_found"); return view(state);
    }
    public synchronized String apply(UUID id, UUID eventId, Fact fact) throws IOException {
        var state = load(id); if (state == null) state = new State(Snapshot.empty(id), List.of(), List.of());
        var transition = handle(state.snapshot(), new Event(eventId, id, fact));
        if (transition.snapshot().seen().size() == state.snapshot().seen().size()) return view(state);
        if (!state.pending().isEmpty()) throw new RuleException("pending_commands");
        var next = new State(transition.snapshot(), transition.commands(), state.delivered()); save(next); return view(next);
    }
    public synchronized String dispatch(UUID id, Runnable afterEffect) throws IOException {
        var state = load(id); if (state == null) throw new RuleException("not_found");
        for (var command : state.pending()) {
            String receipt = command.id() + "\n"; var path = effectPath(command);
            if (Files.exists(path)) { if (!Files.readString(path).equals(receipt)) throw new IOException("effect_conflict"); }
            else atomicWrite(path, receipt);
            if (afterEffect != null) afterEffect.run();
        }
        if (!state.pending().isEmpty()) {
            var delivered = new ArrayList<>(state.delivered()); delivered.addAll(state.pending());
            state = new State(state.snapshot(), List.of(), delivered); save(state);
        }
        return view(state);
    }
    public synchronized void dispatchAll(Runnable afterEffect) throws IOException {
        try (var files = Files.newDirectoryStream(root, "*.saga")) { for (var file : files) dispatch(UUID.fromString(file.getFileName().toString().replace(".saga", "")), afterEffect); }
    }
    public void close() throws IOException { writer.release(); ownership.close(); }
}
