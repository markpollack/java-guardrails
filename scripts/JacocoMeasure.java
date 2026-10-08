///usr/bin/env jbang "$0" "$@" ; exit $?
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Measure a Maven target's coverage before gating it: run each gated module's own tests under
 * the JaCoCo agent through the target's wrapper, read each module's {@code jacoco.xml}, and print
 * the LINE and BRANCH ratios with the floor the kit proposes, measured minus 2 points rounded
 * down to a whole percent. Ends with the STOP block for the one decision the owner makes here:
 * the floors each module declares. Once recorded, prints the floors as the properties to put in
 * each module's pom, and whether the measured coverage still clears them.
 *
 * Needs nothing installed: the agent and report goals are named on the command line, so this
 * works before the plugin slice is in the pom. The gate itself (playbook 21, step 3) is the slice.
 *
 * Usage: {@code ./jbang measure-jacoco [target]}
 */
public class JacocoMeasure {

	record Coverage(String module, int lineCovered, int lineTotal, int branchCovered, int branchTotal) {
		double line() {
			return lineTotal == 0 ? 1.0 : (double) lineCovered / lineTotal;
		}

		double branch() {
			return branchTotal == 0 ? 1.0 : (double) branchCovered / branchTotal;
		}
	}

	static final String JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.15";
	static final int MARGIN = 2;
	static final Pattern FLOOR = Pattern.compile("([^\\s;:]+)\\s*:?\\s*(\\d+)\\s*/\\s*(\\d+)");

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		if (!Files.isDirectory(target) || !Files.isRegularFile(target.resolve("pom.xml"))) {
			System.err.println("measure-jacoco: not a Maven project: " + target);
			System.exit(2);
		}
		Util.Decisions decisions = Util.Decisions.read(target);
		List<Util.Module> modules = Util.modules(target);
		Set<String> excluded = decisions.excludedModules().stream().collect(Collectors.toSet());
		List<Util.Module> gated = new ArrayList<>();
		for (Util.Module m : modules) {
			boolean leftOut = excluded.stream().anyMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/"));
			if (!leftOut && hasCode(m)) {
				gated.add(m);
			}
		}

		System.out.println("# Coverage measurement: " + target.getFileName());
		System.out.println();
		System.out.println("Target `" + target + "`, JaCoCo 0.8.15, each module's own classes exercised by its own tests; modules the owner left out skip.");
		decisions.print();

		String pl = gated.stream().map(Util.Module::path).collect(Collectors.joining(","));
		String[] argLine = surefireArgLine(target);
		String argLineProperty = argLine[0];
		if (argLine[1].equals("$")) {
			System.out.println("**The agent cannot attach.** The surefire `<argLine>` reads `${" + argLineProperty + "}`, which Maven interpolates when it reads the pom, before JaCoCo sets the property; surefire then runs with the empty value and the report says \"missing execution data file\". Change it to `@{" + argLineProperty + "}` (surefire's late evaluation) in the root pom, then run measure-jacoco again.");
			System.exit(1);
		}
		if (!argLineProperty.equals("argLine")) {
			System.out.println("The surefire `<argLine>` reads `@{" + argLineProperty + "}`, not JaCoCo's default `argLine`; the agent is written into that property (`jacoco.propertyName`). The plugin slice needs the same `<propertyName>`.");
			System.out.println();
		}
		List<String> command = List.of(Util.mvnw(target), "-B", "-q", "-Dspring-javaformat.skip=true", "-P", "!errorprone",
				"-Djacoco.propertyName=" + argLineProperty, "-pl", pl, JACOCO + ":prepare-agent", "test", JACOCO + ":report");
		System.out.println("Running: " + String.join(" ", command));
		System.out.println();
		StringBuilder out = new StringBuilder();
		int exit = run(target, command, out);
		if (exit != 0) {
			System.out.println("The test run failed (exit " + exit + "); coverage is not measured on a red test suite:");
			for (String line : out.toString().split("\n")) {
				if (line.contains("Tests run:") && (line.contains("Failures: ") && !line.contains("Failures: 0,") || line.contains("Errors: ") && !line.contains("Errors: 0,"))) {
					System.out.println("    " + line.trim());
				}
			}
			System.exit(1);
		}

		List<Coverage> coverages = new ArrayList<>();
		for (Util.Module m : gated) {
			Path report = m.dir().resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.xml");
			if (Files.isRegularFile(report)) {
				coverages.add(read(report, m.path()));
			}
		}

		System.out.println("## Measured, and the floor proposed (measured minus " + MARGIN + " points, rounded down)");
		System.out.println();
		System.out.println("| module | lines covered | line % | floor | branches covered | branch % | floor |");
		System.out.println("|---|---:|---:|---:|---:|---:|---:|");
		for (Coverage c : coverages) {
			System.out.printf(Locale.ROOT, "| %s | %d / %d | %.1f | %d | %d / %d | %.1f | %d |%n", c.module(), c.lineCovered(), c.lineTotal(),
					100 * c.line(), floor(c.line()), c.branchCovered(), c.branchTotal(), 100 * c.branch(), floor(c.branch()));
		}
		System.out.println();
		System.out.println("A module with no executable code passes an empty ratio and needs no floor. Coverage reached only from another module's tests is not counted: that is a test to write in the owning module, not a floor to lower.");
		System.out.println();

