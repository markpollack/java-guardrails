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

/** Shared by every script through {@code //SOURCES Util.java}. */
final class Util {
	private Util() {
	}

	/** The target project's Maven wrapper command for this operating system. */
	static String mvnw(Path target) {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		return target.resolve(windows ? "mvnw.cmd" : "mvnw").toString();
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
	 * Every module below the target that has a src/main/java, with the reasons it looks like test
	 * support: its name, test libraries on its compile classpath, or most of its classes named
	 * like tests. Build output and vendored trees are skipped.
	 */
	static List<Module> modules(Path target) throws IOException {
		List<Path> roots;
		try (Stream<Path> walk = Files.walk(target)) {
			roots = walk.filter(Files::isDirectory)
					.filter(p -> p.endsWith(Path.of("src", "main", "java")))
					.filter(p -> !isBuildOutput(target.relativize(p)))
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
			String v = get("TEST_SUPPORT_MODULES");
			if (!v.startsWith("exclude")) {
				return List.of();
			}
			return Stream.of(v.substring("exclude".length()).split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
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
