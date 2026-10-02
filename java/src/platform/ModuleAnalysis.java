package platform;

import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import static platform.Contracts.*;

public final class ModuleAnalysis {
    public enum Coupling { Static, Synchronous, Asynchronous }
    public record Module(String name, int classes, int abstractClasses, String deployment, String database) {}
    public record Dependency(String from, String to, Coupling kind) {}
    public record Metric(String name, int afferent, int efferent, double abstractness, double instability, double distance) {}
    private final List<Module> modules;
    private final List<Dependency> dependencies;
    public ModuleAnalysis(List<Module> modules, List<Dependency> dependencies) {
        this.modules = List.copyOf(modules); this.dependencies = List.copyOf(dependencies);
        var names = modules.stream().map(Module::name).collect(java.util.stream.Collectors.toSet());
        if (names.size() != modules.size() || modules.stream().anyMatch(m -> m.name() == null || m.name().isBlank() ||
            m.deployment() == null || m.deployment().isBlank() || m.database() == null || m.database().isBlank() ||
            m.classes() <= 0 || m.abstractClasses() < 0 || m.abstractClasses() > m.classes()) ||
            dependencies.stream().anyMatch(d -> !names.contains(d.from()) || !names.contains(d.to()) || d.from().equals(d.to())))
            throw new RuleException("invalid_topology");
    }
    public List<Metric> metrics() {
        return modules.stream().sorted(java.util.Comparator.comparing(Module::name)).map(module -> {
            int afferent = (int)dependencies.stream().filter(d -> d.kind() == Coupling.Static && d.to().equals(module.name())).map(Dependency::from).distinct().count();
            int efferent = (int)dependencies.stream().filter(d -> d.kind() == Coupling.Static && d.from().equals(module.name())).map(Dependency::to).distinct().count();
            double abstraction = (double)module.abstractClasses() / module.classes();
            double instability = afferent + efferent == 0 ? 0 : (double)efferent / (afferent + efferent);
            return new Metric(module.name(), afferent, efferent, abstraction, instability, Math.abs(abstraction + instability - 1));
        }).toList();
    }
    private static String root(HashMap<String, String> parents, String name) {
        while (!parents.get(name).equals(name)) name = parents.get(name);
        return name;
    }
    public List<List<String>> operationalGroups() {
        var parents = new HashMap<String, String>(); modules.forEach(m -> parents.put(m.name(), m.name()));
        for (var edge : dependencies) if (edge.kind() != Coupling.Asynchronous) parents.put(root(parents, edge.from()), root(parents, edge.to()));
        for (int i = 0; i < modules.size(); i++) for (int j = i + 1; j < modules.size(); j++) {
            var a = modules.get(i); var b = modules.get(j);
            if (a.deployment().equals(b.deployment()) || a.database().equals(b.database())) parents.put(root(parents, a.name()), root(parents, b.name()));
        }
        var groups = new TreeMap<String, java.util.ArrayList<String>>();
        for (var module : modules) groups.computeIfAbsent(root(parents, module.name()), k -> new java.util.ArrayList<>()).add(module.name());
        return groups.values().stream().map(group -> group.stream().sorted().toList()).sorted(java.util.Comparator.comparing(group -> group.getFirst())).toList();
    }
}
