//JAVA 17+
//SOURCES Util.java

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared scope inventory and Maven invocation for package-by-package NullAway adoption. */
final class NullAwaySupport {

	private NullAwaySupport() {
	}

	record PackageScope(Util.Module module, String name, Path directory, int files, boolean marked) {
		String key() {
			return module.path() + "=" + name;
		}
	}

	static String option(String[] args, String name) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals(name)) {
				if (i + 1 == args.length || args[i + 1].startsWith("--")) {
					throw new IllegalArgumentException(name + " needs a value");
				}
				return args[i + 1];
			}
		}
		return "";
	}

	/** Package annotations apply to this package only, never implicitly to child packages. */
	static List<PackageScope> packages(Path target, String selectedModule) throws IOException {
		List<PackageScope> result = new ArrayList<>();
		List<String> excluded = Util.Decisions.read(target).excludedModules();
		boolean moduleFound = selectedModule.isEmpty();
		for (Util.Module module : Util.modules(target)) {
			if (!selectedModule.isEmpty() && !module.path().equals(selectedModule)) {
				continue;
			}
			moduleFound = true;
			if (excluded.stream().anyMatch(x -> module.path().equals(x) || module.path().startsWith(x + "/"))) {
				continue;
			}
			TreeMap<String, List<Path>> byPackage = new TreeMap<>();
			for (Path file : Util.javaFiles(module.sourceRoot())) {
				if (file.getFileName().toString().equals("module-info.java")) {
					continue;
				}
				String pkg = module.sourceRoot().relativize(file.getParent()).toString().replace(java.io.File.separatorChar, '.');
				byPackage.computeIfAbsent(pkg, x -> new ArrayList<>()).add(file);
			}
			for (var entry : byPackage.entrySet()) {
				Path directory = entry.getValue().get(0).getParent();
				Path info = directory.resolve("package-info.java");
				result.add(new PackageScope(module, entry.getKey(), directory, entry.getValue().size(),
						Files.isRegularFile(info) && packageMarked(Files.readString(info))));
			}
		}
		if (!moduleFound) {
			throw new IllegalArgumentException("no main-source module named " + selectedModule + " (use the module path from assess)");
		}
		return result;
	}

	static boolean packageMarked(String source) {
		String text = source.replaceAll("(?s)\"(?:\\\\.|[^\"\\\\])*\"|/\\*.*?\\*/|//[^\\r\\n]*", "");
		var declaration = Pattern.compile("\\bpackage\\s+").matcher(text);
		if (!declaration.find()) {
			return false;
		}
		String annotations = text.substring(0, declaration.start());
		return Pattern.compile("@org\\.jspecify\\.annotations\\.NullMarked\\b").matcher(annotations).find()
				|| (Pattern.compile("@NullMarked\\b").matcher(annotations).find()
						&& Pattern.compile("import\\s+org\\.jspecify\\.annotations\\.(NullMarked|\\*)\\s*;").matcher(text).find());
	}

	/** Inventory only: a processor or a marker is not evidence of a live gate. */
	static String summary(List<PackageScope> packages) {
		long marked = packages.stream().filter(PackageScope::marked).count();
		return marked + " of " + packages.size() + " main-source packages have JSpecify @NullMarked; "
				+ "inventory of package defaults only; declaration opt-outs and compiler exclusions are not audited. "
				+ "Falsification proves enforcement on a new unannotated probe only, not on every existing declaration";
	}

	static String plainOutput(String text) {
		return text.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "");
	}

	/** Own the reserved file through normal completion and JVM shutdown, without deleting replacements. */
	static final class Probe implements AutoCloseable {
		private final Path path;
		private final Thread shutdown;
		private boolean created;
		private boolean closed;
		private Object fileKey;
		private String expected;

		Probe(Path path) {
			this.path = path;
			shutdown = new Thread(() -> {
				try {
					clean();
				}
				catch (IOException e) {
					System.err.println("falsify nullaway: cleanup incomplete: " + e.getMessage());
				}
			}, "nullaway-probe-cleanup");
			Runtime.getRuntime().addShutdownHook(shutdown);
		}

		synchronized void create(String source) throws IOException {
			if (closed) {
				throw new IOException("shutdown started before creating the probe");
			}
			Files.writeString(path, source, java.nio.file.StandardOpenOption.CREATE_NEW);
			created = true;
			fileKey = attributes().fileKey();
			expected = source;
		}

		private java.nio.file.attribute.BasicFileAttributes attributes() throws IOException {
			return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
		}

		private void verifyOwnership() throws IOException {
			var current = attributes();
			if (!current.isRegularFile() || !java.util.Objects.equals(fileKey, current.fileKey()) || !Files.readString(path).equals(expected)) {
				throw new IOException("probe changed externally; leaving it untouched: " + path);
			}
		}

		synchronized void write(String source) throws IOException {
			if (closed) {
				throw new IOException("shutdown started while writing the probe");
			}
			verifyOwnership();
			Files.writeString(path, source, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
			expected = source;
		}

		private synchronized void clean() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			if (created && Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				verifyOwnership();
				Files.delete(path);
				System.out.println("falsify nullaway: removed the planted class");
			}
		}

		@Override
		public void close() throws IOException {
			try {
				clean();
			}
			finally {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdown);
				}
				catch (IllegalStateException e) {
					// JVM shutdown is already running the hook.
				}
			}
		}
	}

	static void requireProfile(Path target) throws IOException {
		Path pom = target.resolve("pom.xml");
		String xml = Files.isRegularFile(pom) ? Files.readString(pom).replaceAll("(?s)<!--.*?-->", "") : "";
		if (!xml.contains("<id>errorprone</id>") || !xml.contains("-Xep:NullAway:")) {
			throw new IllegalArgumentException("install the errorprone profile with NullAway first (playbooks/30-nullaway.md); "
					+ "the first increment supports the kit's root-POM profile");
		}
	}

	static int compile(Path target, String module, boolean report, StringBuilder output) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of(Util.mvnw(target), "-B", "-Dstyle.color=never", "-P", "errorprone",
				"-Dspring-javaformat.skip=true"));
		if (report) {
			command.add("-Derrorprone.failOnWarning=false");
			command.add("-Derrorprone.extraArgs=-XepAllErrorsAsWarnings");
		}
		if (!module.isEmpty()) {
			command.addAll(List.of("-pl", module, "-am"));
		}
		command.addAll(List.of("clean", "compile"));
		System.out.println("Running: " + String.join(" ", command));
		Process process = new ProcessBuilder(command).directory(target.toFile()).redirectErrorStream(true).start();
		Thread shutdown = new Thread(() -> {
			process.descendants().forEach(ProcessHandle::destroy);
			process.destroy();
		}, "nullaway-maven-cleanup");
		Runtime.getRuntime().addShutdownHook(shutdown);
		try {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					output.append(plainOutput(line)).append('\n');
				}
			}
			return process.waitFor();
		}
		finally {
			if (process.isAlive()) {
				process.descendants().forEach(ProcessHandle::destroy);
				process.destroy();
			}
			try {
				Runtime.getRuntime().removeShutdownHook(shutdown);
			}
			catch (IllegalStateException e) {
				// JVM shutdown is already running the hook.
			}
		}
	}
}
