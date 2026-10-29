///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real Maven coverage completeness, including code exercised only by a downstream consumer. */
public class JacocoCompletenessTest extends ReactorScopeTest {
    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        root = Files.createTempDirectory("guardrails-jacoco-completeness-");
        System.out.println("Evidence: " + root);
        Path p = root.resolve("reactor");
        Files.createDirectories(p.resolve(".mvn/wrapper"));
        for (String f : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"))
            Files.copy(kit.resolve("fixtures/nullaway").resolve(f), p.resolve(f));
        p.resolve("mvnw").toFile().setExecutable(true);
        write(p, "pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.coverage</groupId>
            <artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
            <modules><module>covered</module><module>no-tests</module><module>opt-in-it</module>
            <module>agent-tck</module><module>api</module><module>default-api</module></modules>
            <properties><maven.compiler.release>17</maven.compiler.release></properties>
            <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
            <version>5.11.4</version><scope>test</scope></dependency></dependencies>
            <build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.3</version></plugin>
            </plugins></build></project>
            """);
        for (String mod : List.of("covered", "no-tests", "opt-in-it", "agent-tck", "api", "default-api")) {
            write(p, mod + "/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture.coverage</groupId>"
                    + "<artifactId>parent</artifactId><version>1</version></parent><artifactId>" + mod + "</artifactId>"
                    + (mod.equals("covered") ? "<dependencies><dependency><groupId>fixture.coverage</groupId><artifactId>agent-tck</artifactId><version>1</version><scope>test</scope></dependency></dependencies>" : "")
                    + "</project>");
            String cls = className(mod);
            String source = mod.equals("api") ? "public interface Api { int value(); }"
                    : mod.equals("default-api") ? "public interface DefaultApi { default int value() { return 42; } }"
                    : "public class " + cls + " { public int value() { return 42; } }";
            write(p, mod + "/src/main/java/fx/" + cls + ".java", "package fx; " + source);
        }
        ownTest(p, "covered", "CoveredTest", "new AgentTck().value();");
        ownTest(p, "opt-in-it", "OptInIT", ""); // compiled, but not selected by default Surefire
        Result scope = script(p, "JacocoMeasure.java");
        check(scope, "coverage asks test-support scope first", "STOP TEST_SUPPORT_MODULES (decide)", "agent-tck");
        require(!scope.output().contains("Running:") && !scope.output().contains("STOP JACOCO_FLOORS"), "scope decision precedes build and floors", scope.output());
        check(script(p, "Assess.java", "--quick"), "assessment asks scope before installing first gate", "STOP TEST_SUPPORT_MODULES (decide)");
        decisions(p, "TEST_SUPPORT_MODULES: gate\n");
        Result missing = script(p, "JacocoMeasure.java");
        missing(missing, "no-tests", "no tests at all");
        missing(missing, "opt-in-it", "opt-in IT is not default execution");
        missing(missing, "agent-tck", "consumer tests do not cover support module locally");
        missing(missing, "default-api", "default interface methods are executable");
        require(missing.output().contains("\napi: no executable code") && !missing.output().contains("    api: missing"), "abstract-only interface explicitly needs no floor", missing.output());
        require(!Files.exists(p.resolve("agent-tck/target/site/jacoco/jacoco.xml")) && Files.exists(p.resolve("covered/target/site/jacoco/jacoco.xml")), "consumer ran while support has no own report", "");
        require(!missing.output().contains("floor proposed") && !missing.output().contains("STOP JACOCO_FLOORS"), "no partial floor proposal", missing.output());
        decisions(p, "TEST_SUPPORT_MODULES: gate\nJACOCO_FLOORS: covered 98/98\n");
        missing(script(p, "JacocoMeasure.java"), "no-tests", "existing floors cannot bypass missing reports");
        decisions(p, "TEST_SUPPORT_MODULES: exclude agent-tck\nEXCLUDED_MODULES: no-tests, opt-in-it, default-api\n");
        Result excluded = script(p, "JacocoMeasure.java");
        check(excluded, "explicit exclusions restore complete scoped floors", "STOP JACOCO_FLOORS (notify)", "covered 98/98");
        require(!excluded.output().contains("JACOCO_MISSING_REPORTS") && !excluded.output().contains("TEST_SUPPORT_MODULES (decide)"), "legacy support exclusion remains an answer", excluded.output());
        decisions(p, "TEST_SUPPORT_MODULES: gate\n");
        for (String mod : List.of("no-tests", "opt-in-it", "agent-tck", "default-api")) ownTest(p, mod, className(mod) + "Test", "");
        Result complete = script(p, "JacocoMeasure.java");
        check(complete, "own tests produce complete floors including gated test support", "STOP JACOCO_FLOORS (notify)", "agent-tck 98/98", "no-tests 98/98", "opt-in-it 98/98", "default-api 98/98");
        require(!complete.output().contains("| api |"), "no-code module receives no invented floor", complete.output());
        decisions(p, "TEST_SUPPORT_MODULES: gate\nJACOCO_FLOORS: covered 90/90\n");
        check(script(p, "JacocoMeasure.java"), "partial saved floors require a completion decision", "STOP JACOCO_FLOORS (decide)", "Missing recorded floors:", "covered 90/90", "agent-tck 98/98");
        decisions(p, "TEST_SUPPORT_MODULES: gate\nEXCLUDED_MODULES: agent-tck, covered, no-tests, opt-in-it, default-api\n");
        Result noCode = script(p, "JacocoMeasure.java");
        check(noCode, "only non-executable code needs no floors", "No selected modules with executable code");
        require(!noCode.output().contains("STOP JACOCO_FLOORS"), "no-code selection has no floor stop", noCode.output());
        decisions(p, "TEST_SUPPORT_MODULES: gate\n");
        write(p, "no-tests/src/main/java/fx/NoTests.java", "package fx; public class NoTests { MissingType broken; }");
        Result broken = script(p, "JacocoMeasure.java");
        require(broken.exit() != 0 && broken.output().contains("cannot find symbol") && !broken.output().contains("STOP JACOCO_FLOORS"), "Maven compiler diagnostics preserved", broken.output());
        if (!failures.isEmpty()) throw new AssertionError("Failed: " + failures + "; evidence " + root);
        System.out.println("PASS JaCoCo completeness regressions");
    }

    static void missing(Result r, String module, String name) {
        require(r.exit() != 0 && r.output().contains("STOP JACOCO_MISSING_REPORTS (decide)")
                && r.output().contains(module + ": missing jacoco.xml"), name, r.output());
    }
    static String className(String module) {
        return switch (module) { case "no-tests" -> "NoTests"; case "opt-in-it" -> "OptIn";
            case "agent-tck" -> "AgentTck"; case "default-api" -> "DefaultApi";
            case "api" -> "Api"; default -> "Covered"; };
    }
    static void ownTest(Path p, String module, String name, String extra) throws Exception {
        String instance = "new " + className(module) + "()" + (module.equals("default-api") ? " {}" : "");
        write(p, module + "/src/test/java/fx/" + name + ".java", "package fx; class " + name
                + " { @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals(42, "
                + instance + ".value()); " + extra + " } }");
    }
}
