///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS com.google.errorprone:error_prone_core:2.50.0
//SOURCES Util.java

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.errorprone.BugCheckerInfo;
import com.google.errorprone.BugPattern;
import com.google.errorprone.scanner.BuiltInCheckerSuppliers;

/**
 * Measure a Maven target against the kit's Error Prone profile before gating it: run the
 * profile in report mode (every finding a warning, nothing fails), group the findings by check
 * and module, classify each check with Error Prone's own metadata (default severity and tags),
 * and end with the STOP block for the one decision the owner makes here: which style checks are
 * disabled by name, with a reason, rather than fixed.
 *
 * Needs the errorprone profile installed in the target's root pom (playbook 11, step 3) and a
 * JDK 21 or newer, which Error Prone requires. Runs the target's own Maven wrapper.
 *
 * Usage: {@code ./jbang measure-errorprone [target] [--top N]}
 */
public class ErrorProneMeasure {

	record Finding(String check, String module, String file, int line, String message) {
	}

	/** What Error Prone itself says about a check: default severity and tags; absent for checks it does not ship. */
	record CheckInfo(String name, String severity, Set<String> tags, boolean enabledByDefault) {
	}

	static final Pattern DIAGNOSTIC = Pattern.compile("^\\[(?:WARNING|ERROR)\\] (\\S+\\.java):\\[(\\d+),(\\d+)\\] \\[([A-Za-z0-9_]+)\\] (.*)$");
	/** javac's own warnings, which the gate's -Werror fails on too and which stop Error Prone from running until fixed. */
	static final Pattern JAVAC_WARNING = Pattern.compile("^\\[WARNING\\] (\\S+\\.java):\\[(\\d+),(\\d+)\\] (?!\\[)(.*)$");
	static final Pattern JAVAC_UNLOCATED = Pattern.compile("^\\[WARNING\\] (unknown enum constant .*|.*class file for .* not found.*)$");

	/**
	 * Classification from Error Prone's own metadata. A check is a bug finder when it is tagged
	 * LIKELY_ERROR, FRAGILE_CODE or CONCURRENCY, or its default severity is ERROR. It is style when
	 * tagged STYLE, SIMPLIFICATION, REFACTORING or PERFORMANCE. Error Prone leaves many checks
	 * untagged (its Javadoc checks, and bug finders such as ClassInitializationDeadlock alike), so an
	 * untagged WARNING is put to the owner with the others, with a recommendation from its count.
	 */
	static final Set<String> BUG_TAGS = Set.of(BugPattern.StandardTags.LIKELY_ERROR, BugPattern.StandardTags.FRAGILE_CODE,
			BugPattern.StandardTags.CONCURRENCY);
	static final Set<String> STYLE_TAGS = Set.of(BugPattern.StandardTags.STYLE, BugPattern.StandardTags.SIMPLIFICATION,
			BugPattern.StandardTags.REFACTORING, BugPattern.StandardTags.PERFORMANCE);
	/** Checks the kit's profile raises beyond Error Prone's defaults, and why they are bugs for a library. */
	static final Map<String, String> KIT_RAISED = Map.of("SystemOut", "a stray print corrupts a process whose stdout is a protocol stream",
			"CheckReturnValue", "a Reactor operator whose result is dropped never runs", "NullAway", "null-marked packages are checked");
	static final int FIX_LIMIT = 10;

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		int top = intOption(args, "--top", 5);
		if (!Files.isDirectory(target)) {
			System.err.println("measure-errorprone: not a directory: " + target);
			System.exit(2);
		}
		Path pom = target.resolve("pom.xml");
		if (!Files.isRegularFile(pom) || !Files.readString(pom).contains("<id>errorprone</id>")) {
			System.err.println("measure-errorprone: the errorprone profile is not in " + pom + "; install it first (playbooks/11-errorprone.md step 3)");
			System.exit(2);
		}
		Util.Decisions decisions = Util.Decisions.read(target);
		List<Util.Module> modules = Util.modules(target);
		Set<String> excluded = decisions.excludedModules().stream().collect(Collectors.toSet());
		Set<String> disabled = disabledChecks(decisions);

		System.out.println("# Error Prone measurement: " + target.getFileName());
		System.out.println();
		System.out.println("Target `" + target + "`, Error Prone 2.50.0, the kit's profile in report mode, main sources only.");
		decisions.print();

