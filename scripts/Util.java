import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Shared by every script through {@code //SOURCES Util.java}. */
final class Util {
	private Util() {
	}

	/** The target project's Maven wrapper command for this operating system. */
	static String mvnw(Path target) {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		return target.resolve(windows ? "mvnw.cmd" : "mvnw").toString();
	}

	/** The kit's compiler checkers share one opt-in profile; other gates must not activate them. */
	static String mavenProfile(String gate) {
		return gate.equals("errorprone") || gate.equals("nullaway") ? "errorprone" : "!errorprone";
	}

	/**
	 * Shared build policy for measurements and proofs. Build selected modules with their working-tree
	 * dependencies, isolate the requested checker from other kit gates, and never override that
	 * checker's own skip/disable setting: a disabled gate must fail its negative control.
	 */
	static List<String> maven(Path target, String gate, String selected) {
		if (!List.of("pmd", "cpd", "spotbugs", "jacoco", "archunit", "errorprone", "nullaway").contains(gate)) {
			throw new IllegalArgumentException("Unknown Maven gate: " + gate);
		}
		List<String> command = new ArrayList<>(List.of(mvnw(target), "-B", "-Dstyle.color=never",
				"-Dspring-javaformat.skip=true", "-P", mavenProfile(gate)));
		for (String other : List.of("pmd", "cpd", "spotbugs", "jacoco")) {
			if (!other.equals(gate)) command.add("-D" + other + ".skip=true");
		}
		if (!selected.isEmpty()) command.addAll(List.of("-pl", selected, "-am"));
		return command;
	}

	/** The target project directory: the first argument, else the working directory. */
	static Path target(String[] args) {
		return (args.length > 0 && !args[0].startsWith("--")) ? Path.of(args[0]).toAbsolutePath().normalize()
				: Path.of("").toAbsolutePath();
	}

	/**
	 * The Java language level the target compiles for, read from the root pom.xml
	 * ({@code java.version}, {@code maven.compiler.release} or {@code maven.compiler.source}), else
	 * from a Gradle build file's {@code languageVersion} or {@code sourceCompatibility}; 17 if none.
	 */
	static String javaVersion(Path target) {
		String[] files = { "pom.xml", "build.gradle.kts", "build.gradle" };
		Pattern[] patterns = {
				Pattern.compile("<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source|release)>\\s*(?:1\\.)?(\\d+)\\s*<"),
				Pattern.compile("(?:languageVersion\\s*(?:=|\\.set\\()\\s*JavaLanguageVersion\\.of\\(|sourceCompatibility\\s*=?\\s*(?:JavaVersion\\.VERSION_)?['\"]?(?:1[._])?)(\\d+)") };
		for (int f = 0; f < files.length; f++) {
			Path file = target.resolve(files[f]);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			try {
				Matcher m = patterns[f == 0 ? 0 : 1].matcher(Files.readString(file));
				if (m.find()) {
					return m.group(1);
				}
			}
			catch (IOException e) {
				// fall through to the default
			}
		}
		return "17";
	}

	// ---- Modules ----------------------------------------------------------------------------------

	/** A module below the target: its path relative to the target, its main source root, and why it looks like test support, if it does. */
	record Module(String path, Path dir, Path sourceRoot, List<String> testSupportReasons) {
	}

	/** Test libraries whose presence without {@code <scope>test</scope>} marks a module as test support. */
	static final Pattern TEST_LIBRARY = Pattern.compile(
			"<artifactId>(junit[a-z0-9-]*|assertj[a-z0-9-]*|mockito[a-z0-9-]*|testcontainers[a-z0-9-]*|hamcrest[a-z0-9-]*|testng|spring-boot-starter-test|spring-test)</artifactId>");
	static final Pattern TEST_MODULE_NAME = Pattern.compile("(^|[-_/])(test|tests|testing|test-support|testkit|tck|conformance)([-_/]|$)");

	/**
	 * Every declared Maven module (filesystem source inventory for other build tools), with the reasons it looks like test
	 * support: its name, test libraries on its compile classpath, or most of its classes named
	 * like tests. Build output and vendored trees are skipped.
	 */
	static List<Module> modules(Path target) throws IOException {
		return modules(target, "");
	}

	static List<Module> modules(Path target, String profile) throws IOException {
		target = target.toAbsolutePath().normalize();
		if (Files.isRegularFile(target.resolve("pom.xml"))) {
			List<Module> result = new ArrayList<>();
			try { mavenModules(target, target, new HashSet<>(), result); }
			catch (EffectiveModelNeeded e) { result = effectiveModules(target, profile); }
			return result.stream().sorted(java.util.Comparator.comparing(Module::path)).toList();
		}
		final Path base = target;
		List<Path> roots;
		try (Stream<Path> walk = Files.walk(target)) {
			roots = walk.filter(Files::isDirectory)
					.filter(p -> p.endsWith(Path.of("src", "main", "java")))
					.filter(p -> !isBuildOutput(base.relativize(p)))
					.sorted().collect(Collectors.toList());
		}
		List<Module> modules = new ArrayList<>();
		for (Path root : roots) {
			Path moduleDir = root.getParent().getParent().getParent();
			String path = target.equals(moduleDir) ? "." : target.relativize(moduleDir).toString().replace('\\', '/');
			modules.add(new Module(path, moduleDir, root, testSupportReasons(path, moduleDir, root)));
		}
		return modules;
	}

	/** Follow aggregator edges, never arbitrary nested POMs. Profiles need Maven's effective model. */
	private static void mavenModules(Path target, Path dir, Set<Path> seen, List<Module> modules) throws IOException {
		if (!seen.add(dir.toRealPath())) return;
		Element project = pom(dir.resolve("pom.xml"));
		Element profiles = child(project, "profiles");
		if (profiles != null && profiles.getElementsByTagName("modules").getLength() > 0) {
			throw new EffectiveModelNeeded();
		}
		Path source = dir.resolve("src/main/java");
		if (Files.isDirectory(source)) {
			String path = target.equals(dir) ? "." : target.relativize(dir).toString().replace('\\', '/');
			modules.add(new Module(path, dir, source, testSupportReasons(path, dir, source)));
		}
		Element declared = child(project, "modules");
		if (declared == null) return;
		for (Node n = declared.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (!(n instanceof Element module) || !module.getTagName().equals("module")) continue;
			String name = module.getTextContent().trim();
			if (name.contains("${")) throw new EffectiveModelNeeded();
			Path next = dir.resolve(name).normalize();
			if (Files.isRegularFile(next)) next = next.getParent();
			if (!next.startsWith(target)) throw new IOException("Maven module is outside the target: " + next);
			mavenModules(target, next, seen, modules);
		}
	}

	private static final class EffectiveModelNeeded extends IOException {}

	/** Maven resolves profile activation and interpolation; ordinary reactors retain the cheap inventory. */
	private static List<Module> effectiveModules(Path target, String profile) throws IOException {
		Path effective = Files.createTempFile("guardrails-effective-pom-", ".xml");
		try {
			List<String> command = new ArrayList<>(List.of(mvnw(target), "-B", "-q"));
			if (!profile.isEmpty()) command.addAll(List.of("-P", profile));
			command.addAll(List.of("org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom", "-Doutput=" + effective));
			StringBuilder output = new StringBuilder();
			int exit = run(command, target, line -> output.append(line).append('\n'));
			if (exit != 0) throw new IOException("Maven reactor discovery failed (exit " + exit + "):\n" + output);
			Element model = pom(effective);
			List<Element> projects = new ArrayList<>();
			if (model.getTagName().equals("project")) projects.add(model);
			else for (Node n = model.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n instanceof Element e && e.getTagName().equals("project")) projects.add(e);
			}
			List<Module> result = new ArrayList<>();
			for (Element project : projects) {
				Element build = child(project, "build");
				Element sourceDirectory = build == null ? null : child(build, "sourceDirectory");
				if (sourceDirectory == null) continue;
				Path source = Path.of(sourceDirectory.getTextContent().trim()).normalize();
				if (!Files.isDirectory(source)) continue;
				if (!source.startsWith(target) || !source.endsWith(Path.of("src", "main", "java"))) {
					throw new IOException("Reactor inventory requires src/main/java inside the target: " + source);
				}
				Path dir = source.getParent().getParent().getParent();
				String path = target.equals(dir) ? "." : target.relativize(dir).toString().replace('\\', '/');
				result.add(new Module(path, dir, source, testSupportReasons(path, dir, source)));
			}
			return result;
		}
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Maven reactor discovery interrupted", e); }
		finally { Files.deleteIfExists(effective); }
	}

	private static Element pom(Path file) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
		}
		catch (Exception e) { throw new IOException("Cannot read Maven project " + file + ": " + e.getMessage(), e); }
	}

	private static Element child(Element parent, String name) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(name)) return e;
		}
		return null;
	}

	/** Whether a relative path passes through build output or a vendored tree, on any operating system. */
	static boolean isBuildOutput(Path relative) {
		for (Path part : relative) {
			String name = part.toString();
			if (name.equals("target") || name.equals("build") || name.equals(".git") || name.equals("node_modules")) {
				return true;
			}
		}
		return false;
	}

	static List<String> testSupportReasons(String path, Path moduleDir, Path root) throws IOException {
		List<String> reasons = new ArrayList<>();
		if (TEST_MODULE_NAME.matcher(path).find()) {
			reasons.add("named like a test module");
		}
		Path pom = moduleDir.resolve("pom.xml");
		if (Files.isRegularFile(pom)) {
			String xml = Files.readString(pom);
			List<String> compileScoped = new ArrayList<>();
			for (String dep : xml.split("<dependency>")) {
				Matcher m = TEST_LIBRARY.matcher(dep);
				if (m.find() && !dep.contains("<scope>test</scope>")) {
					compileScoped.add(m.group(1));
				}
			}
			if (!compileScoped.isEmpty()) {
				reasons.add("test libraries on the compile classpath: " + String.join(", ", compileScoped));
			}
		}
		List<Path> classes = javaFiles(root);
		long testNamed = classes.stream().filter(p -> p.getFileName().toString().matches(".*(Test|Tests|IT)\\.java")).count();
		if (!classes.isEmpty() && testNamed * 2 >= classes.size()) {
			reasons.add(testNamed + " of " + classes.size() + " classes named like tests");
		}
		return reasons;
	}

	/** The .java files below a directory, package-info excluded. */
	static List<Path> javaFiles(Path root) throws IOException {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java"))
					.collect(Collectors.toList());
		}
	}

	// ---- Decisions file ---------------------------------------------------------------------------

	/** {@code config/guardrails/decisions.md} in the target: one {@code KEY: value} line per decision. */
	static final class Decisions {
		private static final Pattern LINE = Pattern.compile("^([A-Z][A-Z0-9_]+):\\s*(.*?)\\s*$");
		private final Map<String, String> values = new LinkedHashMap<>();
		final Path file;

		private Decisions(Path file) {
			this.file = file;
		}

		static Path path(Path target) {
			return target.resolve("config").resolve("guardrails").resolve("decisions.md");
		}

		static Decisions read(Path target) throws IOException {
			Decisions d = new Decisions(path(target));
			if (Files.isRegularFile(d.file)) {
				for (String line : Files.readAllLines(d.file)) {
					Matcher m = LINE.matcher(line);
					if (m.matches()) {
						// a trailing "(2026-10-08)" is the date the decision was taken, not part of the value
						d.values.put(m.group(1), m.group(2).replaceFirst("\\s*\\([^)]*\\)\\s*$", ""));
					}
				}
			}
			return d;
		}

		boolean has(String key) {
			return values.containsKey(key);
		}

		String get(String key) {
			return values.getOrDefault(key, "");
		}

		Map<String, String> all() {
			return values;
		}

		/** {@code GATE_ORDER: jacoco, spotbugs, errorprone, pmd, archunit; pit scoped-only}: the gate keys before the semicolon. */
		List<String> gateOrder() {
			String v = get("GATE_ORDER");
			if (v.isEmpty()) {
				return List.of();
			}
			String gates = v.contains(";") ? v.substring(0, v.indexOf(';')) : v;
			return Stream.of(gates.split(",")).map(String::trim).map(x -> x.toLowerCase(java.util.Locale.ROOT)).filter(x -> !x.isEmpty()).collect(Collectors.toList());
		}

		List<String> excludedModules() {
			List<String> result = new ArrayList<>(commaList(get("EXCLUDED_MODULES")));
			result.addAll(legacyExcludedModules());
			return result.stream().distinct().toList();
		}

		private List<String> legacyExcludedModules() {
			String v = get("TEST_SUPPORT_MODULES");
			if (!v.startsWith("exclude ")) {
				return List.of();
			}
			return commaList(v.substring("exclude".length()));
		}

		private List<String> commaList(String value) {
			return Stream.of(value.split(",")).map(String::trim).filter(x -> !x.isEmpty() && !x.equals("none")).toList();
		}

		/** Legacy exclusions still apply, but excluding an unrelated tool never answers this question. */
		boolean testSupportDecided(List<Module> support) {
			return get("TEST_SUPPORT_MODULES").equals("gate") || support.stream().anyMatch(m ->
					legacyExcludedModules().stream().anyMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/")));
		}

		Set<String> classSizeExempt() {
			return classSize("exempt");
		}

		Set<String> classSizeRefactor() {
			return classSize("refactor");
		}

		/** {@code CLASS_SIZE: exempt McpSchema; refactor McpServer} */
		private Set<String> classSize(String verb) {
			Set<String> names = new HashSet<>();
			for (String part : get("CLASS_SIZE").split(";")) {
				String p = part.trim();
				if (p.startsWith(verb + " ")) {
					for (String n : p.substring(verb.length() + 1).split(",")) {
						names.add(n.trim());
					}
				}
			}
			return names;
		}

		void print() {
			if (values.isEmpty()) {
				System.out.println("Decisions: none recorded yet (" + file + ").");
			}
			else {
				System.out.println("Decisions from `" + file + "`:");
				values.forEach((k, v) -> System.out.println("  " + k + ": " + v));
			}
			System.out.println();
		}
	}

	// ---- Stop blocks ------------------------------------------------------------------------------

	/** Resolve shared scope at assessment, and again before coverage for direct script callers. */
	static boolean testSupportStop(List<Module> modules, Decisions decisions) {
		List<Module> support = modules.stream().filter(m -> !m.testSupportReasons().isEmpty()).toList();
		if (support.isEmpty() || decisions.testSupportDecided(support)) return false;
		List<Module> selected = support.stream().filter(m -> decisions.excludedModules().stream()
				.noneMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/"))).toList();
		if (selected.isEmpty()) return false;
		System.out.println("```");
		System.out.println(stopHeader("TEST_SUPPORT_MODULES", true));
		System.out.print(kindLine(true));
		System.out.println("  These selected modules look like test support in src/main/java:");
		for (Module m : selected) System.out.println("    " + m.path() + ": " + String.join("; ", m.testSupportReasons()));
		System.out.println("  Decide scope before the first gate, including JaCoCo. Gating test support requires its own tests and coverage report; execution only by consumer tests does not supply that report.");
		System.out.println("  Options: (a) exclude these modules from the gates   (b) gate them as published code");
		System.out.println("  Recommendation: (a) for test-only infrastructure; (b) when its published behavior needs the same guarantees.");
		System.out.println("  Record as:  TEST_SUPPORT_MODULES: exclude " + selected.stream().map(Module::path).collect(Collectors.joining(", ")));
		System.out.println("          or  TEST_SUPPORT_MODULES: gate");
		System.out.println("  Preserve any existing excluded paths when editing the decision.");
		System.out.println("```");
		return true;
	}

	/**
	 * The first line of a STOP block: {@code STOP <ID> (notify)} when the recommendation is the
	 * kit's mechanical default and the data is unambiguous, so the agent records the line and
	 * continues; {@code STOP <ID> (decide)} when the options lead to materially different
	 * outcomes, so the agent relays the block and waits for the owner.
	 */
	static String stopHeader(String id, boolean decide) {
		return "STOP " + id + " (" + (decide ? "decide" : "notify") + ")";
	}

	/** The second line of a STOP block, saying what the kind means for the agent reading it. */
	static String kindLine(boolean decide) {
		return decide ? "  Kind: decide. Relay this block as written and do not proceed until the owner answers.\n"
				: "  Kind: notify. The recommendation is the kit's default; record the line, show the block, continue.\n";
	}

	/** The output lines of a git command run in the target, or none when git or the history is unavailable. */
	static List<String> git(Path target, String... args) {
		List<String> command = new ArrayList<>(List.of("git", "-C", target.toString()));
		command.addAll(List.of(args));
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
			List<String> lines;
			try (Stream<String> s = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).lines()) {
				lines = s.collect(Collectors.toList());
			}
			return p.waitFor() == 0 ? lines : List.of();
		}
		catch (IOException | InterruptedException e) {
			return List.of();
		}
	}

	/**
	 * Runs a command in a directory to completion, handing each output line to the consumer. A JVM
	 * shutdown (Ctrl-C, SIGTERM) stops the process and its children too, so no build outlives the
	 * script that started it.
	 */
	static int run(List<String> command, Path directory, java.util.function.Consumer<String> line) throws IOException, InterruptedException {
		Build build = new Build();
		// registered before the start, so a Ctrl-C between the two cannot orphan the build
		Thread stop = new Thread(build::stop, "guardrails-build-stop");
		Runtime.getRuntime().addShutdownHook(stop);
		try {
			Process process = build.start(new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true));
			try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
				String text;
				while ((text = reader.readLine()) != null) {
					line.accept(text);
				}
			}
			return process.waitFor();
		}
		finally {
			build.stop();
			try {
				Runtime.getRuntime().removeShutdownHook(stop);
			}
			catch (IllegalStateException e) {
				// the JVM is shutting down and the hook is running or has run
			}
		}
	}

	/** A build process the shutdown hook can stop whether it runs before, during or after the start. */
	private static final class Build {
		private Process process;
		private boolean stopped;

		synchronized Process start(ProcessBuilder builder) throws IOException {
			if (stopped) {
				throw new IOException("interrupted; the build was not started");
			}
			process = builder.start();
			return process;
		}

		synchronized void stop() {
			stopped = true;
			if (process != null) {
				process.descendants().forEach(ProcessHandle::destroy);
				process.destroy();
			}
		}
	}

	static void notYetImplemented(String alias) {
		System.err.println("./jbang " + alias + ": not implemented yet; see playbooks/ for the procedure it will automate");
		System.exit(2);
	}
}
