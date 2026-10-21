///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//SOURCES Util.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Inventory a target project before any gate is installed, and say which gate to install next.
 * Prints the build tool and wrapper, Java level, modules with their source counts and whether
 * they look like test support, root packages, the quality tooling already in the build, which of
 * the kit's gates are on this target, the decisions recorded so far, the cheap signals the code
 * base gives off, the gate order those signals recommend (a {@code GATE_ORDER} notification until
 * it is recorded), and the next step from that order. Reads files and the git history only; runs
 * no build.
 *
 * Usage: {@code ./jbang assess [target]}
 */
public class Assess {

	/** Quality tooling the kit knows about, and the text that reveals it in a pom. */
	static final Map<String, String> TOOLING = new LinkedHashMap<>();
	static {
		TOOLING.put("maven-pmd-plugin", "PMD (the kit's gate)");
		TOOLING.put("spotbugs-maven-plugin", "SpotBugs (the kit's gate)");
		TOOLING.put("error_prone_core", "Error Prone (the kit's gate)");
		TOOLING.put("nullaway", "NullAway (tier 3)");
		TOOLING.put("jacoco-maven-plugin", "JaCoCo (the kit's gate)");
		TOOLING.put("archunit", "ArchUnit (the kit's gate)");
		TOOLING.put("pitest-maven", "PIT (a scoped profile, not a gate)");
		TOOLING.put("lincheck", "Lincheck (tier 3)");
		TOOLING.put("maven-checkstyle-plugin", "Checkstyle (not a kit gate; style)");
		TOOLING.put("spring-javaformat-maven-plugin", "Spring Java Format (not a kit gate; formatting, run it before each commit)");
		TOOLING.put("maven-enforcer-plugin", "Enforcer (not a kit gate)");
		TOOLING.put("sonar-maven-plugin", "Sonar (not a kit gate)");
	}

	/** One of the kit's gates: its key in {@code GATE_ORDER}, the question it answers, what it costs, how often it can run. */
	record Gate(String key, String name, String playbook, String script, String question, String cost, String cadence) {
	}

	static final List<Gate> GATES = List.of(
			new Gate("jacoco", "JaCoCo floors", "playbooks/21-jacoco.md", "JacocoMeasure.java", "Is the code executed by its own tests?", "seconds after the tests", "every commit"),
			new Gate("spotbugs", "SpotBugs rank 9 plus concurrency", "playbooks/12-spotbugs.md", "SpotBugsMeasure.java", "Is executed code wrong in a known shape?", "minutes", "every change"),
			new Gate("errorprone", "Error Prone defaults at ERROR", "playbooks/11-errorprone.md", "ErrorProneMeasure.java", "Is executed code wrong in a known shape?", "a compile under JDK 21", "every change"),
			new Gate("pmd", "PMD size, complexity and duplication", "playbooks/10-pmd.md", "PmdMeasure.java", "What must be refactored before the others can see it?", "minutes; the refactoring is the cost", "a refactoring target, then a gate"),
			new Gate("archunit", "ArchUnit no-cycles", "playbooks/20-archunit.md", "ArchUnitMeasure.java", "Is the structure drifting?", "seconds after compile", "every change"));

	/** What a main-source file says when it carries concurrency of its own. */
	static final Pattern CONCURRENCY = Pattern.compile(
			"\\b(synchronized|volatile|ReentrantLock|ReadWriteLock|CountDownLatch|Semaphore|CyclicBarrier|Phaser|new Thread\\b|ExecutorService|ScheduledExecutorService|CompletableFuture|AtomicReference|AtomicInteger|AtomicLong|AtomicBoolean|ConcurrentHashMap|BlockingQueue)\\b");