		List<String> command = List.of(Util.mvnw(target), "-B", "-P", "errorprone", "-Dspring-javaformat.skip=true",
				"-Derrorprone.failOnWarning=false", "-Derrorprone.extraArgs=-XepAllErrorsAsWarnings", "clean", "compile");
		System.out.println("Running: " + String.join(" ", command));
		System.out.println();
		List<Finding> findings = new ArrayList<>();
		String[] currentModule = { "?" };
		Pattern building = Pattern.compile("^\\[INFO\\] Building (.*) \\S+( +\\[\\d+/\\d+\\])?$");
		int exit = run(target, command, line -> {
			Matcher m = DIAGNOSTIC.matcher(line);
			if (m.matches()) {
				Path file = Path.of(m.group(1));
				String module = modules.stream().filter(mod -> file.startsWith(mod.sourceRoot())).map(Util.Module::path).findFirst().orElse("?");
				findings.add(new Finding(m.group(4), module, target.relativize(file).toString().replace('\\', '/'), Integer.parseInt(m.group(2)), m.group(5)));
				return;
			}
			Matcher j = JAVAC_WARNING.matcher(line);
			if (j.matches()) {
				Path file = Path.of(j.group(1));
				String module = modules.stream().filter(mod -> file.startsWith(mod.sourceRoot())).map(Util.Module::path).findFirst().orElse("?");
				findings.add(new Finding(javacKind(j.group(4)), module, target.relativize(file).toString().replace('\\', '/'), Integer.parseInt(j.group(2)), j.group(4)));
				return;
			}
			Matcher u = JAVAC_UNLOCATED.matcher(line);
			if (u.matches()) {
				findings.add(new Finding("javac:classpath", currentModule[0], "(no file: a class referenced by a dependency is missing from the compile classpath)", 0, u.group(1)));
			}
		});
		if (exit != 0) {
			System.err.println("measure-errorprone: the report-mode compile failed (exit " + exit + "); the findings above are partial. Fix the build first.");
			System.exit(1);
		}

		Map<String, CheckInfo> catalog = catalog();
		java.util.function.Predicate<Finding> leftOutModule = f -> excluded.stream().anyMatch(x -> f.module().equals(x) || f.module().startsWith(x + "/"));
		List<Finding> gated = findings.stream().filter(f -> !leftOutModule.test(f) && !disabled.contains(f.check())).collect(Collectors.toList());
		List<Finding> leftOut = findings.stream().filter(leftOutModule).collect(Collectors.toList());
		List<Finding> disabledFindings = findings.stream().filter(f -> !leftOutModule.test(f) && disabled.contains(f.check())).collect(Collectors.toList());

		System.out.println("## Findings by check, gated modules");
		System.out.println();
		System.out.println(gated.size() + " findings" + (leftOut.isEmpty() ? "" : "; " + leftOut.size() + " more in modules left out of the gate")
				+ (disabledFindings.isEmpty() ? "" : "; " + disabledFindings.size() + " more from checks the owner disabled") + ".");
		System.out.println();
		Map<String, List<Finding>> byCheck = gated.stream().collect(Collectors.groupingBy(Finding::check, TreeMap::new, Collectors.toList()));
		List<Map.Entry<String, List<Finding>>> ordered = byCheck.entrySet().stream()
				.sorted(Comparator.comparingInt((Map.Entry<String, List<Finding>> e) -> e.getValue().size()).reversed().thenComparing(Map.Entry::getKey))
				.collect(Collectors.toList());
		System.out.println("| check | findings | Error Prone default | tags | kind |");
		System.out.println("|---|---:|---|---|---|");
		List<String> styleCandidates = new ArrayList<>();
		for (Map.Entry<String, List<Finding>> e : ordered) {
			CheckInfo info = catalog.get(e.getKey());
			String kind = kind(e.getKey(), info);
			if (kind.startsWith("style")) {
				styleCandidates.add(e.getKey());
			}
			System.out.println("| " + e.getKey() + " | " + e.getValue().size() + " | " + (info == null ? "not an Error Prone core check" : info.severity() + (info.enabledByDefault() ? "" : ", off by default"))
					+ " | " + (info == null ? "" : String.join(" ", info.tags())) + " | " + kind + " |");
		}
		System.out.println();
		System.out.println("Kind: **bug** when Error Prone tags the check LIKELY_ERROR, FRAGILE_CODE or CONCURRENCY or defaults it to ERROR, or the kit raised it; fix each, red-first where a test can show it. **style**, and **untagged** checks, go to the owner's decision below.");
		System.out.println();

