///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS net.sourceforge.pmd:pmd-java:7.28.0
//DEPS org.slf4j:slf4j-simple:1.7.36
//SOURCES Util.java
//SOURCES PmdMeasure.java
//SOURCES NullAwaySupport.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Inventory a target project before any gate is installed, and say which gate to install next.
 *
 * <p>For each of the kit's gates it answers three questions from cheap facts: is the question
 * already answered here (the gate is absent, reporting, or gating), will the gate find something
 * (concurrency for SpotBugs, tests per module for coverage, the measured PMD and CPD cost), and
 * what does going green cost. The order is a fixed base sequence moved by modifiers, each
 * printed with its reason; there are no weights. The PMD and CPD measurement runs inside this
 * script under a time limit ({@code --quick} skips it). Reads files and the git history and runs
 * PMD in-process; runs no build. The rules are in {@code playbooks/00-assess.md}.
 *
 * <p>Usage: {@code ./jbang assess [target] [--quick] [--pmd-timeout SECONDS]}
 */
public class Assess {

	/** Quality tooling the kit knows about, and the text that reveals it in a pom. */
	static final Map<String, String> TOOLING = new LinkedHashMap<>();
	static {
		TOOLING.put("maven-pmd-plugin", "PMD (the kit's gate)");
		TOOLING.put("spotbugs-maven-plugin", "SpotBugs (the kit's gate)");
		TOOLING.put("error_prone_core", "Error Prone (the kit's gate)");
		TOOLING.put("nullaway", "NullAway (package-by-package; uses Error Prone integration)");
		TOOLING.put("jacoco-maven-plugin", "JaCoCo (the kit's gate)");
		TOOLING.put("archunit", "ArchUnit (the kit's gate)");
		TOOLING.put("pitest-maven", "PIT (advanced; a scoped profile, not a gate)");
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
			new Gate("errorprone", "Error Prone defaults at ERROR", "playbooks/11-errorprone.md", "ErrorProneMeasure.java", "Is executed code wrong in a known shape?", "a compile under JDK 21", "every change"),
			new Gate("spotbugs", "SpotBugs rank 9 plus concurrency", "playbooks/12-spotbugs.md", "SpotBugsMeasure.java", "Is executed code wrong in a known shape?", "minutes", "every change"),
			new Gate("pmd", "PMD size, complexity, duplication", "playbooks/10-pmd.md", "PmdMeasure.java", "What must be refactored before the others can see it?", "minutes; the refactoring is the cost", "a refactoring target, then a gate"),
			new Gate("archunit", "ArchUnit no-cycles", "playbooks/20-archunit.md", "ArchUnitMeasure.java", "Is the structure drifting?", "seconds after compile", "every change"));

	/** Whether the question a gate answers is already being answered in the build. */
	enum State {
		ABSENT("absent"), REPORTING("reporting"), GATING("gating");

		final String label;

		State(String label) {
			this.label = label;
		}
	}

	/** PMD whose measured cost is at most this many items is cheap enough to go ahead of the bug finders. A kit constant, to calibrate against evidence. */
	static final int PMD_CHEAP = 25;
	static final int DEFAULT_PMD_TIMEOUT_SECONDS = 180;

	/** What a main-source file says when it carries concurrency of its own. */
	static final Pattern CONCURRENCY = Pattern.compile(
			"\\b(synchronized|volatile|ReentrantLock|ReadWriteLock|CountDownLatch|Semaphore|CyclicBarrier|Phaser|new Thread\\b|ExecutorService|ScheduledExecutorService|CompletableFuture|AtomicReference|AtomicInteger|AtomicLong|AtomicBoolean|ConcurrentHashMap|BlockingQueue)\\b");

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		boolean quick = List.of(args).contains("--quick");
		int timeout = PmdMeasure.intOption(args, "--pmd-timeout", DEFAULT_PMD_TIMEOUT_SECONDS);
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
		System.out.println("| module | main .java | test .java | tests per 10 main | looks like test support |");
		System.out.println("|---|---:|---:|---:|---|");
		int mainTotal = 0;
		int testTotal = 0;
		Map<String, Integer> concurrencyPerModule = new TreeMap<>();
		List<String> untested = new ArrayList<>();
		int concurrencyFiles = 0;
		for (Util.Module m : modules) {
			List<Path> mainFiles = Util.javaFiles(m.sourceRoot());
			int testCount = Util.javaFiles(m.dir().resolve("src").resolve("test").resolve("java")).size();
			boolean support = !m.testSupportReasons().isEmpty();
			if (!support) {
				mainTotal += mainFiles.size();
				testTotal += testCount;
				if (testCount == 0 && !mainFiles.isEmpty()) {
					untested.add(m.path());
				}
			}
			int concurrent = 0;
			for (Path f : mainFiles) {
				if (CONCURRENCY.matcher(Files.readString(f)).find()) {
					concurrent++;
				}
			}
			if (concurrent > 0 && !support) {
				concurrencyPerModule.put(m.path(), concurrent);
				concurrencyFiles += concurrent;
			}
			System.out.println("| " + m.path() + " | " + mainFiles.size() + " | " + testCount + " | "
					+ (mainFiles.isEmpty() ? "" : String.format(Locale.ROOT, "%.1f", 10.0 * testCount / mainFiles.size())) + " | "
					+ (support ? String.join("; ", m.testSupportReasons()) : "") + " |");
		}
		System.out.println();
		System.out.println(modules.size() + " modules with main sources; outside test support: " + mainTotal + " main-source files, " + testTotal + " test-source files.");
		System.out.println("Root packages: " + rootPackages(modules));
		System.out.println();

