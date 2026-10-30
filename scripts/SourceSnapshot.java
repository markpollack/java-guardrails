///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS net.sourceforge.pmd:pmd-java:7.28.0
//DEPS org.slf4j:slf4j-simple:1.7.36
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Util.java PmdMeasure.java Declarations.java Findings.java

import java.nio.file.*;
import java.util.*;
import net.sourceforge.pmd.cpd.*;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;

/** Complete source metrics and CPD, regardless of exemptions or installed gates. */
public class SourceSnapshot {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) { System.out.println("SourceSnapshot.java TARGET OUTPUT.json: PMD/CPD machine findings for pass validation"); return; }
        Path target = Path.of(args[0]).toAbsolutePath().normalize();
        long start = System.nanoTime();
        var decisions = Util.Decisions.read(target);
        var modules = Util.modules(target).stream().filter(m -> decisions.excludedModules().stream().noneMatch(x -> m.path().equals(x) || m.path().startsWith(x+"/"))).toList();
        if (modules.isEmpty()) throw new IllegalStateException("No selected source modules");
        PmdMeasure.MeasureRule.MODULES = modules;
        PmdMeasure.analyse(target, modules, Util.javaVersion(target));
        // Regression measurement deliberately does not apply CLASS_SIZE waivers.
        var rawDecisions = Util.Decisions.read(target);
        rawDecisions.all().remove("CLASS_SIZE");
        Map<Path,Declarations> bindings = new HashMap<>();
        List<Findings.Finding> findings = new ArrayList<>();
        for (var metric : PmdMeasure.metrics(rawDecisions)) for (var item : metric.items()) if (item.value() >= metric.reference()) {
            int colon = item.where().lastIndexOf(':');
            Path file = target.resolve(item.where().substring(0,colon));
            int line = Integer.parseInt(item.where().substring(colon+1));
            if (!bindings.containsKey(file)) bindings.put(file, new Declarations(file));
            String name = bindings.get(file).at(line, item.column(), metric.key().startsWith("class-"));
            findings.add(new Findings.Finding(metric.key(), item.module(), name, item.where(), "value="+item.value()+" threshold="+metric.reference()));
        }
        for (var module : modules) {
            CPDConfiguration config = new CPDConfiguration();
            config.setMinimumTileSize(100);
            config.setDefaultLanguageVersion(JavaLanguageModule.getInstance().getVersion(Util.javaVersion(target)));
            config.setSkipLexicalErrors(false);
            try (CpdAnalysis cpd = CpdAnalysis.create(config)) {
                cpd.files().addDirectory(module.sourceRoot());
                cpd.performAnalysis(report -> {
                    if (!report.getProcessingErrors().isEmpty()) throw new IllegalStateException("Incomplete CPD analysis: " + report.getProcessingErrors());
                    for (Match match : report.getMatches()) {
                        List<String> declarations = new ArrayList<>(), locations = new ArrayList<>();
                        for (Mark mark : match.getMarkSet()) try {
                            var location = mark.getLocation(); Path file = Path.of(location.getFileId().getAbsolutePath());
                            if (!bindings.containsKey(file)) bindings.put(file, new Declarations(file));
                            declarations.add(bindings.get(file).at(location.getStartLine(), location.getStartColumn(), false));
                            locations.add(target.relativize(file) + ":" + location.getStartLine());
                        } catch (Exception e) { throw new IllegalStateException("Cannot bind CPD occurrence", e); }
                        // Each occurrence participates. Adding another duplicate cannot consume an old pair twice.
                        Collections.sort(declarations);
                        for (int i=0; i<declarations.size(); i++) for (int j=i+1; j<declarations.size(); j++)
                            findings.add(new Findings.Finding("cpd", module.path(), declarations.get(i) + " = " + declarations.get(j), String.join(" = ", locations), "tokens="+match.getTokenCount()));
                    }
                });
            }
        }
        List<Path> files = new ArrayList<>(); for (var m : modules) files.addAll(Util.javaFiles(m.sourceRoot()));
        Findings.write(Path.of(args[1]), new Findings.Snapshot(1,"pmd","PMD 7.28.0; CPD 100; kit reference metrics",Findings.fingerprint(target, files),modules.stream().map(Util.Module::path).toList(),List.of("SourceSnapshot",target.toString()),true,findings,(System.nanoTime()-start)/1_000_000));
        System.out.println("Complete PMD/CPD snapshot: " + findings.size() + " findings");
    }
}
