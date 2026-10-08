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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Measure a Maven target against the kit's SpotBugs gate before gating it: run the analysis
 * twice through the target's own wrapper, once with the gate's include filter (rank 9 or less,
 * plus the listed concurrency patterns at any rank) and once with every rank, read each module's
 * {@code target/spotbugsXml.xml}, and print what the gate would fail on, by name, and the long
 * tail it deliberately leaves out, by band and pattern. Ends with a STOP block only when the
 * count at the gate is large enough that the mechanism is a decision.
 *
 * Needs the spotbugs plugin slice installed in the target's root pom (playbook 12, step 2):
 * the slice reads the include filter from the {@code spotbugs.includeFilterFile} property, which
 * is what lets this script swap in the kit's include-everything filter.
 *
 * Usage: {@code ./jbang measure-spotbugs [target] [--top N]}
 */
public class SpotBugsMeasure {

	record Finding(String type, int rank, String category, String module, String className, String method, int line, String message) {
		String band() {
			return rank <= 4 ? "1-4 scariest" : rank <= 9 ? "5-9 scary" : rank <= 14 ? "10-14 troubling" : "15-20 of concern";
		}
	}

	static final int FIX_LIMIT = 30;

	/** Every finding at any rank (a RankMatcher matches rank >= value): configs/spotbugs/spotbugs-include-all.xml, inline so the script needs no kit path. */
	static final String INCLUDE_ALL = """
			<?xml version="1.0" encoding="UTF-8"?>
			<FindBugsFilter xmlns="https://github.com/spotbugs/filter/3.0.0">
			    <Match>
			        <Rank value="1"/>
			    </Match>
			</FindBugsFilter>
			""";

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		int top = intOption(args, "--top", 8);
		if (!Files.isDirectory(target)) {
			System.err.println("measure-spotbugs: not a directory: " + target);
			System.exit(2);
		}
		Path pom = target.resolve("pom.xml");
		if (!Files.isRegularFile(pom) || !Files.readString(pom).contains("spotbugs-maven-plugin")) {
			System.err.println("measure-spotbugs: the spotbugs plugin slice is not in " + pom + "; install it first (playbooks/12-spotbugs.md step 2)");
			System.exit(2);
		}
		Path includeAll = Files.createTempFile("spotbugs-include-all", ".xml");
		Files.writeString(includeAll, INCLUDE_ALL);
		includeAll.toFile().deleteOnExit();
		Util.Decisions decisions = Util.Decisions.read(target);
		List<Util.Module> modules = Util.modules(target);
		Set<String> excluded = decisions.excludedModules().stream().collect(Collectors.toSet());
		List<Util.Module> gated = modules.stream().filter(m -> excluded.stream().noneMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/"))).collect(Collectors.toList());

		System.out.println("# SpotBugs measurement: " + target.getFileName());
		System.out.println();
		System.out.println("Target `" + target + "`, SpotBugs 4.10.4, effort Max, threshold Medium, main sources only; modules the owner left out skip the analysis.");
		decisions.print();

		List<Finding> atGate = analyse(target, gated, null, "gate filter (rank 9 or less, plus concurrency patterns at any rank)");
		List<Finding> all = analyse(target, gated, includeAll, "every rank");

		System.out.println("## At the gate");
		System.out.println();
		if (atGate.isEmpty()) {
			System.out.println("Nothing. The gate is green in report mode.");
		}
		else {
			System.out.println(atGate.size() + " finding" + (atGate.size() == 1 ? "" : "s") + " would fail `verify`. Each is fixed in the code or, when false, excluded by class, method and pattern with the reason in `config/spotbugs/spotbugs-exclude.xml`:");
			System.out.println();
			System.out.println("| rank | pattern | where | message |");
			System.out.println("|---:|---|---|---|");
			atGate.stream().sorted(Comparator.comparingInt(Finding::rank).thenComparing(Finding::type))
					.forEach(f -> System.out.println("| " + f.rank() + " | " + f.type() + " | " + f.className() + "." + f.method() + ":" + f.line() + " (" + f.module() + ") | " + f.message() + " |"));
		}
		System.out.println();

		System.out.println("## The long tail the gate leaves out");
		System.out.println();
		System.out.println(all.size() + " findings at any rank in the gated modules. By band:");
		Map<String, Long> bands = all.stream().collect(Collectors.groupingBy(Finding::band, TreeMap::new, Collectors.counting()));
		bands.forEach((band, n) -> System.out.println("- " + band + ": " + n));
		System.out.println();
		Map<String, List<Finding>> byType = all.stream().filter(f -> f.rank() > 9).collect(Collectors.groupingBy(Finding::type, TreeMap::new, Collectors.toList()));
		System.out.println("| pattern | rank | count | category | note |");
		System.out.println("|---|---:|---:|---|---|");
		byType.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, List<Finding>> e) -> e.getValue().size()).reversed()).limit(top)
				.forEach(e -> System.out.println("| " + e.getKey() + " | " + e.getValue().get(0).rank() + " | " + e.getValue().size() + " | " + e.getValue().get(0).category() + " | " + note(e.getKey()) + " |"));
		System.out.println();
		System.out.println("These are below the gate by design: rank combines severity with confidence, and the tail is advice (exposed representations in records, inner classes that could be static, constructors that throw). A pattern here is gated only by adding it to the include filter with a written reason, as the concurrency patterns were.");
		System.out.println();

		System.out.println("## Stops");
		System.out.println();
		if (atGate.size() > FIX_LIMIT && !decisions.has("SPOTBUGS_MECHANISM")) {
			System.out.println("```");
			System.out.println("STOP SPOTBUGS_MECHANISM");
			System.out.println("  " + atGate.size() + " findings at the gate, more than " + FIX_LIMIT + ".");
			System.out.println("  Options: (a) fix-all now   (b) ratchet: spotbugs.maxAllowedViolations at today's count, lowered as fixes land");
			System.out.println("  Recommendation: (b)");
			System.out.println("  Record as:  SPOTBUGS_MECHANISM: fix-all\n          or  SPOTBUGS_MECHANISM: ratchet");
			System.out.println("```");
		}
		else {
			System.out.println("None. " + (atGate.isEmpty() ? "Flip to fail: the plugin's check is already bound to verify." : "Triage the " + atGate.size() + " at the gate (playbook step 4), then measure again."));
		}
	}

	static String note(String type) {
		if (type.startsWith("EI_EXPOSE_REP")) {
			return "records and value objects handing out what they hold";
		}
		if (type.startsWith("SIC_")) {
			return "inner class could be static";
		}
		if (type.equals("CT_CONSTRUCTOR_THROW")) {
			return "finalizer attack surface; advice";
		}
		if (type.startsWith("SE_")) {
			return "Java serialization";
		}
		return "";
	}

	/** Runs compile + spotbugs:spotbugs with the given include filter (null: the gate's) and reads every gated module's report. */
	static List<Finding> analyse(Path target, List<Util.Module> gated, Path includeFilter, String label) throws Exception {
		List<String> command = new ArrayList<>(List.of(Util.mvnw(target), "-B", "-q", "-Dspring-javaformat.skip=true", "-DskipTests", "-P", "!errorprone"));
		if (includeFilter != null) {
			command.add("-Dspotbugs.includeFilterFile=" + includeFilter);
		}
		command.addAll(List.of("compile", "spotbugs:spotbugs"));
		System.out.println("Running (" + label + "): " + String.join(" ", command));
		StringBuilder out = new StringBuilder();
		int exit = run(target, command, out);
		if (exit != 0) {
			System.out.println();
			System.out.println("The analysis failed (exit " + exit + "):");
			for (String line : out.toString().split("\n")) {
				if (line.startsWith("[ERROR]") && !line.contains("Help 1") && !line.contains("re-run") && !line.isBlank()) {
					System.out.println("    " + line);
				}
			}
			System.out.println("A module with no classes to analyse (an aggregator, a module-info only) sets `<spotbugs.skip>true</spotbugs.skip>` in its own pom.");
			System.exit(1);
		}
		System.out.println();
		List<Finding> findings = new ArrayList<>();
		for (Util.Module m : gated) {
			Path report = m.dir().resolve("target").resolve("spotbugsXml.xml");
			if (Files.isRegularFile(report)) {
				findings.addAll(read(report, m.path()));
			}
		}
		return findings;
	}

	static List<Finding> read(Path report, String module) throws Exception {
		List<Finding> out = new ArrayList<>();
		Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.toFile()).getDocumentElement();
		NodeList bugs = root.getElementsByTagName("BugInstance");
		for (int i = 0; i < bugs.getLength(); i++) {
			Element b = (Element) bugs.item(i);
			Element cls = first(b, "Class");
			Element method = first(b, "Method");
			Element line = first(b, "SourceLine");
			Element msg = first(b, "LongMessage");
			out.add(new Finding(b.getAttribute("type"), Integer.parseInt(b.getAttribute("rank")), b.getAttribute("category"), module,
					cls == null ? "?" : simple(cls.getAttribute("classname")), method == null ? "-" : method.getAttribute("name"),
					line == null || line.getAttribute("start").isEmpty() ? 0 : Integer.parseInt(line.getAttribute("start")),
					msg == null ? "" : msg.getTextContent().trim()));
		}
		return out;
	}

	static Element first(Element parent, String tag) {
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i) instanceof Element e && e.getTagName().equals(tag)) {
				return e;
			}
		}
		return null;
	}

	static String simple(String className) {
		int dot = className.lastIndexOf('.');
		return dot < 0 ? className : className.substring(dot + 1);
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

	static int intOption(String[] args, String name, int dflt) {
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals(name)) {
				return Integer.parseInt(args[i + 1]);
			}
		}
		return dflt;
	}
}
