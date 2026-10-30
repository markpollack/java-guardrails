///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//SOURCES Util.java
//SOURCES NullAwaySupport.java
//SOURCES Plant.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Prove a gate is live by planting a violation in the target's main sources, running the build
 * step that should fail, and restoring the file byte for byte, on Ctrl-C too ({@link Plant}). Exit 0
 * means the gate caught the plant (red as expected); exit 1 means the build stayed green, so the
 * gate checks nothing.
 *
 * Usage: {@code ./jbang falsify <target> <gate>} where gate is {@code pmd}, {@code cpd}, {@code errorprone}, {@code spotbugs}, {@code jacoco}, {@code archunit} or {@code nullaway}.
 */
public class Falsify {

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		String gate = args.length > 0 ? args[args.length - 1] : "";
		if (args.length > 1 && args[1].equals("nullaway")) {
			System.exit(falsifyNullAway(target, args));
			return;
		}
		if (!List.of("pmd", "cpd", "errorprone", "spotbugs", "jacoco", "archunit").contains(gate)) {
			System.err.println("falsify: name the gate to falsify: pmd | cpd | errorprone | spotbugs | jacoco | archunit | nullaway");
			System.exit(2);
		}
		if (gate.equals("archunit")) {
			falsifyArchUnit(target);
			return;
		}
		Path file = firstClassFile(target, gate);
		Path module = moduleOf(target, file);
		byte[] original = Files.readAllBytes(file);
		System.out.println("falsify " + gate + ": planting in " + target.relativize(file) + " (module " + target.relativize(module) + ")");
		int exit;
		StringBuilder output = new StringBuilder();
		try (Plant planted = new Plant("falsify " + gate, target)) {
			planted.replace(file, plant(gate, new String(original, java.nio.charset.StandardCharsets.UTF_8)));
			exit = run(target, module, gate, output);
		}
		// A brownfield module may be red already, so exit code alone proves nothing: the report must name the plant.
		String evidence;
		if (gate.equals("errorprone") || gate.equals("jacoco")) {
			evidence = output.toString(); // the compiler output, or jacoco:check's rule message, is the report
		}
		else {
			Path report = module.resolve("target").resolve(gate.equals("pmd") ? "pmd.xml" : gate.equals("cpd") ? "cpd.xml" : "spotbugsXml.xml");
			evidence = Files.exists(report) ? Files.readString(report) : "";
		}
		List<String> fired = rulesFired(gate, evidence, file);
		if (gate.equals("errorprone") && fired.isEmpty() && evidence.contains("warnings found and -Werror specified")
				&& !evidence.contains("] [")) {
			System.out.println("falsify errorprone: NOT PROVEN; the module has javac warnings of its own (deprecation for removal, classpath), "
					+ "and under -Werror javac stops before Error Prone runs. The gate is red for those warnings, which is right, "
					+ "but the plant was not checked. Fix the javac warnings first (playbook 11, step 5), then falsify again.");
			System.exit(1);
		}
		if (exit == 0 || fired.isEmpty()) {
			System.out.println("falsify " + gate + ": FAILED; build exit " + exit + ", planted violation reported by " + fired
					+ "; the gate checks nothing");
			System.exit(1);
		}
		System.out.println("falsify " + gate + ": OK, build red (exit " + exit + ") and the planted violation was reported by " + fired);
	}

	/** Plant in an EXISTING marked package: adding a marker to the probe would hide missing adoption scope. */
	static int falsifyNullAway(Path target, String[] args) throws Exception {
		NullAwaySupport.requireProfile(target);
		String selectedModule = NullAwaySupport.option(args, "--module");
		String selectedPackage = NullAwaySupport.option(args, "--package");
		var scope = NullAwaySupport.packages(target, selectedModule).stream()
				.filter(NullAwaySupport.PackageScope::marked)
				.filter(p -> selectedPackage.isEmpty() || p.name().equals(selectedPackage)).findFirst();
		if (scope.isEmpty()) {
			System.err.println("falsify nullaway: NOT PROVEN; no selected package has JSpecify @NullMarked. "
					+ "Choose and mark a package first (playbook 30). No sources changed.");
			return 1;
		}
		var pkg = scope.orElseThrow();
		Path probe = pkg.directory().resolve("GuardrailsNullAwayProbe.java");
		String source = (pkg.name().isEmpty() ? "" : "package " + pkg.name() + ";\n")
				+ "// planted by java-guardrails falsify: must never be committed\n"
				+ "final class GuardrailsNullAwayProbe {\n"
				+ "  static String guardrailsPlantedViolation() { return null; }\n}\n";
		StringBuilder output = new StringBuilder();
		int exit;
		try (Plant planted = new Plant("falsify nullaway", target)) {
			// Change only the return expression between two compiles with identical gate flags.
			// The valid control must pass: a pre-existing failure cannot prove this diagnostic fatal.
			planted.create(probe, source.replace("return null;", "return \"\";"));
			StringBuilder control = new StringBuilder();
			int controlExit = NullAwaySupport.compile(target, pkg.module().path(), false, control);
			if (controlExit != 0) {
				System.err.println(control);
				System.err.println("falsify nullaway: FAILED; NOT PROVEN; valid-probe control compile already fails (exit " + controlExit + "). "
						+ (control.toString().contains("warnings found and -Werror specified")
								? "The module has javac warnings; -Werror can stop analysis before NullAway runs. " : "")
						+ "Clear the existing build failure or explicitly select the intended checks before retrying.");
				return 1;
			}
			planted.replace(probe, source);
			System.out.println("falsify nullaway: planting in " + pkg.key());
			exit = NullAwaySupport.compile(target, pkg.module().path(), false, output);
		}
		catch (java.nio.file.FileAlreadyExistsException e) {
			System.err.println("falsify nullaway: NOT PROVEN; existing probe left untouched: " + probe
					+ ". Inspect it before manually removing a verified leftover.");
			return 1;
		}
		boolean named = output.toString().lines().anyMatch(line -> line.contains("GuardrailsNullAwayProbe.java:[")
				&& line.contains("[NullAway]") && line.contains("returning @Nullable"));
		if (exit == 0 || !named) {
			System.err.println(output);
			System.err.println("falsify nullaway: FAILED; build exit " + exit + ", planted NullAway diagnostic "
					+ (named ? "reported" : "not reported") + "; the selected scope is NOT PROVEN");
			return 1;
		}
		System.out.println("falsify nullaway: OK, valid control green, nullable return red with [NullAway] in " + pkg.key()
				+ "; new unannotated probe only; existing declaration opt-outs and exclusions are not audited");
		return 0;
	}

	/** The rules that reported the planted member, from the module's PMD or CPD report, or the compiler output for Error Prone. */
	static List<String> rulesFired(String gate, String xml, Path plantedFile) {
		if (gate.equals("cpd")) {
			return xml.contains("guardrailsPlantedCopy") ? List.of("cpd") : List.of();
		}
		if (gate.equals("jacoco")) {
			// jacoco:check names the broken rule: "Rule violated for bundle <module>: lines covered ratio is 0.26, but expected minimum is 0.28"
			for (String line : xml.split("\n")) {
				if (line.contains("Rule violated") && line.contains("lines covered ratio")) {
					return List.of("coverage floor: " + line.substring(line.indexOf("Rule violated")).trim());
				}
			}
			return List.of();
		}
		if (gate.equals("spotbugs")) {
			// a BugInstance on the planted method: the report is one line, so find the pattern next to the method name
			int at = xml.indexOf("NP_ALWAYS_NULL");
			while (at >= 0) {
				int end = xml.indexOf("</BugInstance>", at);
				if (end > 0 && xml.substring(at, end).contains("guardrailsPlantedViolation")) {
					return List.of("NP_ALWAYS_NULL");
				}
				at = xml.indexOf("NP_ALWAYS_NULL", at + 1);
			}
			return List.of();
		}
		if (gate.equals("errorprone")) {
			// a diagnostic on the planted file naming the check: "[ERROR] /path/File.java:[12,5] [DeadException] ..."
			// Maven prints each diagnostic twice (inline and in the summary); report the check once
			for (String line : xml.split("\n")) {
				if (line.contains(plantedFile.getFileName().toString() + ":[") && line.contains("[DeadException]")) {
					return List.of("DeadException");
				}
			}
			return List.of();
		}
		List<String> rules = new java.util.ArrayList<>();
		for (String violation : xml.split("<violation ")) {
			if (violation.contains("method=\"guardrailsPlantedViolation\"")) {
				int at = violation.indexOf("rule=\"") + 6;
				rules.add(violation.substring(at, violation.indexOf('"', at)));
			}
		}
		return rules;
	}

	/**
	 * ArchUnit: a cycle needs two classes in two packages that reference each other, so this
	 * gate plants two new files in the first gated module's first two packages, runs that
	 * module's NoCyclesTest, deletes the files, and passes only if the test failed naming the
	 * planted classes in a cycle.
	 */
	static void falsifyArchUnit(Path target) throws Exception {
		// the first gated module that has the test installed; planting where there is no NoCyclesTest proves nothing
		Path module = null;
		List<Path> eligible = eligibleModules(target, "archunit");
		try (Stream<Path> walk = Files.walk(target)) {
			List<Path> tests = walk.filter(p -> p.getFileName().toString().equals("NoCyclesTest.java") && p.toString().contains("/src/test/java/"))
					.filter(p -> eligible.contains(moduleOf(target, p)))
					.filter(p -> !gateSkipped(target, moduleOf(target, p))).sorted().collect(java.util.stream.Collectors.toList());
			if (!tests.isEmpty()) {
				module = moduleOf(target, tests.get(0));
			}
		}
		if (module == null) {
			System.out.println("falsify archunit: no module has a NoCyclesTest; install it first (playbook 20, step 3)");
			System.exit(2);
		}
		Path sourceRoot = module.resolve("src").resolve("main").resolve("java");
		List<Path> packageDirs;
		try (Stream<Path> walk = Files.walk(sourceRoot)) {
			packageDirs = walk.filter(Files::isDirectory)
					.filter(d -> { try (Stream<Path> f = Files.list(d)) { return f.anyMatch(x -> x.toString().endsWith(".java") && !x.getFileName().toString().startsWith("module-info")); } catch (IOException e) { return false; } })
					.sorted().limit(2).collect(java.util.stream.Collectors.toList());
		}
		if (packageDirs.isEmpty()) {
			System.out.println("falsify archunit: the module has no main-source package to plant in");
			System.exit(2);
		}
		// A single package still has a class-cycle gate: plant both classes there.
		if (packageDirs.size() == 1) packageDirs = List.of(packageDirs.get(0), packageDirs.get(0));
		String pkgA = sourceRoot.relativize(packageDirs.get(0)).toString().replace(java.io.File.separatorChar, '.');
		String pkgB = sourceRoot.relativize(packageDirs.get(1)).toString().replace(java.io.File.separatorChar, '.');
		Path a = packageDirs.get(0).resolve("GuardrailsPlantedA.java");
		Path b = packageDirs.get(1).resolve("GuardrailsPlantedB.java");
		System.out.println("falsify archunit: planting " + target.relativize(a) + " <-> " + target.relativize(b) + " (module " + target.relativize(module) + ")");
		int exit;
		StringBuilder output = new StringBuilder();
		try (Plant planted = new Plant("falsify archunit", target)) {
			planted.create(a, (pkgA.isEmpty() ? "" : "package " + pkgA + ";\n") + "\n// planted by java-guardrails falsify: must never be committed\npublic class GuardrailsPlantedA {\n\tstatic Object other() {\n\t\treturn new " + (pkgB.isEmpty() ? "" : pkgB + ".") + "GuardrailsPlantedB();\n\t}\n}\n");
			planted.create(b, (pkgB.isEmpty() ? "" : "package " + pkgB + ";\n") + "\n// planted by java-guardrails falsify: must never be committed\npublic class GuardrailsPlantedB {\n\tstatic Object other() {\n\t\treturn " + (pkgA.isEmpty() ? "" : pkgA + ".") + "GuardrailsPlantedA.class;\n\t}\n}\n");
			List<String> command = Util.maven(target, "archunit", target.equals(module) ? "." : target.relativize(module).toString());
			command.addAll(List.of("-Dtest=NoCyclesTest", "-Dsurefire.failIfNoSpecifiedTests=false", "clean", "test"));
			System.out.println("falsify: " + String.join(" ", command));
			exit = Util.run(command, target, line -> output.append(line).append('\n'));
		}
		catch (java.nio.file.FileAlreadyExistsException e) {
			System.out.println("falsify archunit: NOT PROVEN; " + e.getFile() + " already exists and was left untouched. "
					+ "Inspect it before removing a verified leftover.");
			System.exit(1);
			return;
		}
		boolean named = output.toString().contains("Cycle detected") && output.toString().contains("GuardrailsPlanted");
		if (exit == 0 || !named) {
			System.out.print(output);
			System.out.println("falsify archunit: FAILED; build exit " + exit + ", planted cycle " + (named ? "reported" : "not reported")
					+ (output.toString().contains("No tests were executed") || !output.toString().contains("NoCyclesTest") ? "; is NoCyclesTest installed in the module?" : "") + "; the gate checks nothing");
			System.exit(1);
		}
		System.out.println("falsify archunit: OK, build red (exit " + exit + ") and NoCyclesTest reported the planted cycle");
	}

	/** Append a violating member before the type's closing brace. */
	static String plant(String gate, String source) {
		int close = source.lastIndexOf('}');
		String member = gate.equals("pmd") ? PLANTED_METHOD : gate.equals("cpd") ? PLANTED_COPIES
				: gate.equals("spotbugs") ? PLANTED_NULL_DEREFERENCE : gate.equals("jacoco") ? plantedUncoveredLines() : PLANTED_DEAD_EXCEPTION;
		return source.substring(0, close) + member + source.substring(close);
	}

	/**
	 * A local set to null and dereferenced: SpotBugs' NP_ALWAYS_NULL, rank 5, in the gate by rank,
	 * found by dataflow with no dependence on the surrounding class (the IS2_INCONSISTENT_SYNC plant
	 * tried first fires only when the class has other synchronized code), and not an Error Prone
	 * check (Error Prone's null analysis is NullAway, off outside null-marked packages).
	 */
	static final String PLANTED_NULL_DEREFERENCE = """

		// planted by java-guardrails falsify: must never be committed
		static int guardrailsPlantedViolation() {
			String planted = null;
			return planted.length();
		}
""";

	/**
	 * Enough never-executed lines to push a module's line coverage more than the floor's 2-point
	 * margin below what it measured: 800 statements in one method nothing calls. A smaller plant
	 * would be absorbed by the margin and prove nothing.
	 */
	static String plantedUncoveredLines() {
		StringBuilder b = new StringBuilder("\n\t// planted by java-guardrails falsify: must never be committed\n\tstatic long guardrailsPlantedViolation(long seed) {\n\t\tlong total = seed;\n");
		for (int i = 1; i <= 800; i++) {
			b.append("\t\ttotal = total * 31 + ").append(i).append(";\n");
		}
		return b.append("\t\treturn total;\n\t}\n").toString();
	}

	/** An exception created and dropped: Error Prone's DeadException, an ERROR by default, a real bug shape. */
	static final String PLANTED_DEAD_EXCEPTION = """

		// planted by java-guardrails falsify: must never be committed
		static void guardrailsPlantedViolation(int a) {
			if (a < 0) {
				new IllegalArgumentException("negative");
			}
		}
""";

	/** Cognitive 15+, cyclomatic 10+, NCSS 30+, 7+ parameters: every PMD rule in the gate fires. */
	static final String PLANTED_METHOD = """

		// planted by java-guardrails falsify: must never be committed
		static int guardrailsPlantedViolation(int a, int b, int c, int d, int e, int f, int g) {
			int total = 0;
			for (int i = 0; i < a; i++) {
				if (i % 2 == 0) {
					if (b > i) {
						total += 1;
						total += 2;
						total += 3;
					} else if (c > i) {
						total += 4;
						total += 5;
						total += 6;
					} else {
						total += 7;
						total += 8;
					}
				} else if (d > i) {
					while (e > total) {
						total += 9;
						if (f > total) {
							total += 10;
						}
					}
				} else if (g > i && f > g || e > d) {
					total += 11;
					total += 12;
					total += 13;
					total += 14;
					total += 15;
					total += 16;
					total += 17;
					total += 18;
				}
			}
			return total;
		}
""";

	/** Two copies of a 100+-token block in one file: CPD fires at cpd.minimumTokens 100. */
	static final String PLANTED_COPIES = """

		// planted by java-guardrails falsify: must never be committed
		static int guardrailsPlantedCopyOne(int a, int b) {
			int total = 0;
			total += a * 2 + b * 3;
			total += a * 4 + b * 5;
			total += a * 6 + b * 7;
			total += a * 8 + b * 9;
			total += a * 10 + b * 11;
			total += a * 12 + b * 13;
			total += a * 14 + b * 15;
			total += a * 16 + b * 17;
			total += a * 18 + b * 19;
			total += a * 20 + b * 21;
			total += a * 22 + b * 23;
			total += a * 24 + b * 25;
			return total;
		}

		static int guardrailsPlantedCopyTwo(int a, int b) {
			int total = 0;
			total += a * 2 + b * 3;
			total += a * 4 + b * 5;
			total += a * 6 + b * 7;
			total += a * 8 + b * 9;
			total += a * 10 + b * 11;
			total += a * 12 + b * 13;
			total += a * 14 + b * 15;
			total += a * 16 + b * 17;
			total += a * 18 + b * 19;
			total += a * 20 + b * 21;
			total += a * 22 + b * 23;
			total += a * 24 + b * 25;
			return total;
		}
""";

	/** Runs the gate's check for the module, echoing and collecting its output. */
	static int run(Path target, Path module, String gate, StringBuilder output) throws IOException, InterruptedException {
		String mod = target.equals(module) ? "." : target.relativize(module).toString();
		List<String> command = Util.maven(target, gate, mod);
		command.add("clean");
		if (gate.equals("jacoco")) command.add("verify");
		else {
			command.addAll(List.of("-DskipTests", "compile"));
			if (gate.equals("spotbugs")) command.add("spotbugs:check");
			if (gate.equals("pmd")) command.add("pmd:check@pmd-gate");
			if (gate.equals("cpd")) command.add("pmd:cpd-check");
		}
		System.out.println("falsify: " + String.join(" ", command));
		return Util.run(command, target, line -> {
			output.append(line).append('\n');
			if (!line.startsWith("[INFO]")) {
				System.out.println(line);
			}
		});
	}

	/**
	 * The first top-level class file under the first src/main/java, in path order, in a module
	 * the gate runs on: a module whose pom sets pmd.skip or cpd.skip (the owner left it out) is
	 * passed over, because planting there proves nothing.
	 */
	static Path firstClassFile(Path target, String gate) throws IOException {
		List<Path> eligible = eligibleModules(target, gate);
		try (Stream<Path> walk = Files.walk(target)) {
			return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
					.filter(p -> p.toString().contains("/src/main/java/") && !p.toString().contains("/target/"))
					.filter(p -> eligible.contains(moduleOf(target, p)))
					.filter(p -> !gateSkipped(target, moduleOf(target, p)))
					.filter(p -> !p.getFileName().toString().equals("package-info.java")
							&& !p.getFileName().toString().equals("module-info.java"))
					.filter(p -> {
						try {
							return Files.readString(p).contains("\nclass ") || Files.readString(p).contains(" class ");
						}
						catch (IOException e) {
							return false;
						}
					})
					.sorted().findFirst()
					.orElseThrow(() -> new IllegalStateException("no class under src/main/java below " + target));
		}
	}

	static List<Path> eligibleModules(Path target, String gate) throws IOException {
		List<String> excluded = Util.Decisions.read(target).excludedModules();
		return Util.modules(target, Util.mavenProfile(gate)).stream()
				.filter(m -> excluded.stream().noneMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/")))
				.map(Util.Module::dir).toList();
	}

	/** Whether the module, or a parent pom between it and the target, skips the PMD or CPD goals. */
	static boolean gateSkipped(Path target, Path module) {
		for (Path dir = module; dir != null && dir.startsWith(target); dir = dir.getParent()) {
			Path pom = dir.resolve("pom.xml");
			try {
				if (Files.isRegularFile(pom)) {
					String xml = Files.readString(pom);
					if (xml.contains("<pmd.skip>true</pmd.skip>") || xml.contains("<cpd.skip>true</cpd.skip>")) {
						return true;
					}
				}
			}
			catch (IOException e) {
				return false;
			}
		}
		return false;
	}

	/** The nearest ancestor of the file that holds a pom.xml. */
	static Path moduleOf(Path target, Path file) {
		for (Path dir = file.getParent(); dir != null && dir.startsWith(target); dir = dir.getParent()) {
			if (Files.exists(dir.resolve("pom.xml"))) {
				return dir;
			}
		}
		return target;
	}
}
