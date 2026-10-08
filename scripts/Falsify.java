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
 * Usage: {@code ./jbang falsify [target] <gate>} where gate is {@code pmd}, {@code cpd} or {@code errorprone}.
 */
public class Falsify {

	public static void main(String[] args) throws Exception {
		Path target = Util.target(args);
		String gate = args.length > 0 ? args[args.length - 1] : "";
		if (!List.of("pmd", "cpd", "errorprone").contains(gate)) {
			System.err.println("falsify: name the gate to falsify: pmd | cpd | errorprone");
			System.exit(2);
		}
		Path file = firstClassFile(target);
		Path module = moduleOf(target, file);
		byte[] original = Files.readAllBytes(file);
		System.out.println("falsify " + gate + ": planting in " + target.relativize(file) + " (module " + target.relativize(module) + ")");
		int exit;
		StringBuilder output = new StringBuilder();
		try {
			Files.write(file, plant(gate, new String(original, java.nio.charset.StandardCharsets.UTF_8))
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			exit = run(target, module, gate, output);
		}
		finally {
			Files.write(file, original);
			System.out.println("falsify " + gate + ": restored " + target.relativize(file));
		}
		// A brownfield module may be red already, so exit code alone proves nothing: the report must name the plant.
		String evidence;
		if (gate.equals("errorprone")) {
			evidence = output.toString(); // the compiler output is the report
		}
		else {
			Path report = module.resolve("target").resolve(gate.equals("pmd") ? "pmd.xml" : "cpd.xml");
			evidence = Files.exists(report) ? Files.readString(report) : "";
		}
		List<String> fired = rulesFired(gate, evidence, file);
		if (gate.equals("errorprone") && fired.isEmpty() && evidence.contains("warnings found and -Werror specified")
				&& !evidence.contains("] [")) {
			System.out.println("falsify errorprone: NOT PROVEN; the module has javac warnings of its own (deprecation for removal, classpath), "
					+ "and under -Werror javac stops before Error Prone runs. The gate is red for those warnings, which is right, "
					+ "but the plant was not checked. Fix the javac warnings first (playbook 11, step 5), then falsify again.");
			System.exit(1);
		}
		if (exit == 0 || fired.isEmpty()) {
			System.out.println("falsify " + gate + ": FAILED; build exit " + exit + ", planted violation reported by " + fired
					+ "; the gate checks nothing");
			System.exit(1);
		}
		System.out.println("falsify " + gate + ": OK, build red (exit " + exit + ") and the planted violation was reported by " + fired);
	}

	/** The rules that reported the planted member, from the module's PMD or CPD report, or the compiler output for Error Prone. */
	static List<String> rulesFired(String gate, String xml, Path plantedFile) {
		if (gate.equals("cpd")) {
			return xml.contains("guardrailsPlantedCopy") ? List.of("cpd") : List.of();
		}
		if (gate.equals("errorprone")) {
			// a diagnostic on the planted file naming the check: "[ERROR] /path/File.java:[12,5] [DeadException] ..."
			List<String> checks = new java.util.ArrayList<>();
			for (String line : xml.split("\n")) {
				if (line.contains(plantedFile.getFileName().toString() + ":[") && line.contains("[DeadException]")) {
					checks.add("DeadException");
				}
			}
			return checks;
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
		String member = gate.equals("pmd") ? PLANTED_METHOD : gate.equals("cpd") ? PLANTED_COPIES : PLANTED_DEAD_EXCEPTION;
		return source.substring(0, close) + member + source.substring(close);
	}

	/** An exception created and dropped: Error Prone's DeadException, an ERROR by default, a real bug shape. */
	static final String PLANTED_DEAD_EXCEPTION = """

		// planted by java-guardrails falsify: must never be committed
		static void guardrailsPlantedViolation(int a) {
			if (a < 0) {
				new IllegalArgumentException("negative");
			}
		}
""";

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

	/** Runs the gate's check for the module, echoing and collecting its output. */
	static int run(Path target, Path module, String gate, StringBuilder output) throws IOException, InterruptedException {
		List<String> command = gate.equals("errorprone")
				? List.of(Util.mvnw(target), "-B", "-P", "errorprone", "-Dspring-javaformat.skip=true", "-pl", target.relativize(module).toString(), "compile")
				: List.of(Util.mvnw(target), "-q", "-B", "-Dspring-javaformat.skip=true", "-pl", target.relativize(module).toString(),
						gate.equals("pmd") ? "pmd:check" : "pmd:cpd-check");
		System.out.println("falsify: " + String.join(" ", command));
		Process p = new ProcessBuilder(command).directory(target.toFile()).redirectErrorStream(true).start();
		try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				output.append(line).append('\n');
				if (!line.startsWith("[INFO]")) {
					System.out.println(line);
				}
			}
		}
		return p.waitFor();
	}

	/**
	 * The first top-level class file under the first src/main/java, in path order, in a module
	 * the gate runs on: a module whose pom sets pmd.skip or cpd.skip (the owner left it out) is
	 * passed over, because planting there proves nothing.
	 */
	static Path firstClassFile(Path target) throws IOException {
		try (Stream<Path> walk = Files.walk(target)) {
			return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
					.filter(p -> p.toString().contains("/src/main/java/") && !p.toString().contains("/target/"))
					.filter(p -> !gateSkipped(target, moduleOf(target, p)))
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

	/** Whether the module, or a parent pom between it and the target, skips the PMD or CPD goals. */
	static boolean gateSkipped(Path target, Path module) {
		for (Path dir = module; dir != null && dir.startsWith(target); dir = dir.getParent()) {
			Path pom = dir.resolve("pom.xml");
			try {
				if (Files.isRegularFile(pom)) {
					String xml = Files.readString(pom);
					if (xml.contains("<pmd.skip>true</pmd.skip>") || xml.contains("<cpd.skip>true</cpd.skip>")) {
						return true;
					}
				}
			}
			catch (IOException e) {
				return false;
			}
		}
		return false;
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
