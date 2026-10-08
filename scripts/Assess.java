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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Inventory a target project before any gate is installed: build tool and wrapper, Java level,
 * modules with their source counts and whether they look like test support, root packages, the
 * quality tooling already in the build, which of the kit's gates are installed, the decisions
 * recorded so far, and the next step. Reads files only; runs nothing.
 *
 * Usage: {@code ./jbang assess [target]}
 */
public class Assess {

	/** Quality tooling the kit knows about, and the text that reveals it in a pom. */
	static final Map<String, String> TOOLING = new LinkedHashMap<>();
	static {
		TOOLING.put("maven-pmd-plugin", "PMD (the kit's tier-1 gate)");
		TOOLING.put("spotbugs-maven-plugin", "SpotBugs (the kit's tier-1 gate)");
		TOOLING.put("error_prone_core", "Error Prone (the kit's tier-1 gate)");
		TOOLING.put("nullaway", "NullAway (tier 3)");
		TOOLING.put("jacoco-maven-plugin", "JaCoCo (the kit's tier-2 gate)");
		TOOLING.put("archunit", "ArchUnit (the kit's tier-2 gate)");
		TOOLING.put("pitest-maven", "PIT (tier 3)");
		TOOLING.put("lincheck", "Lincheck (tier 3)");
		TOOLING.put("maven-checkstyle-plugin", "Checkstyle (not a kit gate; style)");
		TOOLING.put("spring-javaformat-maven-plugin", "Spring Java Format (not a kit gate; formatting, run it before each commit)");
		TOOLING.put("maven-enforcer-plugin", "Enforcer (not a kit gate)");
		TOOLING.put("sonar-maven-plugin", "Sonar (not a kit gate)");
	}

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
		System.out.println("Java: " + Util.javaVersion(target) + (Files.exists(target.resolve(".mvn").resolve("jvm.config")) ? "; .mvn/jvm.config present" : ""));
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
		for (Util.Module m : modules) {
			int mainCount = Util.javaFiles(m.sourceRoot()).size();
			int testCount = Util.javaFiles(m.dir().resolve("src").resolve("test").resolve("java")).size();
			mainTotal += mainCount;
			System.out.println("| " + m.path() + " | " + mainCount + " | " + testCount + " | "
					+ (m.testSupportReasons().isEmpty() ? "" : String.join("; ", m.testSupportReasons())) + " |");
		}
		System.out.println();
		System.out.println(modules.size() + " modules with main sources, " + mainTotal + " main-source files.");
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
		String pmd = pmdRuleset && pmdPlugin ? "installed (ruleset and plugin present)"
				: pmdPlugin ? "a PMD plugin is in the build but not the kit's ruleset: measure first, then decide whether to replace its configuration"
						: "not installed";
		System.out.println("| gate | tier | kit status | this target |");
		System.out.println("|---|---|---|---|");
		System.out.println("| PMD size, complexity, duplication | 1 | proven on two code bases | " + pmd + " |");
		System.out.println("| Error Prone | 1 | configs from one code base; playbook an outline | " + (found.containsKey("error_prone_core") ? "present in the build" : "not installed") + " |");
		System.out.println("| SpotBugs | 1 | configs from one code base; playbook an outline | " + (found.containsKey("spotbugs-maven-plugin") ? "present in the build" : "not installed") + " |");
		System.out.println("| ArchUnit | 2 | template from one code base; playbook an outline | " + (found.containsKey("archunit") ? "present in the build" : "not installed") + " |");
		System.out.println("| JaCoCo floors | 2 | mechanism from one code base; playbook an outline | " + (found.containsKey("jacoco-maven-plugin") ? "present in the build" : "not installed") + " |");
		System.out.println();

		// Decisions
		Util.Decisions decisions = Util.Decisions.read(target);
		decisions.print();

		// Next step
		System.out.println("## Next");
		System.out.println();
		if (!maven) {
			System.out.println("Nothing the kit can install here yet. `measure` still works for the numbers.");
		}
		else if (!pmdRuleset || !pmdPlugin) {
			System.out.println("The PMD gate (`playbooks/10-pmd.md`). Step 1: `<kit>/jbang <kit>/scripts/PmdMeasure.java " + target + "`.");
			System.out.println("It ends with STOP blocks for the decisions it needs; relay them, record the answers, run it again.");
		}
		else if (!decisions.has("PMD_MECHANISM")) {
			System.out.println("The PMD gate is installed but the mechanism is undecided: run `measure` and answer its STOP blocks.");
		}
		else {
			System.out.println("The PMD gate is installed and decided (" + decisions.get("PMD_MECHANISM") + "). Go green: `./mvnw verify` is the target; then `<kit>/jbang <kit>/scripts/Falsify.java " + target + " pmd` and `cpd`.");
		}
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
