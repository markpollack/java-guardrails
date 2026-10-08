///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS com.tngtech.archunit:archunit:1.4.1
//DEPS org.slf4j:slf4j-nop:1.7.36
//SOURCES Util.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * Measure a target's package and class dependency cycles before gating them: for each gated
 * module, import its compiled main classes, derive the root package, and evaluate the two rules
 * the kit's {@code NoCyclesTest} template will enforce (no cycles between packages at any depth;
 * no cycles between top-level classes of one package). Prints every cycle, with the dependencies
 * that close it, and ends with the STOP block when there is a decision to make.
 *
 * Needs nothing installed: ArchUnit runs here as a library over {@code target/classes}, which the
 * script compiles first through the target's wrapper. The gate itself is the test class the
 * playbook drops into each module (playbook 20, step 3).
 *
 * Usage: {@code ./jbang measure-archunit [target] [--top N]}
 */
public class ArchUnitMeasure {

	record Cycle(String module, String kind, String description, List<String> details) {
	}

	static final int FIX_LIMIT = 5;

	/** Every top-level class is a slice; its nested classes belong to it. */
	static final SliceAssignment TOP_LEVEL_CLASS = new SliceAssignment() {
		@Override
		public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
			return SliceIdentifier.of(javaClass.getName().split("\\$")[0]);
		}

