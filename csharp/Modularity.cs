namespace PaymentPlatform;

public enum Coupling { Static, Synchronous, Asynchronous }
public sealed record Module(string Name, int Classes, int AbstractClasses, string Deployment, string Database);
public sealed record Dependency(string From, string To, Coupling Kind);
public sealed record ModuleMetric(string Name, int Afferent, int Efferent, double Abstractness, double Instability, double Distance);

public sealed class ModuleAnalysis
{
    private readonly Module[] modules;
    private readonly Dependency[] dependencies;
    public ModuleAnalysis(IEnumerable<Module> modules, IEnumerable<Dependency> dependencies)
    {
        this.modules = modules.ToArray(); this.dependencies = dependencies.ToArray();
        var names = this.modules.Select(x => x.Name).ToHashSet(StringComparer.Ordinal);
        if (names.Count != this.modules.Length || this.modules.Any(x => string.IsNullOrWhiteSpace(x.Name) ||
            string.IsNullOrWhiteSpace(x.Deployment) || string.IsNullOrWhiteSpace(x.Database) ||
            x.Classes <= 0 || x.AbstractClasses < 0 || x.AbstractClasses > x.Classes) ||
            this.dependencies.Any(x => !names.Contains(x.From) || !names.Contains(x.To) || x.From == x.To))
            throw new RuleException("invalid_topology");
    }
    public IReadOnlyList<ModuleMetric> Metrics() => modules.OrderBy(x => x.Name, StringComparer.Ordinal).Select(module =>
    {
        var afferent = dependencies.Where(x => x.Kind == Coupling.Static && x.To == module.Name).Select(x => x.From).Distinct().Count();
        var efferent = dependencies.Where(x => x.Kind == Coupling.Static && x.From == module.Name).Select(x => x.To).Distinct().Count();
        var abstraction = (double)module.AbstractClasses / module.Classes;
        var instability = afferent + efferent == 0 ? 0 : (double)efferent / (afferent + efferent);
        return new ModuleMetric(module.Name, afferent, efferent, abstraction, instability, Math.Abs(abstraction + instability - 1));
    }).ToArray();

    // Conservative operational groups, not a measurement of domain cohesion or a definitive quantum.
    public IReadOnlyList<IReadOnlyList<string>> OperationalGroups()
    {
        var parent = modules.ToDictionary(x => x.Name, x => x.Name);
        string Root(string name) { while (parent[name] != name) name = parent[name]; return name; }
        void Union(string a, string b) { parent[Root(a)] = Root(b); }
        foreach (var edge in dependencies.Where(x => x.Kind != Coupling.Asynchronous)) Union(edge.From, edge.To);
        for (var i = 0; i < modules.Length; i++)
            for (var j = i + 1; j < modules.Length; j++)
                if (modules[i].Deployment == modules[j].Deployment || modules[i].Database == modules[j].Database)
                    Union(modules[i].Name, modules[j].Name);
        return modules.GroupBy(x => Root(x.Name)).Select(group => (IReadOnlyList<string>)group.Select(x => x.Name)
            .OrderBy(x => x, StringComparer.Ordinal).ToArray()).OrderBy(x => x[0], StringComparer.Ordinal).ToArray();
    }
}
