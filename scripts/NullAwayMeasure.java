///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES Util.java
//SOURCES NullAwaySupport.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Report NullAway findings and adoption scope without adding annotations or weakening the gate. */
public class NullAwayMeasure {

	// Maven prints compiler findings twice; count unique source locations and messages.
	static final Pattern DIAGNOSTIC = Pattern.compile("^\\[(?:WARNING|ERROR)\\] (.+\\.java):\\[(\\d+),\\d+\\] \\[NullAway\\] (.*)$");

	public static void main(String[] args) throws Exception {
		if (java.util.List.of(args).contains("--help")) {
			System.out.println("Usage: measure-nullaway <target> [--module module-path] [--inventory]");
			return;
		}
		Path target = Util.target(args);
		String module = NullAwaySupport.option(args, "--module");
		var packages = NullAwaySupport.packages(target, module);
		System.out.println("# NullAway measurement: " + target.getFileName());
		System.out.println(NullAwaySupport.summary(packages));
		System.out.println("| module=package | main files | package default |");
		System.out.println("|---|---:|---|");
		for (var scope : packages) {
			System.out.println("| " + scope.key() + " | " + scope.files() + " | " + (scope.marked() ? "@NullMarked" : "unmarked") + " |");
		}
		if (java.util.List.of(args).contains("--inventory")) {
			return;
		}
		NullAwaySupport.requireProfile(target);
		StringBuilder output = new StringBuilder();
		int exit = NullAwaySupport.compile(target, module, true, output);
		Set<String> findings = new LinkedHashSet<>();
		for (String line : output.toString().split("\\R")) {
			Matcher match = DIAGNOSTIC.matcher(line);
			if (match.matches()) {
				Path file = Path.of(match.group(1)).toAbsolutePath().normalize();
				findings.add(target.relativize(file) + ":" + match.group(2) + " " + match.group(3));
			}
		}
		System.out.println("\nNullAway findings: " + findings.size());
		findings.forEach(finding -> System.out.println("- " + finding));
		if (exit != 0) {
			System.err.println(output);
			System.err.println("measure-nullaway: report-mode compile failed (exit " + exit + "); findings are partial. Fix the build first.");
			System.exit(1);
		}
		System.out.println("Report-mode compile passed. A zero count does not prove NullAway ran: "
				+ "run falsify nullaway on an existing marked package before claiming a gate.");
		if (packages.stream().noneMatch(NullAwaySupport.PackageScope::marked)) {
			System.out.println("No package defaults are marked. Choose one package using playbook 30; do not claim whole-project null safety.");
		}
	}
}
