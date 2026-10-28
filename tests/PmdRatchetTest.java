///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//SOURCES ../scripts/Falsify.java

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real Maven proof: a PMD allowance must never become an allowance for CPD duplicates. */
public class PmdRatchetTest {

	record Result(int exit, String output) {
	}

	static Path kit;
	static Path target;
	static List<String> failures = new ArrayList<>();
	static int commandIndex;

	public static void main(String[] args) throws Exception {
		kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
		target = Files.createTempDirectory("guardrails-pmd-ratchet-");
		System.out.println("Fixture: " + target);
		Files.createDirectories(target.resolve(".mvn/wrapper"));
		for (String name : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
			Files.copy(kit.resolve("fixtures/nullaway").resolve(name), target.resolve(name));
		}
		target.resolve("mvnw").toFile().setExecutable(true);
		Files.createDirectories(target.resolve("config/pmd"));
		Files.copy(kit.resolve("configs/pmd/ruleset.xml"), target.resolve("config/pmd/ruleset.xml"));
		Files.writeString(target.resolve("pom.xml"), """
			<project xmlns="http://maven.apache.org/POM/4.0.0">
			<modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
			<artifactId>ratchet-parent</artifactId><version>1</version><packaging>pom</packaging>
			<modules><module>app</module></modules>
			<properties><java.version>17</java.version><maven.compiler.release>17</maven.compiler.release>
			%s</properties>
			<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
			<artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin>
			%s</plugins></build></project>
			""".formatted(Files.readString(kit.resolve("maven/properties.xml")), Files.readString(kit.resolve("maven/pmd.plugin.xml"))));
		Files.createDirectories(target.resolve("app/src/main/java/fx"));
		Files.writeString(target.resolve("app/pom.xml"), """
			<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId>
			<artifactId>ratchet-parent</artifactId><version>1</version></parent><artifactId>app</artifactId>
			<properties><pmd.maxAllowedViolations>1</pmd.maxAllowedViolations></properties></project>
			""");
		Path source = target.resolve("app/src/main/java/fx/Example.java");
		String original = """
			package fx;
			public class Example {
			    public int value(int a, int b, int c, int d, int e, int f, int g, int h) {
			        return a + b + c + d + e + f + g + h;
			    }
			}
			""";
		Files.writeString(source, original);
		Result baseline = maven("verify");
		check(baseline.exit == 0 && Files.readString(target.resolve("app/target/pmd.xml")).contains("ExcessiveParameterList"),
				"verify permits the one measured PMD violation", baseline);
		Result named = maven("-pl", "app", "pmd:check@pmd-gate");
		check(named.exit == 0, "named PMD check uses the module allowance", named);
		Result plainPmd = maven("-pl", "app", "pmd:check");
		check(plainPmd.exit != 0 && plainPmd.output.contains("PMD"), "plain PMD check remains strict zero", plainPmd);
		Result strict = maven("-pl", "app", "-Dpmd.maxAllowedViolations=0", "pmd:check@pmd-gate");
		check(strict.exit != 0 && strict.output.contains("PMD"), "lowering the PMD allowance fails the existing violation", strict);
		Files.writeString(source, Falsify.plant("pmd", original));
		Result pmd = maven("verify");
		check(pmd.exit != 0 && Files.readString(target.resolve("app/target/pmd.xml")).contains("guardrailsPlantedViolation"),
				"new PMD violations exceed the ratchet under verify", pmd);
		Files.writeString(source, Falsify.plant("cpd", original));
		Result cpd = maven("verify");
		check(cpd.exit != 0 && cpd.output.contains("CPD") && Files.readString(target.resolve("app/target/cpd.xml")).contains("guardrailsPlantedCopy"),
				"verify rejects duplication while the PMD count stays within its allowance", cpd);
		Result direct = maven("-pl", "app", "pmd:cpd-check");
		check(direct.exit != 0 && direct.output.contains("CPD"), "plain CPD check rejects duplication with the PMD allowance", direct);
		Result raised = maven("-pl", "app", "-Dpmd.maxAllowedViolations=999", "pmd:cpd-check");
		check(raised.exit != 0 && raised.output.contains("CPD"), "raising the PMD property cannot weaken a direct CPD check", raised);
		Result bound = maven("-pl", "app", "pmd:cpd-check@cpd-gate");
		check(bound.exit != 0 && bound.output.contains("CPD"), "named CPD execution rejects duplication", bound);
		Files.writeString(source, original);
		for (String gate : List.of("pmd", "cpd")) {
			Result proof = falsify(gate);
			check(proof.exit == 0 && proof.output.contains("falsify " + gate + ": OK") && Files.readString(source).equals(original),
					"falsify " + gate + " catches its plant and restores source under the ratchet", proof);
		}
		Result restored = maven("verify");
		check(restored.exit == 0, "restored verify is green with the accepted PMD violation", restored);
		if (!failures.isEmpty()) {
			throw new AssertionError("Failed PMD/CPD checks: " + failures + "; fixture " + target);
		}
		System.out.println("PASS PMD ratchet isolation");
	}

	static Result maven(String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of(Util.mvnw(target), "-q", "-B", "-Dstyle.color=never", "-DskipTests"));
		command.addAll(List.of(args));
		return run(command);
	}

	static Result falsify(String gate) throws Exception {
		return run(List.of(kit.resolve(System.getProperty("os.name").startsWith("Windows") ? "jbang.cmd" : "jbang").toString(),
				kit.resolve("scripts/Falsify.java").toString(), target.toString(), gate));
	}

	static Result run(List<String> command) throws Exception {
		Process process = new ProcessBuilder(command).directory(target.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		Result result = new Result(process.waitFor(), output);
		Files.writeString(target.resolve("command-" + commandIndex++ + ".log"), String.join(" ", command) + "\nexit=" + result.exit + "\n" + output);
		return result;
	}

	static void check(boolean condition, String name, Result result) {
		if (condition) {
			System.out.println("PASS " + name);
		}
		else {
			failures.add(name);
			System.out.println("FAIL " + name + "; exit=" + result.exit + "\n" + result.output);
		}
	}
}