		@Override
		public String getDescription() {
			return "top-level classes";
		}
	};

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		int top = intOption(args, "--top", 6);
		if (!Files.isDirectory(target) || !Files.isRegularFile(target.resolve("pom.xml"))) {
			System.err.println("measure-archunit: not a Maven project: " + target);
			System.exit(2);
		}
		Util.Decisions decisions = Util.Decisions.read(target);
		List<Util.Module> modules = Util.modules(target);
		Set<String> excluded = decisions.excludedModules().stream().collect(Collectors.toSet());
		List<Util.Module> gated = new ArrayList<>();
		for (Util.Module m : modules) {
			boolean leftOut = excluded.stream().anyMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/"));
			if (!leftOut && Util.javaFiles(m.sourceRoot()).stream().anyMatch(p -> !p.getFileName().toString().equals("module-info.java"))) {
				gated.add(m);
			}
		}

		System.out.println("# Dependency-cycle measurement: " + target.getFileName());
		System.out.println();
		System.out.println("Target `" + target + "`, ArchUnit 1.4.1 over each gated module's compiled main classes; modules the owner left out skip.");
		decisions.print();

		String pl = gated.stream().map(Util.Module::path).collect(Collectors.joining(","));
		List<String> compile = List.of(Util.mvnw(target), "-B", "-q", "-Dspring-javaformat.skip=true", "-DskipTests", "-P", "!errorprone", "-pl", pl, "compile");
		System.out.println("Compiling: " + String.join(" ", compile));
		System.out.println();
		Process p = new ProcessBuilder(compile).directory(target.toFile()).inheritIO().start();
		if (p.waitFor() != 0) {
			System.err.println("measure-archunit: compile failed; nothing measured");
			System.exit(1);
		}

		List<Cycle> cycles = new ArrayList<>();
		System.out.println("## Modules");
		System.out.println();
		System.out.println("| module | classes | root package | packages | package cycles | class cycles |");
		System.out.println("|---|---:|---|---:|---:|---:|");
		for (Util.Module m : gated) {
			Path classesDir = m.dir().resolve("target").resolve("classes");
			if (!Files.isDirectory(classesDir)) {
				continue;
			}
			JavaClasses classes = new ClassFileImporter().importPath(classesDir);
			Set<String> packages = new TreeSet<>();
			classes.forEach(c -> packages.add(c.getPackageName()));
			packages.remove("");
			if (packages.isEmpty()) {
				continue;
			}
			String root = commonPrefix(packages);
			ArchRule noPackageCycles = SlicesRuleDefinition.slices().matching(root + ".(**)").should().beFreeOfCycles();
			ArchRule noClassCycles = SlicesRuleDefinition.slices().assignedFrom(TOP_LEVEL_CLASS).should().beFreeOfCycles();
			List<Cycle> pkg = evaluate(noPackageCycles, classes, m.path(), "package");
			List<Cycle> cls = evaluate(noClassCycles, classes, m.path(), "class");
			cycles.addAll(pkg);
			cycles.addAll(cls);
			System.out.println("| " + m.path() + " | " + classes.size() + " | " + root + " | " + packages.size() + " | " + pkg.size() + " | " + cls.size() + " |");
		}
		System.out.println();

		System.out.println("## Cycles");
		System.out.println();
		if (cycles.isEmpty()) {
			System.out.println("None. Both rules pass in every gated module.");
		}
		for (Cycle c : cycles) {
			System.out.println("### " + c.module() + ", " + c.kind() + " cycle: " + c.description());
			c.details().stream().limit(top).forEach(d -> System.out.println("- " + d));
			if (c.details().size() > top) {
				System.out.println("- … " + (c.details().size() - top) + " more dependencies in this cycle");
			}
			System.out.println();
		}

		System.out.println("## Stops");
		System.out.println();
		if (cycles.isEmpty() || decisions.has("ARCHUNIT_CYCLES")) {
			System.out.println("None. " + (cycles.isEmpty() ? "Install the no-cycles test in each module (playbook step 3) and falsify."
					: cycles.size() + " cycles to break (playbook step 4), then measure again."));
			return;
		}
		System.out.println("Relay the block below to the owner as written, record the answer in `" + target.relativize(decisions.file) + "`, then run measure-archunit again.");
		System.out.println();
		System.out.println("```");
		System.out.println("STOP ARCHUNIT_CYCLES");
		System.out.println("  " + cycles.size() + " dependency cycle" + (cycles.size() == 1 ? "" : "s") + " in the gated modules, listed above. Breaking a cycle moves a type or inverts a dependency, and can change public API.");
		System.out.println("  Options: (a) fix-all: break every cycle now, one per commit, API changes recorded   (b) scope: gate the modules that are already cycle-free, break the rest later");
		System.out.println("  Recommendation: " + (cycles.size() <= FIX_LIMIT ? "(a), " + cycles.size() + " is a session's work" : "(b), more than " + FIX_LIMIT));
		System.out.println("  Record as:  ARCHUNIT_CYCLES: fix-all\n          or  ARCHUNIT_CYCLES: scope <module, module>");
		System.out.println("```");
	}

	static List<Cycle> evaluate(ArchRule rule, JavaClasses classes, String module, String kind) {
		EvaluationResult result = rule.evaluate(classes);
		List<Cycle> cycles = new ArrayList<>();
		for (String failure : result.getFailureReport().getDetails()) {
			String[] lines = failure.split("\n");
			String head = lines[0].trim();
			List<String> details = new ArrayList<>();
			for (int i = 1; i < lines.length; i++) {
				String l = lines[i].trim();
				if (!l.isEmpty()) {
					details.add(l);
				}
			}
			cycles.add(new Cycle(module, kind, head, details));
		}
		return cycles;
	}

	/** The longest package prefix shared by every package, on dot boundaries. */
	static String commonPrefix(Set<String> packages) {
		String[] first = packages.iterator().next().split("\\.");
		int keep = first.length;
		for (String pkg : packages) {
			String[] parts = pkg.split("\\.");
			int i = 0;
			while (i < keep && i < parts.length && parts[i].equals(first[i])) {
				i++;
			}
			keep = i;
		}
		return String.join(".", java.util.Arrays.copyOf(first, Math.max(keep, 1)));
	}

	static int intOption(String[] args, String name, int dflt) {
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals(name)) {
				return Integer.parseInt(args[i + 1]);
			}
		}
		return dflt;
	}
}