		System.out.println("## Stops");
		System.out.println();
		if (!decisions.has("JACOCO_FLOORS")) {
			System.out.println("Relay the block below to the owner as written, record the answer in `" + target.relativize(decisions.file) + "`, then run measure-jacoco again.");
			System.out.println();
			System.out.println("```");
			System.out.println("STOP JACOCO_FLOORS");
			System.out.println("  The floors below are measured minus " + MARGIN + " points, rounded down: enough for run-to-run variation of concurrent tests, too little to lose a test class unnoticed. Raise a floor when coverage rises; lower one only with a reason in the commit.");
			for (Coverage c : coverages) {
				System.out.printf(Locale.ROOT, "    %s: measured %.1f / %.1f, floor %d / %d (line / branch)%n", c.module(), 100 * c.line(), 100 * c.branch(), floor(c.line()), floor(c.branch()));
			}
			System.out.println("  Options: (a) the floors as proposed   (b) your own floors per module, never above measured");
			System.out.println("  Recommendation: (a)");
			System.out.println("  Record as:  JACOCO_FLOORS: " + coverages.stream().map(c -> c.module() + " " + floor(c.line()) + "/" + floor(c.branch())).collect(Collectors.joining("; ")));
			System.out.println("```");
			return;
		}
		System.out.println("None. The floors recorded, as the properties each module's pom declares (the parent's default stays 1.00 so a new module must declare its own), and whether today's coverage clears them:");
		System.out.println();
		Matcher m = FLOOR.matcher(decisions.get("JACOCO_FLOORS"));
		while (m.find()) {
			String module = m.group(1);
			int line = Integer.parseInt(m.group(2));
			int branch = Integer.parseInt(m.group(3));
			Coverage c = coverages.stream().filter(x -> x.module().equals(module)).findFirst().orElse(null);
			String clears = c == null ? "not measured" : (100 * c.line() >= line && 100 * c.branch() >= branch) ? "clears" : "**below the floor**";
			System.out.printf(Locale.ROOT, "- %s: `<jacoco.minimum.line>0.%02d</jacoco.minimum.line>` `<jacoco.minimum.branch>0.%02d</jacoco.minimum.branch>` (%s%s)%n",
					module, line, branch, clears, c == null ? "" : String.format(Locale.ROOT, ", measured %.1f / %.1f", 100 * c.line(), 100 * c.branch()));
		}
	}

	/**
	 * The property surefire's {@code <argLine>} reads and how: {@code @{name}} is evaluated late and
	 * sees what JaCoCo set; {@code ${name}} is interpolated when Maven reads the pom and never does.
	 * Returns {name, "@" | "$" | ""}; {"argLine", ""} when surefire sets no argLine.
	 */
	static String[] surefireArgLine(Path target) throws IOException {
		String pom = Files.readString(target.resolve("pom.xml"));
		int surefire = pom.indexOf("<artifactId>maven-surefire-plugin</artifactId>");
		if (surefire < 0) {
			return new String[] { "argLine", "" };
		}
		int end = pom.indexOf("</plugin>", surefire);
		Matcher m = Pattern.compile("<argLine>\\s*([@$])\\{([A-Za-z0-9_.-]+)\\}").matcher(pom.substring(surefire, end < 0 ? pom.length() : end));
		return m.find() ? new String[] { m.group(2), m.group(1) } : new String[] { "argLine", "" };
	}

	static int floor(double ratio) {
		return Math.max(0, (int) Math.floor(ratio * 100) - MARGIN);
	}

	static boolean hasCode(Util.Module m) throws IOException {
		return Util.javaFiles(m.sourceRoot()).stream().anyMatch(p -> !p.getFileName().toString().equals("module-info.java"));
	}

	static Coverage read(Path report, String module) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		Element root = f.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
		int lc = 0, lt = 0, bc = 0, bt = 0;
		NodeList counters = root.getChildNodes();
		for (int i = 0; i < counters.getLength(); i++) {
			if (counters.item(i) instanceof Element e && e.getTagName().equals("counter")) {
				int covered = Integer.parseInt(e.getAttribute("covered"));
				int missed = Integer.parseInt(e.getAttribute("missed"));
				if (e.getAttribute("type").equals("LINE")) {
					lc = covered;
					lt = covered + missed;
				}
				else if (e.getAttribute("type").equals("BRANCH")) {
					bc = covered;
					bt = covered + missed;
				}
			}
		}
		return new Coverage(module, lc, lt, bc, bt);
	}

	static int run(Path target, List<String> command, StringBuilder out) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(command).directory(target.toFile()).redirectErrorStream(true).start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				out.append(line).append('\n');
			}
		}
		return p.waitFor();
	}
}