		// Tooling already present
		System.out.println("## Quality tooling already in the build");
		System.out.println();
		Map<String, List<String>> found = new LinkedHashMap<>();
		Map<String, String> pomText = new LinkedHashMap<>();
		if (maven) {
			List<Path> poms = new ArrayList<>();
			poms.add(target.resolve("pom.xml"));
			for (Util.Module m : modules) {
				Path pom = m.dir().resolve("pom.xml");
				if (Files.isRegularFile(pom) && !poms.contains(pom)) {
					poms.add(pom);
				}
			}
			for (Path pom : poms) {
				if (Files.isRegularFile(pom)) {
					String xml = Files.readString(pom);
					pomText.put(target.relativize(pom).toString().replace('\\', '/'), xml);
					for (String key : TOOLING.keySet()) {
						if (xml.contains(key)) {
							found.computeIfAbsent(key, k -> new ArrayList<>()).add(target.relativize(pom).toString().replace('\\', '/'));
						}
					}
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

		// Decisions
		Util.Decisions decisions = Util.Decisions.read(target);
		decisions.print();

		// Question 1: is it already answered here
		boolean pit = found.containsKey("pitest-maven");
		boolean pmdRuleset = Files.exists(target.resolve("config").resolve("pmd").resolve("ruleset.xml"));
		Map<String, State> state = new LinkedHashMap<>();
		state.put("jacoco", pluginState(pomText, "jacoco-maven-plugin", "<goal>check</goal>", null));
		state.put("errorprone", errorProneState(pomText));
		state.put("spotbugs", pluginState(pomText, "spotbugs-maven-plugin", "<goal>check</goal>", "<failOnError>false"));
		state.put("pmd", pluginState(pomText, "maven-pmd-plugin", "<goal>check</goal>|<goal>cpd-check</goal>", "<failOnViolation>false"));
		state.put("archunit", archUnitState(modules, found.containsKey("archunit")));

		// Question 2: will it find something
		List<String> churn = churn(target);
		Util.Decisions d = decisions;
		List<Util.Module> measured = modules.stream()
				.filter(m -> m.testSupportReasons().isEmpty() && d.excludedModules().stream().noneMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/")))
				.collect(Collectors.toList());
		PmdMeasure.Summary pmdCost = null;
		String pmdNote = null;
		if (quick) {
			pmdNote = "not measured (--quick)";
		}
		else if (measured.isEmpty()) {
			pmdNote = "no module outside test support to measure";
		}
		else {
			System.out.println("Measuring PMD and CPD over " + measured.size() + " module(s), limit " + timeout + " s...");
			System.out.flush();
			pmdCost = runPmd(target, measured, String.valueOf(java), decisions, timeout);
			if (pmdCost == null) {
				pmdNote = "not measured: still running after " + timeout + " s; run `measure`, which has no limit";
			}
			System.out.println();
		}

		System.out.println("## Gate analysis");
		System.out.println();
		System.out.println("| gate | answered here? | will it find something | cost to go green |");
		System.out.println("|---|---|---|---|");
		Map<String, String> finds = new LinkedHashMap<>();
		Map<String, String> cost = new LinkedHashMap<>();
		finds.put("jacoco", testTotal == 0 ? "**no test sources**: nothing to measure until tests exist"
				: untested.isEmpty() ? "every module outside test support has tests; the floors protect what is there"
						: untested.size() + " module(s) with main code and no tests: " + String.join(", ", untested));
		cost.put("jacoco", "floors at measured minus 2: no refactoring" + (untested.isEmpty() ? "" : "; tests to write in the untested modules"));
		finds.put("errorprone", "not predictable from source; on the two proven code bases about 3 real bugs per 130 files, from checks no grep names");
		cost.put("errorprone", "a triage pass; a few fixes; JDK 21 profile" + (java < 21 ? " (the build is on " + java + ")" : ""));
		finds.put("spotbugs", concurrencyFiles == 0 ? "no concurrency primitives in main sources; the rank 9 findings are fewer and rarely locks"
				: concurrencyFiles + " main-source files use concurrency (" + concurrencyPerModule.entrySet().stream().limit(3).map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))
						+ "); on three code bases every gate finding was a lock or a race");
		cost.put("spotbugs", "a handful at the gate (4 to 6 on the code bases measured); fixed by hand");
		if (pmdCost != null) {
			finds.put("pmd", pmdCost.pmdViolations() + " violations and " + pmdCost.cpdBlocks() + " duplicate blocks at the reference thresholds, measured in "
					+ (pmdCost.millis() / 1000) + " s" + (pmdCost.unfit().isEmpty() ? "" : "; references that do not fit: " + String.join("; ", pmdCost.unfit())));
			cost.put("pmd", pmdCost.pmdViolations() + pmdCost.cpdBlocks() <= PmdMeasure.FIX_ALL_LIMIT && pmdCost.unfit().isEmpty() ? "fix-all, "
					+ (pmdCost.pmdViolations() + pmdCost.cpdBlocks()) + " items" : "ratchet, " + (pmdCost.pmdViolations() + pmdCost.cpdBlocks()) + " items"
							+ (pmdCost.unfit().isEmpty() ? "" : " and threshold-fit decisions"));
		}
		else {
			finds.put("pmd", pmdNote);
			cost.put("pmd", mainTotal > 300 ? "expect a ratchet at " + mainTotal + " main-source files" : "known after `measure`; fix-all under " + PmdMeasure.FIX_ALL_LIMIT + " items");
		}
		finds.put("archunit", "needs compiled classes; `measure-archunit` lists the cycles; "
				+ modules.size() + " modules, and cycles through public API cost a session each");
		cost.put("archunit", "the cycle-free modules gate now; the rest are measured first");
		for (Gate g : GATES) {
			System.out.println("| " + g.name() + " (" + g.question() + ") | " + state.get(g.key()).label + " | " + finds.get(g.key()) + " | " + cost.get(g.key()) + " |");
		}
		System.out.println("| PIT, scoped (advanced: does the executing test check anything?) | " + (pit ? "profile present" : "absent") + " | not a gate; reads survivors in classes that changed | tens of minutes whole-module, about two scoped |");
		System.out.println("| NullAway (can null cross a non-null contract?) | " + (found.containsKey("nullaway") ? "processor configured; scope needs proof" : "absent")
				+ " | " + NullAwaySupport.summary(NullAwaySupport.packages(target, "")) + " | package-by-package adoption; `measure-nullaway --inventory`, then playbook 30 |");
		System.out.println();
		System.out.println("- Churn (main-source files touched most in the last 50 commits): " + (churn.isEmpty() ? "no git history read" : String.join(", ", churn)));
		System.out.println();

		// Question 3 and the order
		List<String> notes = new ArrayList<>();
		List<String> order = order(testTotal, concurrencyFiles, pmdCost, state, notes);
		List<String> recorded = decisions.gateOrder();
		List<String> unknown = recorded.stream().filter(k -> GATES.stream().noneMatch(g -> g.key().equals(k))).collect(Collectors.toList());
		if (!unknown.isEmpty()) {
			System.out.println("GATE_ORDER names gates the kit does not know: " + String.join(", ", unknown) + " (known: " + GATES.stream().map(Gate::key).collect(Collectors.joining(", ")) + "); they are ignored.");
			System.out.println();
		}
		List<String> effective = recorded.isEmpty() ? order : recorded.stream().filter(k -> !unknown.contains(k)).collect(Collectors.toList());

		System.out.println("## Gate order");
		System.out.println();
		System.out.println("Base sequence: coverage, then the bug finders (Error Prone, SpotBugs), then PMD, then ArchUnit. A gate already gating stays in the record and is skipped as the next gate. Modifiers applied:");
		System.out.println();
		if (notes.isEmpty()) {
			System.out.println("- none");
		}
		notes.forEach(n -> System.out.println("- " + n));
		System.out.println();
		System.out.println(recorded.isEmpty() ? "Recommended order:" : "Recorded in `" + target.relativize(decisions.file) + "` (the analysis would recommend: " + String.join(", ", order) + "):");
		System.out.println();
		int i = 1;
		for (String key : effective) {
			Gate g = gate(key);
			System.out.println(i++ + ". **" + g.name() + "** (`" + key + "`, " + g.playbook() + "): " + state.get(key).label + "; " + cost.get(key) + ".");
		}
		List<String> todo = effective.stream().filter(k -> state.get(k) != State.GATING).collect(Collectors.toList());
		System.out.println();
		System.out.println("Order: " + String.join(", ", todo));
		System.out.println();

		System.out.println("## Stops");
		System.out.println();
		if (Util.testSupportStop(modules, decisions)) {
			System.out.println("Answer TEST_SUPPORT_MODULES and rerun assess before installing the first gate.");
			System.exit(0); // a timed-out PMD worker may still be running
		}
		if (recorded.isEmpty()) {
			System.out.println("Record the line below in `" + target.relativize(decisions.file) + "` (create the file with a `# java-guardrails decisions` heading if absent, the date in parentheses), show the block to the owner, and continue: a notification, not a decision.");
			System.out.println();
			System.out.println("```");
			System.out.println(Util.stopHeader("GATE_ORDER", false));
			System.out.print(Util.kindLine(false));
			System.out.println("  The order the analysis recommends, with the modifiers printed above. Every later run names the next gate from it; reorder by editing the line.");
			System.out.println("  Record as:  GATE_ORDER: " + String.join(", ", order) + (pit ? "; pit scoped-only" : ""));
			System.out.println("```");
		}
		else {
			System.out.println("None: the gate order is recorded; each gate's own measure asks what it needs.");
		}
		System.out.println();

		// Next step
		System.out.println("## Next");
		System.out.println();
		String next = todo.isEmpty() ? null : todo.get(0);
		if (!maven) {
			System.out.println("Nothing the kit can install here yet. `measure` still works for the numbers.");
		}
		else if (next == null) {
			System.out.println("Every gate in the order is gating. `./mvnw -P errorprone verify` is the target every change is held to.");
		}
		else if (next.equals("pmd") && pmdRuleset && state.get("pmd") != State.ABSENT && !decisions.has("PMD_MECHANISM")) {
			System.out.println("The PMD gate is installed but the mechanism is undecided: run `measure` and answer its STOP blocks.");
		}
		else {
			Gate g = gate(next);
			System.out.println("The " + g.name() + " gate (`" + g.playbook() + "`), " + state.get(next).label + " here. Step 1: `<kit>/jbang <kit>/scripts/" + g.script() + " " + target + "`.");
			System.out.println("It ends with STOP blocks for what it needs: record a notify block and continue; relay a decide block and wait.");
		}
		if (pit) {
			System.out.println();
			System.out.println(scopedPit(target, modules));
		}
		System.out.println();
		System.out.println("Optional NullAway adoption: `playbooks/30-nullaway.md`. It needs Error Prone's compiler integration, "
				+ "not a completed cleanup of every Error Prone finding. Choose the package scope explicitly; it is outside the default GATE_ORDER.");
		System.exit(0); // a timed-out PMD run may still be on a daemon thread
	}

	/** PMD and CPD on a worker thread; null when it did not finish in time or could not run. */
	static PmdMeasure.Summary runPmd(Path target, List<Util.Module> measured, String java, Util.Decisions decisions, int timeoutSeconds) throws InterruptedException {
		ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "pmd-measure");
			t.setDaemon(true);
			return t;
		});
		Future<PmdMeasure.Summary> future = pool.submit(() -> PmdMeasure.summarise(target, measured, java, decisions));
		try {
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		}
		catch (TimeoutException e) {
			return null;
		}
		catch (ExecutionException e) {
			System.out.println("PMD measurement failed: " + e.getCause());
			return null;
		}
		finally {
			pool.shutdownNow();
		}
	}

	static Gate gate(String key) {
		return GATES.stream().filter(g -> g.key().equals(key)).findFirst().orElseThrow();
	}

	/**
	 * The order: a fixed base sequence moved by modifiers, each printed with its reason. Base:
	 * coverage, Error Prone, SpotBugs, PMD, ArchUnit. A gate already gating stays in the record and is skipped as the next gate.
	 * Modifiers: no tests sends coverage last (nothing to measure); concurrency in main sources
	 * puts SpotBugs ahead of Error Prone; a measured PMD cost at most {@value #PMD_CHEAP} items
	 * that fits every reference puts PMD ahead of the bug finders, behind coverage.
	 */
	static List<String> order(int testFiles, int concurrencyFiles, PmdMeasure.Summary pmd, Map<String, State> state, List<String> notes) {
		List<String> order = new ArrayList<>(List.of("jacoco", "errorprone", "spotbugs", "pmd", "archunit"));
		List<String> open = order.stream().filter(k -> state.get(k) != State.GATING).collect(Collectors.toList());
		if (open.contains("jacoco") && testFiles == 0) {
			order.remove("jacoco");
			order.add("jacoco");
			notes.add("no test sources outside test support: coverage goes last, there is nothing to measure until tests exist.");
		}
		if (open.contains("spotbugs") && concurrencyFiles > 0) {
			order.remove("spotbugs");
			order.add(order.indexOf("errorprone"), "spotbugs");
			notes.add(concurrencyFiles + " main-source files use concurrency: SpotBugs goes ahead of Error Prone.");
		}
		if (open.contains("pmd") && pmd != null && pmd.pmdViolations() + pmd.cpdBlocks() <= PMD_CHEAP && pmd.unfit().isEmpty()) {
			order.remove("pmd");
			order.add(order.indexOf("jacoco") == 0 ? 1 : 0, "pmd");
			notes.add("PMD measured at " + (pmd.pmdViolations() + pmd.cpdBlocks()) + " items (at most " + PMD_CHEAP + ") and every reference fits: cheap enough to go ahead of the bug finders.");
		}
		for (Map.Entry<String, State> e : state.entrySet()) {
			if (e.getValue() == State.GATING) {
				notes.add(gate(e.getKey()).name() + " is already gating here: kept in the recorded order, skipped as the next gate.");
			}
			else if (e.getValue() == State.REPORTING) {
				notes.add(gate(e.getKey()).name() + " is in the build but only reporting: it stays in the order, to be turned into a gate.");
			}
		}
		return order;
	}

	/** The state of a Maven plugin: absent; reporting when present without a gating goal or with failure switched off; gating otherwise. */
	static State pluginState(Map<String, String> poms, String artifact, String gatingGoals, String failureOff) {
		boolean present = false;
		boolean gating = false;
		for (String xml : poms.values()) {
			int from = 0;
			while ((from = xml.indexOf(artifact, from)) >= 0) {
				present = true;
				int start = xml.lastIndexOf("<plugin>", from);
				int end = xml.indexOf("</plugin>", from);
				if (start >= 0 && end > start) {
					String block = xml.substring(start, end);
					boolean goal = java.util.Arrays.stream(gatingGoals.split("\\|")).anyMatch(block::contains);
					if (goal && (failureOff == null || !block.contains(failureOff))) {
						gating = true;
					}
				}
				from += artifact.length();
			}
		}
		return !present ? State.ABSENT : gating ? State.GATING : State.REPORTING;
	}

	/** Error Prone rides on the compiler plugin: gating when warnings fail the build ({@code -Werror} or {@code failOnWarning} not false). */
	static State errorProneState(Map<String, String> poms) {
		boolean present = false;
		boolean gating = false;
		for (String xml : poms.values()) {
			if (xml.contains("error_prone_core")) {
				present = true;
				if (xml.contains("-Werror") || (xml.contains("<failOnWarning>") && !xml.contains("<failOnWarning>false"))) {
					gating = true;
				}
			}
		}
		return !present ? State.ABSENT : gating ? State.GATING : State.REPORTING;
	}

	/** ArchUnit: the library on a pom is reporting; a test that states a rule makes it gating. */
	static State archUnitState(List<Util.Module> modules, boolean library) throws IOException {
		if (!library) {
			return State.ABSENT;
		}
		for (Util.Module m : modules) {
			for (Path test : Util.javaFiles(m.dir().resolve("src").resolve("test").resolve("java"))) {
				String text = Files.readString(test);
				if (text.contains("com.tngtech.archunit") && (text.contains("slices()") || text.contains("@ArchTest") || text.contains("ArchRule") || text.contains("noClasses()"))) {
					return State.GATING;
				}
			}
		}
		return State.REPORTING;
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
