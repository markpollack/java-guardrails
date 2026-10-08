///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//SOURCES Util.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Prove a gate is live by planting a violation in the target's main sources, running the build
 * step that should fail, and restoring the file byte for byte. Exit 0 means the gate caught the
 * plant (red as expected); exit 1 means the build stayed green, so the gate checks nothing.
 *
 * Usage: {@code ./jbang falsify [target] <gate>} where gate is {@code pmd} or {@code cpd}.
 */
public class Falsify {

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		String gate = args.length > 0 ? args[args.length - 1] : "";
		if (!List.of("pmd", "cpd").contains(gate)) {
			System.err.println("falsify: name the gate to falsify: pmd | cpd");
			System.exit(2);
		}
		Path file = firstClassFile(target);
		Path module = moduleOf(target, file);
		byte[] original = Files.readAllBytes(file);
		System.out.println("falsify " + gate + ": planting in " + target.relativize(file) + " (module " + target.relativize(module) + ")");
		int exit;
		try {
			Files.write(file, plant(gate, new String(original, java.nio.charset.StandardCharsets.UTF_8))
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			exit = run(target, module, gate.equals("pmd") ? "pmd:check" : "pmd:cpd-check");
		}
		finally {
			Files.write(file, original);
			System.out.println("falsify " + gate + ": restored " + target.relativize(file));
		}
		// A brownfield module may be red already, so exit code alone proves nothing: the report must name the plant.
		Path report = module.resolve("target").resolve(gate.equals("pmd") ? "pmd.xml" : "cpd.xml");
		String xml = Files.exists(report) ? Files.readString(report) : "";
		List<String> fired = rulesFired(gate, xml);
		if (exit == 0 || fired.isEmpty()) {
			System.out.println("falsify " + gate + ": FAILED; build exit " + exit + ", planted violation reported by " + fired
					+ "; the gate checks nothing");
			System.exit(1);
		}
		System.out.println("falsify " + gate + ": OK, build red (exit " + exit + ") and the planted violation was reported by " + fired);
	}

	/** The rules that reported the planted member, from the module's PMD or CPD report. */
	static List<String> rulesFired(String gate, String xml) {
		if (gate.equals("cpd")) {
			return xml.contains("guardrailsPlantedCopy") ? List.of("cpd") : List.of();
		}
		List<String> rules = new java.util.ArrayList<>();
		for (String violation : xml.split("<violation ")) {
			if (violation.contains("method=\"guardrailsPlantedViolation\"")) {
				int at = violation.indexOf("rule=\"") + 6;
				rules.add(violation.substring(at, violation.indexOf('"', at)));
			}
		}
		return rules;
	}

	/** Append a violating member before the type's closing brace. */
	static String plant(String gate, String source) {
		int close = source.lastIndexOf('}');
		return source.substring(0, close) + (gate.equals("pmd") ? PLANTED_METHOD : PLANTED_COPIES) + source.substring(close);
	}

	/** Cognitive 15+, cyclomatic 10+, NCSS 30+, 7+ parameters: every PMD rule in the gate fires. */
	static final String PLANTED_METHOD = """

		// planted by java-guardrails falsify: must never be committed
		static int guardrailsPlantedViolation(int a, int b, int c, int d, int e, int f, int g) {
			int total = 0;
			for (int i = 0; i < a; i++) {
				if (i % 2 == 0) {
					if (b > i) {
						total += 1;
						total += 2;
						total += 3;
					} else if (c > i) {
						total += 4;
						total += 5;
						total += 6;
					} else {
						total += 7;
						total += 8;
					}
				} else if (d > i) {
					while (e > total) {
						total += 9;
						if (f > total) {
							total += 10;
						}
					}
				} else if (g > i && f > g || e > d) {
					total += 11;
					total += 12;
					total += 13;
					total += 14;
					total += 15;
					total += 16;
					total += 17;
					total += 18;
				}
			}
			return total;
		}
""";

	/** Two copies of a 100+-token block in one file: CPD fires at cpd.minimumTokens 100. */
	static final String PLANTED_COPIES = """

		// planted by java-guardrails falsify: must never be committed
		static int guardrailsPlantedCopyOne(int a, int b) {
			int total = 0;
			total += a * 2 + b * 3;
			total += a * 4 + b * 5;
			total += a * 6 + b * 7;
			total += a * 8 + b * 9;
			total += a * 10 + b * 11;
			total += a * 12 + b * 13;
			total += a * 14 + b * 15;
			total += a * 16 + b * 17;
			total += a * 18 + b * 19;
			total += a * 20 + b * 21;
			total += a * 22 + b * 23;
			total += a * 24 + b * 25;
			return total;
		}

		static int guardrailsPlantedCopyTwo(int a, int b) {
			int total = 0;
			total += a * 2 + b * 3;
			total += a * 4 + b * 5;
			total += a * 6 + b * 7;
			total += a * 8 + b * 9;
			total += a * 10 + b * 11;
			total += a * 12 + b * 13;
			total += a * 14 + b * 15;
			total += a * 16 + b * 17;
			total += a * 18 + b * 19;
			total += a * 20 + b * 21;
			total += a * 22 + b * 23;
			total += a * 24 + b * 25;
			return total;
		}
""";

	static int run(Path target, Path module, String goal) throws IOException, InterruptedException {
		List<String> command = List.of(Util.mvnw(target), "-q", "-B", "-Dspring-javaformat.skip=true", "-pl",
				target.relativize(module).toString(), goal);
		System.out.println("falsify: " + String.join(" ", command));
		Process p = new ProcessBuilder(command).directory(target.toFile()).inheritIO().start();
		return p.waitFor();
	}

	/** The first top-level class file under the first src/main/java, in path order. */
	static Path firstClassFile(Path target) throws IOException {
		try (Stream<Path> walk = Files.walk(target)) {
			return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
					.filter(p -> p.toString().contains("/src/main/java/") && !p.toString().contains("/target/"))
					.filter(p -> !p.getFileName().toString().equals("package-info.java")
							&& !p.getFileName().toString().equals("module-info.java"))
					.filter(p -> {
						try {
							return Files.readString(p).contains("\nclass ") || Files.readString(p).contains(" class ");
						}
						catch (IOException e) {
							return false;
						}
					})
					.sorted().findFirst()
					.orElseThrow(() -> new IllegalStateException("no class under src/main/java below " + target));
		}
	}

	/** The nearest ancestor of the file that holds a pom.xml. */
	static Path moduleOf(Path target, Path file) {
		for (Path dir = file.getParent(); dir != null && dir.startsWith(target); dir = dir.getParent()) {
			if (Files.exists(dir.resolve("pom.xml"))) {
				return dir;
			}
		}
		return target;
	}
}