	public static void main(String[] args) throws IOException {
		Path target = Util.target(args);
		if (!Files.isDirectory(target)) {
			System.err.println("assess: not a directory: " + target);
			System.exit(2);
		}
		System.out.println("# Assessment: " + target.getFileName());
		System.out.println();

		// Build tool
		boolean maven = Files.exists(target.resolve("pom.xml"));
		boolean gradle = Files.exists(target.resolve("build.gradle")) || Files.exists(target.resolve("build.gradle.kts"));
		boolean mvnw = Files.exists(target.resolve("mvnw"));
		boolean gradlew = Files.exists(target.resolve("gradlew"));
		System.out.println("Target `" + target + "`");
		if (maven) {
			System.out.println("Build: Maven" + (mvnw ? ", wrapper present" : ", **no mvnw**: add the wrapper first (`mvn wrapper:wrapper`); the playbooks run the build through it"));
		}
		else if (gradle) {
			System.out.println("Build: Gradle" + (gradlew ? ", wrapper present" : ", no gradlew") + ". **The kit's Gradle slice is not written**; `measure` works, the gate install does not.");
		}
		else {
			System.out.println("Build: none found (no pom.xml, build.gradle or build.gradle.kts).");
		}
		int java = Integer.parseInt(Util.javaVersion(target));
		System.out.println("Java: " + java + (Files.exists(target.resolve(".mvn").resolve("jvm.config")) ? "; .mvn/jvm.config present" : ""));
		System.out.println();

		// Modules
		List<Util.Module> modules = Util.modules(target);
		if (modules.isEmpty()) {
			System.out.println("No src/main/java below the target. Nothing to gate.");
			return;
		}
		System.out.println("## Modules");
		System.out.println();
		System.out.println("| module | main .java | test .java | looks like test support |");
		System.out.println("|---|---:|---:|---|");
		int mainTotal = 0;
		int testTotal = 0;
		Map<String, Integer> concurrencyPerModule = new TreeMap<>();
		int concurrencyFiles = 0;
		for (Util.Module m : modules) {
			List<Path> mainFiles = Util.javaFiles(m.sourceRoot());
			int testCount = Util.javaFiles(m.dir().resolve("src").resolve("test").resolve("java")).size();
			mainTotal += mainFiles.size();
			testTotal += testCount;
			int concurrent = 0;
			for (Path f : mainFiles) {
				if (CONCURRENCY.matcher(Files.readString(f)).find()) {
					concurrent++;
				}
			}
			if (concurrent > 0 && m.testSupportReasons().isEmpty()) {
				concurrencyPerModule.put(m.path(), concurrent);
				concurrencyFiles += concurrent;
			}
			System.out.println("| " + m.path() + " | " + mainFiles.size() + " | " + testCount + " | "
					+ (m.testSupportReasons().isEmpty() ? "" : String.join("; ", m.testSupportReasons())) + " |");
		}
		System.out.println();
		System.out.println(modules.size() + " modules with main sources, " + mainTotal + " main-source files, " + testTotal + " test-source files.");
		System.out.println("Root packages: " + rootPackages(modules));
		System.out.println();

		// Tooling already present
		System.out.println("## Quality tooling already in the build");
		System.out.println();
		Map<String, List<String>> found = new LinkedHashMap<>();
		List<Path> poms = new ArrayList<>();
		if (maven) {
			poms.add(target.resolve("pom.xml"));
			for (Util.Module m : modules) {
				Path pom = m.dir().resolve("pom.xml");
				if (Files.isRegularFile(pom) && !poms.contains(pom)) {
					poms.add(pom);
				}
			}
		}
		for (Path pom : poms) {
			String xml = Files.readString(pom);
			for (String key : TOOLING.keySet()) {
				if (xml.contains(key)) {
					found.computeIfAbsent(key, k -> new ArrayList<>()).add(target.relativize(pom).toString().replace('\\', '/'));
				}
			}
		}
		if (found.isEmpty()) {
			System.out.println("None of the tools the kit knows about. The build enforces nothing yet beyond compiling and tests.");
		}
		else {
			found.forEach((key, where) -> System.out.println("- " + TOOLING.get(key) + ": `" + key + "` in " + String.join(", ", where)));
		}
		System.out.println();

		// The kit's gates
		System.out.println("## The kit's gates on this target");
		System.out.println();
		boolean pmdRuleset = Files.exists(target.resolve("config").resolve("pmd").resolve("ruleset.xml"));
		boolean pmdPlugin = found.containsKey("maven-pmd-plugin");
		Map<String, Boolean> installed = new LinkedHashMap<>();
		installed.put("pmd", pmdRuleset && pmdPlugin);
		installed.put("errorprone", found.containsKey("error_prone_core"));
		installed.put("spotbugs", found.containsKey("spotbugs-maven-plugin"));
		installed.put("jacoco", found.containsKey("jacoco-maven-plugin"));
		installed.put("archunit", found.containsKey("archunit"));
		String pmd = pmdRuleset && pmdPlugin ? "installed (ruleset and plugin present)"
				: pmdPlugin ? "a PMD plugin is in the build but not the kit's ruleset: measure first, then decide whether to replace its configuration"
						: "not installed";
		System.out.println("| gate | question it answers | cost | cadence | kit status | this target |");
		System.out.println("|---|---|---|---|---|---|");
		for (Gate g : GATES) {
			String here = g.key().equals("pmd") ? pmd : installed.get(g.key()) ? "present in the build" : "not installed";
			String status = g.key().equals("archunit") ? "no-cycles proven on two code bases; layers not written" : "proven on two code bases";
			System.out.println("| " + g.name() + " | " + g.question() + " | " + g.cost() + " | " + g.cadence() + " | " + status + " | " + here + " |");
		}
		System.out.println("| PIT (scoped) | Does the executing test check anything? | tens of minutes whole-module; about two scoped | changed classes during work; whole module at acceptance | playbook from one code base's record | "
				+ (found.containsKey("pitest-maven") ? "profile present" : "not installed") + " |");
		System.out.println();

		// Decisions
		Util.Decisions decisions = Util.Decisions.read(target);
		decisions.print();

		// Signals
		System.out.println("## Signals");
		System.out.println();
		boolean pit = found.containsKey("pitest-maven");
		boolean jacoco = installed.get("jacoco");
		List<String> churn = churn(target);
		System.out.println("- Tests: " + testTotal + " test-source files" + (testTotal == 0 ? " (**none**: coverage has nothing to measure)" : ""));
		System.out.println("- Mutation testing: " + (pit ? "a PIT profile is in the build" + (jacoco ? "" : " and no coverage plugin: mutation runs are scoring code that is never executed") : "none"));
		System.out.println("- Concurrency: " + (concurrencyFiles == 0 ? "no main-source file uses concurrency primitives"
				: concurrencyFiles + " main-source files use concurrency primitives (synchronized, volatile, locks, latches, threads, executors, atomics): "
						+ concurrencyPerModule.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))));
		System.out.println("- Java: " + java + (java < 21 ? "; Error Prone's gate runs under the kit's JDK 21 profile, not the build's own level" : "; Error Prone runs on the build's own JDK"));
		System.out.println("- Size: " + modules.size() + " modules, " + mainTotal + " main-source files"
				+ (mainTotal > 300 ? "; expect PMD's cost above the fix-all limit, so a ratchet" : "; PMD's cost is known after `measure`, fix-all under 100 items"));
		System.out.println("- Gates: " + (installed.values().stream().noneMatch(b -> b) ? "none of the kit's gates is in the build" : installed.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.joining(", ")) + " in the build"));
		System.out.println("- Churn (main-source files touched most in the last 50 commits): " + (churn.isEmpty() ? "no git history read" : String.join(", ", churn)));
		System.out.println();

		// Order
		List<String> order = order(testTotal, concurrencyFiles, java, pit, jacoco);
		Map<String, String> reasons = reasons(testTotal, concurrencyFiles, concurrencyPerModule, java, pit, jacoco, mainTotal, modules.size());
		List<String> recorded = decisions.gateOrder();
		List<String> unknown = recorded.stream().filter(k -> GATES.stream().noneMatch(g -> g.key().equals(k))).collect(Collectors.toList());
		if (!unknown.isEmpty()) {
			System.out.println("GATE_ORDER names gates the kit does not know: " + String.join(", ", unknown) + " (known: " + GATES.stream().map(Gate::key).collect(Collectors.joining(", ")) + "); they are ignored.");
			System.out.println();
		}
		List<String> effective = recorded.isEmpty() ? order : recorded.stream().filter(k -> !unknown.contains(k)).collect(Collectors.toList());

		System.out.println("## Gate order");
		System.out.println();
		if (recorded.isEmpty()) {
			System.out.println("Recommended from the signals above, one reason per gate:");
		}
		else {
			System.out.println("Recorded in `" + target.relativize(decisions.file) + "` (the signals would recommend: " + String.join(", ", order) + "):");
		}
		System.out.println();
		int i = 1;
		for (String key : effective) {
			Gate g = gate(key);
			System.out.println(i++ + ". **" + g.name() + "** (`" + key + "`, " + g.playbook() + "): " + reasons.get(key)
					+ (installed.get(key) ? " **Present in the build.**" : ""));
		}
		System.out.println("- PIT, scoped only: " + reasons.get("pit"));
		System.out.println();

		System.out.println("## Stops");
		System.out.println();
		if (recorded.isEmpty()) {
			System.out.println("Record the line below in `" + target.relativize(decisions.file) + "` (create the file with a `# java-guardrails decisions` heading if absent, the date in parentheses), show the block to the owner, and continue: a notification, not a decision.");
			System.out.println();
			System.out.println("```");
			System.out.println(Util.stopHeader("GATE_ORDER", false));
			System.out.print(Util.kindLine(false));
			System.out.println("  The order the signals recommend, with the reasons printed above. Every later run names the next gate from it; reorder by editing the line.");
			System.out.println("  Record as:  GATE_ORDER: " + String.join(", ", order) + "; pit scoped-only");
			System.out.println("```");
		}
		else {
			System.out.println("None: the gate order is recorded; each gate's own measure asks what it needs.");
		}
		System.out.println();

		// Next step
		System.out.println("## Next");
		System.out.println();
		String next = effective.stream().filter(k -> !installed.get(k)).findFirst().orElse(null);
		if (!maven) {
			System.out.println("Nothing the kit can install here yet. `measure` still works for the numbers.");
		}
		else if (next == null) {
			System.out.println("Every gate in the order is in the build. `./mvnw -P errorprone verify` is the target every change is held to.");
		}
		else if (next.equals("pmd") && pmdRuleset && pmdPlugin && !decisions.has("PMD_MECHANISM")) {
			System.out.println("The PMD gate is installed but the mechanism is undecided: run `measure` and answer its STOP blocks.");
		}
		else {
			Gate g = gate(next);
			System.out.println("The " + g.name() + " gate (`" + g.playbook() + "`). Step 1: `<kit>/jbang <kit>/scripts/" + g.script() + " " + target + "`.");
			System.out.println("It ends with STOP blocks for what it needs: record a notify block and continue; relay a decide block and wait.");
		}
		if (pit) {
			System.out.println();
			System.out.println(scopedPit(target, modules));
		}
	}

	static Gate gate(String key) {
		return GATES.stream().filter(g -> g.key().equals(key)).findFirst().orElseThrow();
	}

	/**
	 * The order the signals recommend. Coverage first when there are tests (a fact in seconds,
	 * and its floors are a notification); SpotBugs next when main sources carry concurrency,
	 * else after Error Prone; PMD after the bug finders, since it is a refactoring target
	 * before it is a gate; ArchUnit last. Without tests, coverage waits until there are some.
	 */
	static List<String> order(int testFiles, int concurrencyFiles, int java, boolean pit, boolean jacoco) {
		List<String> order = new ArrayList<>();
		if (testFiles > 0) {
			order.add("jacoco");
		}
		if (concurrencyFiles > 0) {
			order.add("spotbugs");
			order.add("errorprone");
		}
		else {
			order.add("errorprone");
			order.add("spotbugs");
		}
		order.add("pmd");
		order.add("archunit");
		if (testFiles == 0) {
			order.add("jacoco");
		}
		return order;
	}

	static Map<String, String> reasons(int testFiles, int concurrencyFiles, Map<String, Integer> concurrencyPerModule, int java, boolean pit,
			boolean jacoco, int mainFiles, int moduleCount) {
		Map<String, String> r = new LinkedHashMap<>();
		r.put("jacoco", testFiles == 0 ? "no test sources: nothing to measure until tests exist, so it goes last."
				: "whether code is executed is a fact in seconds, and the floors (measured minus 2) are a notification, not a decision."
						+ (pit && !jacoco ? " A PIT profile is in the build with no coverage plugin: mutation runs have been scoring code that is never executed." : ""));
		r.put("spotbugs", concurrencyFiles > 0
				? concurrencyFiles + " main-source files use concurrency primitives (" + concurrencyPerModule.keySet().stream().limit(3).collect(Collectors.joining(", ")) + "): the concurrency include list is the gate for that shape, and on the MCP Java SDK every finding was a lock or a race."
				: "no concurrency in main sources; still the cheapest bug finder at rank 9, after Error Prone.");
		r.put("errorprone", "every default check at ERROR, a compile away" + (java < 21 ? "; the target compiles for " + java + ", so the kit's JDK 21 profile carries it and CI needs a 21 on the path." : " on the build's own JDK."));
		r.put("pmd", "a refactoring target before it is a gate: " + mainFiles + " main-source files in " + moduleCount + " modules; the cost is known after `measure`"
				+ (mainFiles > 300 ? ", and at this size expect a ratchet." : ", fix-all under 100 items."));
		r.put("archunit", "no-cycles runs in seconds on every change and gates the cycle-free modules now; cycles that run through public API wait for their own session.");
		r.put("pit", pit ? "a profile is in the build: run it on the classes a change touched during work (the command is printed under Next), whole module only at acceptance."
				: "not a gate; a profile to add once coverage floors hold, run on changed classes during work and whole module at acceptance.");
		return r;
	}

	/** Main-source files touched most in the last 50 commits, from git; empty when there is no history to read. */
	static List<String> churn(Path target) {
		List<String> lines = Util.git(target, "log", "--name-only", "--pretty=format:", "-n", "50");
		Map<String, Integer> counts = new TreeMap<>();
		for (String l : lines) {
			if (l.contains("src/main/java/") && l.endsWith(".java")) {
				counts.merge(l, 1, Integer::sum);
			}
		}
		return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(5)
				.map(e -> e.getKey().substring(e.getKey().lastIndexOf('/') + 1).replace(".java", "") + " (" + e.getValue() + ")").collect(Collectors.toList());
	}

	/** The scoped PIT command for the main classes changed in the working tree, per module, or why there is none. */
	static String scopedPit(Path target, List<Util.Module> modules) {
		List<String> changed = Util.git(target, "status", "--porcelain", "--untracked-files=all");
		Map<String, List<String>> perModule = new TreeMap<>();
		for (String line : changed) {
			if (line.length() < 4) {
				continue;
			}
			String path = line.substring(3).trim();
			if (path.contains(" -> ")) {
				path = path.substring(path.indexOf(" -> ") + 4);
			}
			int at = path.indexOf("src/main/java/");
			if (at < 0 || !path.endsWith(".java")) {
				continue;
			}
			String modulePath = at == 0 ? "." : path.substring(0, at - 1);
			String cls = path.substring(at + "src/main/java/".length(), path.length() - ".java".length()).replace('/', '.');
			perModule.computeIfAbsent(modulePath, k -> new ArrayList<>()).add(cls + "*");
		}
		if (perModule.isEmpty()) {
			return "PIT, scoped: no main-source class is changed in the working tree, so there is nothing to target; after a change, `assess` prints the scoped command. Whole module (`./mvnw -Ppit -pl <module> test`) only at acceptance.";
		}
		StringBuilder b = new StringBuilder("PIT, scoped to the main classes changed in the working tree (whole module only at acceptance):\n");
		perModule.forEach((module, classes) -> b.append("  ./mvnw -Ppit -pl ").append(module).append(" test -DtargetClasses=").append(String.join(",", classes)).append("\n"));
		return b.toString().stripTrailing();
	}

	/** The top-level packages under every main source root, by the first path segment below it. */
	static String rootPackages(List<Util.Module> modules) throws IOException {
		TreeSet<String> roots = new TreeSet<>();
		Pattern pkg = Pattern.compile("^package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
		for (Util.Module m : modules) {
			for (Path file : Util.javaFiles(m.sourceRoot())) {
				String head = Files.lines(file).limit(40).collect(Collectors.joining("\n"));
				Matcher mt = pkg.matcher(head);
				if (mt.find()) {
					String[] parts = mt.group(1).split("\\.");
					roots.add(parts.length >= 3 ? parts[0] + "." + parts[1] + "." + parts[2] : mt.group(1));
					break; // one file per module is enough for the root
				}
			}
		}
		return String.join(", ", roots);
	}
}
