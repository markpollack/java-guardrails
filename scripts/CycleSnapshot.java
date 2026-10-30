///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.tngtech.archunit:archunit:1.4.1
//DEPS org.slf4j:slf4j-nop:1.7.36
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Util.java ArchUnitMeasure.java Findings.java

import java.nio.file.*;
import java.util.*;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/** Invoked after a clean, successful scratch compile. Missing imports never mean zero cycles. */
public class CycleSnapshot {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) { System.out.println("CycleSnapshot.java TARGET OUTPUT.json: consume freshly compiled classes; caller owns clean compile proof"); return; }
        Path target = Path.of(args[0]).toAbsolutePath().normalize(); long start = System.nanoTime();
        var decisions = Util.Decisions.read(target);
        var modules = Util.modules(target).stream().filter(m -> decisions.excludedModules().stream().noneMatch(x -> m.path().equals(x) || m.path().startsWith(x+"/"))).toList();
        List<Findings.Finding> findings = new ArrayList<>(); List<Path> files = new ArrayList<>();
        for (var module : modules) {
            Path dir = module.dir().resolve("target/classes");
            if (!Files.isDirectory(dir)) throw new IllegalStateException("Missing classes: " + module.path());
            var classes = new ClassFileImporter().importPath(dir);
            if (classes.isEmpty()) throw new IllegalStateException("Empty class import: " + module.path());
            try (var paths = Files.walk(dir)) { files.addAll(paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList()); }
            for (String kind : List.of("class", "package")) {
                var rule = SlicesRuleDefinition.slices().assignedFrom(kind.equals("class") ? ArchUnitMeasure.TOP_LEVEL_CLASS : ArchUnitMeasure.PACKAGE).should().beFreeOfCycles();
                for (var cycle : ArchUnitMeasure.evaluate(rule, classes, module.path(), kind)) {
                    String full = cycle.description() + "\n" + String.join("\n", cycle.details());
                    List<String> names = new ArrayList<>();
                    for (String line : full.split("\n")) {
                        if (line.contains("Dependencies of Slice")) break;
                        int pos = line.indexOf("Slice ");
                        if (pos >= 0) names.add(line.substring(pos + 6).replace(" ->", "").trim());
                    }
                    if (names.size() < 3 || !names.getFirst().equals(names.getLast())) throw new IllegalStateException("Unrecognized ArchUnit cycle: " + full);
                    names.removeLast(); List<String> edges = new ArrayList<>();
                    for (int i=0; i<names.size(); i++) edges.add(names.get(i) + " -> " + names.get((i+1)%names.size()));
                    Collections.sort(edges);
                    findings.add(new Findings.Finding(kind+"-cycle",module.path(),String.join("; ",edges),module.path(),full));
                }
            }
        }
        Findings.write(Path.of(args[1]),new Findings.Snapshot(1,"archunit","ArchUnit 1.4.1; package/top-level-class cycles",Findings.fingerprint(target,files),modules.stream().map(Util.Module::path).toList(),List.of("CycleSnapshot",target.toString()),true,findings,(System.nanoTime()-start)/1_000_000));
        System.out.println("Complete cycle snapshot: " + findings.size() + " findings");
    }
}
