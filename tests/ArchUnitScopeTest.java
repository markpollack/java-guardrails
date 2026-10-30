///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real Maven measurements and copied-template checks for package scope and empty imports. */
public class ArchUnitScopeTest extends ReactorScopeTest {
    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        root = Files.createTempDirectory("guardrails-archunit-scope-");
        System.out.println("Evidence: " + root);
        Path single = project("single");
        write(single, "src/main/java/fx/A.java", "package fx; public class A { B b; }\n");
        write(single, "src/main/java/fx/B.java", "package fx; public class B {}\n");
        check(script(single, "ArchUnitMeasure.java"), "single package measures without a global diagnostic override", "| . | 2 | fx | 1 | 0 | 0 |", "Both rules pass");
        check(maven(single, "clean", "verify"), "copied template accepts a nonempty acyclic single package");
        check(script(single, "Falsify.java", "archunit"), "single-package falsifier catches a class cycle", "falsify archunit: OK");
        restored(single, "positive plant restores sources");

        Path template = single.resolve("src/test/java/fx/NoCyclesTest.java");
        String original = Files.readString(template);
        Files.writeString(template, original.replace(".check(CLASSES)", ""));
        Result disabled = script(single, "Falsify.java", "archunit");
        require(disabled.exit() != 0 && disabled.output().contains("falsify archunit: FAILED"), "disabled cycle rules cannot pass falsification", disabled.output());
        restored(single, "negative plant restores sources");
        Files.writeString(template, original);

        write(single, "src/main/java/fx/B.java", "package fx; public class B { A a; }\n");
        check(script(single, "ArchUnitMeasure.java"), "single-package class cycle is measured and reaches decision", "| . | 2 | fx | 1 | 0 | 1 |", "class cycle", "STOP ARCHUNIT_CYCLES (decide)");
        fails(maven(single, "clean", "test", "-Dtest=NoCyclesTest#noClassCycles"), "class rule rejects a cycle within one package", "Cycle detected", "fx.A", "fx.B");

        Path multi = project("root-and-child");
        write(multi, "src/main/java/fx/A.java", "package fx; public class A { fx.child.B b; }\n");
        write(multi, "src/main/java/fx/child/B.java", "package fx.child; public class B { fx.A a; }\n");
        check(script(multi, "ArchUnitMeasure.java"), "package cycles include the root package as well as children", "| . | 2 | fx | 2 | 1 | 1 |", "package cycle", "STOP ARCHUNIT_CYCLES (decide)");
        fails(maven(multi, "clean", "test", "-Dtest=NoCyclesTest#noPackageCycles"), "package rule independently rejects root-to-child cycle", "Cycle detected", "fx.child");
        write(multi, "src/main/java/fx/child/B.java", "package fx.child; public class B {}\n");
        check(maven(multi, "clean", "verify"), "acyclic multiple packages pass both copied rules");
        check(script(multi, "Falsify.java", "archunit"), "multiple-package falsifier still catches a cycle", "falsify archunit: OK");

        // Keep compiled test classes but remove all main bytecode. Even the diagnostic override
        // must not turn an empty import into proof that a gate ran.
        try (var paths = Files.walk(multi.resolve("target/classes"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) Files.delete(path);
        }
        fails(maven(multi, "surefire:test", "-Dtest=NoCyclesTest", "-DargLine=-Darchunit.archRule.failOnEmptyShould=false"),
                "copied importsSomething rejects empty imports despite global override", "importsSomething", "no classes imported");

        for (boolean resources : List.of(false, true)) {
            Path empty = project(resources ? "empty-classes" : "missing-classes");
            write(empty, "src/main/java/fx/A.java", "package fx; public class A {}\n");
            write(empty, ".mvn/maven.config", "-Dmaven.main.skip=true\n");
            if (resources) write(empty, "src/main/resources/resource.txt", "resource without bytecode\n");
            Result result = script(empty, "ArchUnitMeasure.java");
            require(result.exit() != 0 && result.output().contains("no classes imported") && !result.output().contains("Both rules pass"),
                    (resources ? "empty" : "missing") + " main-class import stops measurement", result.output());
        }
        if (!failures.isEmpty()) throw new AssertionError("Failed: " + failures + "; evidence " + root);
        System.out.println("PASS ArchUnit scope regressions");
    }

    static Path project(String name) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.resolve(".mvn/wrapper"));
        for (String f : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"))
            Files.copy(kit.resolve("fixtures/nullaway").resolve(f), p.resolve(f));
        p.resolve("mvnw").toFile().setExecutable(true);
        write(p, "pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.archunit</groupId>
            <artifactId>%s</artifactId><version>1</version>
            <properties><maven.compiler.release>17</maven.compiler.release></properties>
            <dependencies>
            <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit</artifactId><version>1.4.1</version><scope>test</scope></dependency>
            <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
            </dependencies><build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.3</version></plugin>
            </plugins></build></project>
            """.formatted(name));
        write(p, "src/test/java/fx/NoCyclesTest.java", Files.readString(kit.resolve("configs/archunit/NoCyclesTest.java.tmpl")).replace("{{ROOT_PACKAGE}}", "fx"));
        return p;
    }
    static void fails(Result r, String name, String... expected) {
        require(r.exit() != 0 && List.of(expected).stream().allMatch(r.output()::contains), name, r.output());
    }
    static void restored(Path p, String name) throws Exception {
        require(!Files.exists(p.resolve("src/main/java/fx/GuardrailsPlantedA.java"))
                && !Files.exists(p.resolve("src/main/java/fx/GuardrailsPlantedB.java"))
                && Files.readString(p.resolve("src/main/java/fx/A.java")).equals("package fx; public class A { B b; }\n")
                && Files.readString(p.resolve("src/main/java/fx/B.java")).equals("package fx; public class B {}\n"), name, "");
    }
}
