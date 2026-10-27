///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../scripts/Util.java
//SOURCES ../scripts/NullAwaySupport.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Integration proof using the shipped Maven slice and real compiler, including negative controls. */
public class NullAwayTest {

	record Result(int exit, String output) {
	}

	static Path kit;
	static Path target;

	public static void main(String[] args) throws Exception {
		kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
		target = Files.createTempDirectory("guardrails-nullaway-");
		System.out.println("Fixture: " + target);
		Files.createDirectories(target.resolve(".mvn/wrapper"));
		for (String name : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
			Files.copy(kit.resolve("fixtures/nullaway").resolve(name), target.resolve(name));
		}
		target.resolve("mvnw").toFile().setExecutable(true);
		Files.copy(kit.resolve("maven/jvm.config"), target.resolve(".mvn/jvm.config"));
		String profile = Files.readString(kit.resolve("maven/errorprone.profile.xml"))
				.replace(" -XepOpt:CheckReturnValue:Packages=reactor.core.publisher", "")
				.replace(" -XepOpt:CheckReturnValue:ApiExclusionList=${maven.multiModuleProjectDirectory}/config/errorprone/reactor-ignorable-results.txt", "");
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0">
			<modelVersion>4.0.0</modelVersion>
			<groupId>fixture</groupId><artifactId>nullaway</artifactId><version>1</version>
			<properties><maven.compiler.release>17</maven.compiler.release>
			%s</properties>
			<dependencies><dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId>
			<version>1.0.0</version></dependency></dependencies>
			<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
			<artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version>
			</plugin></plugins></build>
			<profiles>%s</profiles></project>
			""".formatted(Files.readString(kit.resolve("maven/properties.xml")), profile);
		Files.writeString(target.resolve("pom.xml"), pom);
		Path pkg = target.resolve("src/main/java/fx");
		Files.createDirectories(pkg.resolve("child"));
		Files.writeString(pkg.resolve("package-info.java"), "@org.jspecify.annotations.NullMarked\npackage fx;\n");
		Path example = pkg.resolve("Example.java");
		String broken = "package fx; public final class Example { public String value() { return null; } }\n";
		String green = broken.replace("return null", "return \"ok\"");
		Files.writeString(example, broken);
		// This child package is deliberately unmarked: package annotations are not recursive.
		Files.writeString(pkg.resolve("child/Unmarked.java"),
				"package fx.child; public final class Unmarked { public String value() { return null; } }\n");
		Result measured = script("NullAwayMeasure.java");
		require(measured.exit == 0 && measured.output.contains("NullAway findings: 1"), "one unique report-mode finding", measured);
		require(measured.output.contains("1 of 2 main-source packages"), "only the exact marked package counted", measured);
		Result assessment = script("Assess.java", "--quick");
		require(assessment.exit == 0 && assessment.output.contains("processor configured; scope needs proof")
				&& assessment.output.contains("Record as:  GATE_ORDER: errorprone, spotbugs, pmd, archunit, jacoco"),
				"assessment shows optional adoption without treating processor presence as enforcement", assessment);
		StringBuilder build = new StringBuilder();
		int exit = NullAwaySupport.compile(target, ".", false, build);
		require(exit != 0 && build.toString().contains("[NullAway]"), "real nullness violation fails compile", new Result(exit, build.toString()));
		Files.writeString(example, green);
		Result zero = script("NullAwayMeasure.java");
		require(zero.exit == 0 && zero.output.contains("NullAway findings: 0"), "fixed source measures zero", zero);
		Result proof = script("Falsify.java", "nullaway", "--module", ".", "--package", "fx");
		require(proof.exit == 0 && proof.output.contains("falsify nullaway: OK"), "marked scope catches planted nullable return", proof);
		require(Files.readString(example).equals(green) && !Files.exists(pkg.resolve("GuardrailsNullAwayProbe.java")), "source restored after plant", proof);
		Files.writeString(target.resolve("pom.xml"), pom.replace("-Xep:NullAway:ERROR", "-Xep:NullAway:OFF"));
		Result off = script("Falsify.java", "nullaway");
		require(off.exit != 0 && off.output.contains("falsify nullaway: FAILED"), "disabled checker cannot pass proof", off);
		require(!Files.exists(pkg.resolve("GuardrailsNullAwayProbe.java")), "negative control restores source", off);
		String selective = pom.replaceAll("(?s)<arg>-Xplugin:ErrorProne.*?</arg>",
				"<arg>-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true \\${errorprone.extraArgs}</arg>");
		Files.writeString(target.resolve("pom.xml"), selective);
		Files.writeString(example, green.replace("public String value()", "public void unrelated() { new IllegalArgumentException(\"dropped\"); } public String value()"));
		StringBuilder selectedOutput = new StringBuilder();
		int selectedExit = NullAwaySupport.compile(target, ".", false, selectedOutput);
		require(selectedExit == 0, "NullAway-only compile can precede other Error Prone cleanup", new Result(selectedExit, selectedOutput.toString()));
		Result selectiveProof = script("Falsify.java", "nullaway");
		require(selectiveProof.exit == 0, "selective checker still catches the planted nullness violation", selectiveProof);
		Files.writeString(target.resolve("pom.xml"), pom);
		Files.writeString(example, green);
		Files.writeString(pkg.resolve("GuardrailsNullAwayProbe.java"), "target-owned sentinel");
		Result collision = script("Falsify.java", "nullaway");
		require(collision.exit != 0 && Files.readString(pkg.resolve("GuardrailsNullAwayProbe.java")).equals("target-owned sentinel"),
				"existing target file preserved", collision);
		Files.delete(pkg.resolve("GuardrailsNullAwayProbe.java"));
		Files.writeString(pkg.resolve("package-info.java"), "// @org.jspecify.annotations.NullMarked is only a comment\npackage fx;\n");
		Result unmarked = script("Falsify.java", "nullaway");
		require(unmarked.exit != 0 && unmarked.output.contains("NOT PROVEN"), "comment-only marker cannot pass proof", unmarked);
		require(!Files.exists(pkg.resolve("GuardrailsNullAwayProbe.java")), "unmarked scope left untouched", unmarked);
		Files.writeString(example, "package fx; public class Example { broken Java syntax }\n");
		Result failed = script("NullAwayMeasure.java");
		require(failed.exit != 0 && failed.output.contains("findings are partial"), "broken build never reports success", failed);
		Files.writeString(pkg.resolve("package-info.java"), "@org.jspecify.annotations.NullMarked\npackage fx;\n");
		Result unrelated = script("Falsify.java", "nullaway");
		require(unrelated.exit != 0 && unrelated.output.contains("falsify nullaway: FAILED"), "unrelated compile failure cannot pass proof", unrelated);
		require(!Files.exists(pkg.resolve("GuardrailsNullAwayProbe.java")), "unrelated failure restores source", unrelated);
		System.out.println("PASS NullAway integration proof; fixture retained at " + target);
	}

	static Result script(String script, String... options) throws Exception {
		List<String> command = new ArrayList<>(List.of(kit.resolve(System.getProperty("os.name").startsWith("Windows") ? "jbang.cmd" : "jbang").toString(),
				kit.resolve("scripts").resolve(script).toString(), target.toString()));
		command.addAll(List.of(options));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		return new Result(process.waitFor(), output);
	}

	static void require(boolean condition, String name, Result result) {
		if (!condition) {
			throw new AssertionError(name + " (exit " + result.exit + ")\n" + result.output);
		}
		System.out.println("PASS " + name);
	}
}