		System.out.println("## Where");
		System.out.println();
		for (Map.Entry<String, List<Finding>> e : ordered) {
			System.out.println("### " + e.getKey() + " (" + e.getValue().size() + ")");
			e.getValue().stream().limit(top).forEach(f -> System.out.println("- `" + f.file() + ":" + f.line() + "` " + f.message()));
			if (e.getValue().size() > top) {
				System.out.println("- … " + (e.getValue().size() - top) + " more");
			}
			System.out.println();
		}
		Map<String, Long> perModule = gated.stream().collect(Collectors.groupingBy(Finding::module, TreeMap::new, Collectors.counting()));
		System.out.println("Per module: " + perModule.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
		System.out.println();

		System.out.println("## Stops");
		System.out.println();
		if (styleCandidates.isEmpty() || decisions.has("ERRORPRONE_DISABLED")) {
			long bugs = gated.stream().filter(f -> !styleCandidates.contains(f.check()) || decisions.has("ERRORPRONE_DISABLED")).count();
			System.out.println("None. " + (gated.isEmpty() ? "The gate is green in report mode: flip it to fail (playbook step 6)."
					: gated.size() + " findings to fix in the code; then `measure-errorprone` again."));
			System.out.println();
			return;
		}
		StringBuilder b = new StringBuilder("STOP ERRORPRONE_DISABLED\n");
		b.append("  These checks have findings and Error Prone does not mark them as bugs (style-tagged, or untagged WARNINGs); each is either fixed in the code or disabled by name in the profile with a reason:\n");
		List<String> recommendDisable = new ArrayList<>();
		for (String check : styleCandidates) {
			int n = byCheck.get(check).size();
			CheckInfo info = catalog.get(check);
			boolean disable = n > FIX_LIMIT;
			if (disable) {
				recommendDisable.add(check);
			}
			b.append("    ").append(check).append(": ").append(n).append(" finding").append(n == 1 ? "" : "s")
					.append(info == null ? "" : " (" + (info.tags().isEmpty() ? "untagged" : String.join(" ", info.tags())) + ")")
					.append("; recommendation: ").append(disable ? "disable, more than " + FIX_LIMIT + " and pure style" : "fix, " + n + " is cheap").append("\n");
		}
		b.append("  Options per check: fix  |  disable (a -Xep:<Check>:OFF in the profile and a line in configs/errorprone/disabled-checks.txt with the reason)\n");
		b.append("  Record as:  ERRORPRONE_DISABLED: ").append(recommendDisable.isEmpty() ? "none" : String.join(", ", recommendDisable)).append("\n");
		System.out.println("Relay the block below to the owner as written, record the answer in `" + target.relativize(decisions.file) + "`, then run measure-errorprone again.");
		System.out.println();
		System.out.println("```");
		System.out.print(b);
		System.out.println("```");
	}

	/** javac warnings grouped by what they are, as pseudo-checks named javac:<kind>. */
	static String javacKind(String message) {
		if (message.contains("marked for removal")) {
			return "javac:removal";
		}
		if (message.contains("deprecated")) {
			return "javac:deprecation";
		}
		if (message.contains("unchecked")) {
			return "javac:unchecked";
		}
		return "javac:other";
	}

	static String kind(String check, CheckInfo info) {
		if (check.startsWith("javac:")) {
			return "bug (javac): the gate's -Werror fails on it, and until it is fixed javac stops before Error Prone runs";
		}
		if (KIT_RAISED.containsKey(check)) {
			return "bug (kit): " + KIT_RAISED.get(check);
		}
		if (info == null) {
			return "unknown to Error Prone core: triage by hand";
		}
		if (info.tags().stream().anyMatch(BUG_TAGS::contains) || "ERROR".equals(info.severity())) {
			return "bug";
		}
		if (info.tags().stream().anyMatch(STYLE_TAGS::contains)) {
			return "style";
		}
		return "style? untagged";
	}

	/** Every check Error Prone core ships, by canonical name, with its default severity, tags and whether it is on by default. */
	static Map<String, CheckInfo> catalog() {
		Map<String, CheckInfo> all = new LinkedHashMap<>();
		for (BugCheckerInfo c : BuiltInCheckerSuppliers.ENABLED_ERRORS) {
			all.put(c.canonicalName(), new CheckInfo(c.canonicalName(), c.defaultSeverity().name(), c.getTags(), true));
		}
		for (BugCheckerInfo c : BuiltInCheckerSuppliers.ENABLED_WARNINGS) {
			all.put(c.canonicalName(), new CheckInfo(c.canonicalName(), c.defaultSeverity().name(), c.getTags(), true));
		}
		for (BugCheckerInfo c : BuiltInCheckerSuppliers.DISABLED_CHECKS) {
			all.put(c.canonicalName(), new CheckInfo(c.canonicalName(), c.defaultSeverity().name(), c.getTags(), false));
		}
		return all;
	}

	/** {@code ERRORPRONE_DISABLED: InlineMeSuggester, MissingSummary} or {@code none}. */
	static Set<String> disabledChecks(Util.Decisions d) {
		String v = d.get("ERRORPRONE_DISABLED").trim();
		if (v.isEmpty() || v.equals("none")) {
			return Set.of();
		}
		return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
	}

	static int run(Path target, List<String> command, java.util.function.Consumer<String> eachLine) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(command).directory(target.toFile()).redirectErrorStream(true).start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				eachLine.accept(line);
			}
		}
		return p.waitFor();
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
