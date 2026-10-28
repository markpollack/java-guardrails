//JAVA 17+
//SOURCES Util.java

import java.io.IOException;
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
		for (Util.Module module : Util.modules(target, Util.mavenProfile("nullaway"))) {
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

	static void requireProfile(Path target) throws IOException {
		Path pom = target.resolve("pom.xml");
		String xml = Files.isRegularFile(pom) ? Files.readString(pom).replaceAll("(?s)<!--.*?-->", "") : "";
		if (!xml.contains("<id>errorprone</id>") || !xml.contains("-Xep:NullAway:")) {
			throw new IllegalArgumentException("install the errorprone profile with NullAway first (playbooks/30-nullaway.md); "
					+ "the first increment supports the kit's root-POM profile");
		}
	}

	static int compile(Path target, String module, boolean report, StringBuilder output) throws IOException, InterruptedException {
		List<String> command = Util.maven(target, "nullaway", module);
		if (report) {
			command.add("-Derrorprone.failOnWarning=false");
			command.add("-Derrorprone.extraArgs=-XepAllErrorsAsWarnings");
		}
		command.addAll(List.of("clean", "compile"));
		System.out.println("Running: " + String.join(" ", command));
		return Util.run(command, target, line -> output.append(plainOutput(line)).append('\n'));
	}
}
