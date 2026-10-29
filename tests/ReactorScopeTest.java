///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../scripts/Util.java

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real Maven regressions for reactor dependencies, nested projects and decision compatibility. */
public class ReactorScopeTest {
    record Result(int exit, String output) {}
    static Path kit, root;
    static int sequence;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        root = Files.createTempDirectory("guardrails-reactor-");
        System.out.println("Evidence: " + root);
        Path sibling = fixture("siblings", true, false);
        decisions(sibling, "TEST_SUPPORT_MODULES: exclude z-lib\n");
        check(script(sibling, "JacocoMeasure.java"), "fresh sibling coverage", "| app |", "STOP JACOCO_FLOORS");
        check(script(sibling, "ArchUnitMeasure.java"), "fresh sibling cycle measurement", "| app |");
        for (String gate : List.of("pmd", "cpd", "errorprone", "spotbugs", "jacoco", "archunit")) {
            String original = Files.readString(sibling.resolve("app/src/main/java/fx/app/App.java"));
            check(script(sibling, "Falsify.java", gate), "fresh sibling falsify " + gate, "falsify " + gate + ": OK");
            require(Files.readString(sibling.resolve("app/src/main/java/fx/app/App.java")).equals(original), gate + " restores source", "");
            Path config = sibling.resolve(".mvn/maven.config");
            Path cycles = sibling.resolve("app/src/test/java/fx/NoCyclesTest.java");
            String originalCycles = Files.readString(cycles);
            if (gate.equals("archunit")) Files.writeString(cycles, originalCycles.replace(".check(CLASSES)", ""));
            else Files.writeString(config, gate.equals("errorprone") ? "-Derrorprone.extraArgs=-XepDisableAllChecks\n" : "-D" + gate + ".skip=true\n");
            try {
                Result off = script(sibling, "Falsify.java", gate);
                require(off.exit != 0 && off.output.contains("falsify " + gate + ": FAILED"), "disabled " + gate + " cannot pass proof", off.output);
                require(Files.readString(sibling.resolve("app/src/main/java/fx/app/App.java")).equals(original), "disabled " + gate + " restores source", "");
            } finally {
                Files.deleteIfExists(config);
                Files.writeString(cycles, originalCycles);
            }
        }
        // Install an intentionally stale sibling, then change its API and behavior in this checkout.
        Path lib = sibling.resolve("z-lib/src/main/java/fx/lib/Lib.java");
        Files.writeString(lib, "package fx.lib; public class Lib { public static int oldValue() { return -1; } }\n");
        check(maven(sibling, "-pl", "z-lib", "-am", "-DskipTests", "install"), "seed stale sibling cache");
        Files.writeString(lib, "package fx.lib; public class Lib { public static int value() { return 42; } }\n");
        check(maven(sibling, "clean"), "clean before stale-cache proof");
        check(script(sibling, "JacocoMeasure.java"), "working-tree sibling beats stale installed API", "| app |");
        String validLib = Files.readString(lib);
        Files.writeString(lib, validLib.replace("return 42;", "new IllegalArgumentException(\"dropped\"); return 42;"));
        check(script(sibling, "JacocoMeasure.java"), "coverage ignores unrelated upstream Error Prone violation", "| app |");
        Result upstream = script(sibling, "Falsify.java", "errorprone");
        require(upstream.exit != 0 && !upstream.output.contains("falsify errorprone: OK"), "upstream checker failure cannot prove selected plant", upstream.output);
        Files.writeString(lib, validLib);

        Path nested = fixture("nested", false, true);
        decisions(nested, "TEST_SUPPORT_MODULES: exclude test-support\n");
        List<String> paths = Util.modules(nested).stream().map(Util.Module::path).toList();
        require(paths.equals(List.of("app", "test-support")), "inventory excludes standalone nested project", paths.toString());
        check(script(nested, "JacocoMeasure.java"), "nested standalone cannot enter Maven selection", "| app |");
        Result selection = maven(nested, "-pl", "tools/docgen", "validate");
        require(selection.exit != 0 && selection.output.contains("Could not find the selected project in the reactor"), "Maven confirms nested project is outside reactor", selection.output);
        // Put a standalone project before the reactor in lexical order: falsify must not plant there.
        Files.move(nested.resolve("tools"), nested.resolve("00-tools"));
        check(script(nested, "Falsify.java", "pmd"), "falsify ignores standalone sources", "planting in app/", "falsify pmd: OK");
        Files.move(nested.resolve("00-tools"), nested.resolve("tools"));

        decisions(nested, "TEST_SUPPORT_MODULES: exclude tools/docgen\n");
        check(script(nested, "PmdMeasure.java"), "legacy non-test exclusion does not answer test-support decision", "STOP TEST_SUPPORT_MODULES (decide)");
        decisions(nested, "EXCLUDED_MODULES: app\n");
        Result independent = script(nested, "PmdMeasure.java");
        check(independent, "general exclusion is independent", "Modules measured: test-support", "STOP TEST_SUPPORT_MODULES (decide)");
        decisions(nested, "TEST_SUPPORT_MODULES: exclude test-support\n");
        Result legacy = script(nested, "PmdMeasure.java");
        check(legacy, "legacy test-support exclusion still works", "Modules measured: app");
        require(!legacy.output.contains("STOP TEST_SUPPORT_MODULES"), "legacy test-support answer is retained", legacy.output);
        decisions(nested, "TEST_SUPPORT_MODULES: gate\nEXCLUDED_MODULES: app\n");
        Result gate = script(nested, "PmdMeasure.java");
        check(gate, "gate decision coexists with general exclusion", "Modules measured: test-support");
        require(!gate.output.contains("STOP TEST_SUPPORT_MODULES"), "explicit gate answer is retained", gate.output);
        check(script(nested, "Falsify.java", "pmd"), "falsify honors general exclusion", "planting in test-support/", "falsify pmd: OK");
        decisions(nested, "EXCLUDED_MODULES: app, test-support\n");
        Result empty = script(nested, "JacocoMeasure.java");
        check(empty, "empty selection exits without Maven or floors", "nothing measured");
        require(!empty.output.contains("Running:") && !empty.output.contains("STOP JACOCO_FLOORS"), "empty selection cannot run entire reactor", empty.output);

        // A real compiler failure before any test must remain visible to the caller.
        decisions(nested, "TEST_SUPPORT_MODULES: gate\nEXCLUDED_MODULES: tools/docgen\n");
        Files.writeString(nested.resolve("app/src/main/java/fx/app/App.java"), "package fx.app; public class App { MissingType broken; }\n");
        Result broken = script(nested, "JacocoMeasure.java");
        require(broken.exit != 0 && broken.output.contains("cannot find symbol") && !broken.output.contains("STOP JACOCO_FLOORS"), "pre-test Maven compiler error is surfaced without floors", broken.output);
        for (String script : List.of("ErrorProneMeasure.java", "SpotBugsMeasure.java", "ArchUnitMeasure.java")) {
            Result failed = script(nested, script);
            require(failed.exit != 0 && failed.output.contains("cannot find symbol"), script + " retains Maven compiler failure", failed.output);
        }

        Path profiles = fixture("profiles", false, true);
        decisions(profiles, "TEST_SUPPORT_MODULES: exclude test-support\n");
        String pom = Files.readString(profiles.resolve("pom.xml"));
        Files.writeString(profiles.resolve("pom.xml"), pom.replace("<module>test-support</module>", "")
                .replace("<profiles>", "<profiles><profile><id>support</id><activation><activeByDefault>true</activeByDefault></activation><modules><module>test-support</module></modules></profile>"));
        require(Util.modules(profiles, "!errorprone").stream().map(Util.Module::path).toList().equals(List.of("app", "test-support")), "active profile modules use Maven model", "");
        require(Util.modules(profiles, "!support,!errorprone").stream().map(Util.Module::path).toList().equals(List.of("app")), "inactive profile sources are not reactor members", "");
        check(script(profiles, "JacocoMeasure.java"), "profile-aware selection measures successfully", "| app |");
        if (!failures.isEmpty()) throw new AssertionError("Failed: " + failures + "; evidence " + root);
        System.out.println("PASS reactor scope regressions");
    }

    static Path fixture(String name, boolean sibling, boolean nested) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.resolve(".mvn/wrapper"));
        for (String file : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            Files.copy(kit.resolve("fixtures/nullaway").resolve(file), p.resolve(file));
        }
        p.resolve("mvnw").toFile().setExecutable(true);
        Files.copy(kit.resolve("maven/jvm.config"), p.resolve(".mvn/jvm.config"));
        write(p, "config/errorprone/reactor-ignorable-results.txt", "");
        for (String file : List.of("pmd/ruleset.xml", "spotbugs/spotbugs-include.xml", "spotbugs/spotbugs-exclude.xml")) {
            Files.createDirectories(p.resolve("config").resolve(file).getParent());
            Files.copy(kit.resolve("configs").resolve(file), p.resolve("config").resolve(file));
        }
        String group = "fixture.reactor.g" + UUID.randomUUID().toString().replace("-", "");
        String plugins = Files.readString(kit.resolve("maven/pmd.plugin.xml"))
                + Files.readString(kit.resolve("maven/spotbugs.plugin.xml"))
                + Files.readString(kit.resolve("maven/jacoco.plugin.xml"));
        Files.writeString(p.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>%s</groupId><artifactId>parent</artifactId><version>1-SNAPSHOT</version><packaging>pom</packaging>
            <modules><module>app</module>%s</modules>
            <properties><java.version>17</java.version><maven.compiler.release>17</maven.compiler.release>%s
            <jacoco.minimum.line>0.90</jacoco.minimum.line><jacoco.minimum.branch>0</jacoco.minimum.branch>
            </properties><build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.3</version></plugin>
            %s</plugins></build><profiles>%s</profiles></project>
            """.formatted(group, sibling ? "<module>z-lib</module>" : "<module>test-support</module>",
                Files.readString(kit.resolve("maven/properties.xml")), plugins, Files.readString(kit.resolve("maven/errorprone.profile.xml"))));
        String parent = "<parent><groupId>" + group + "</groupId><artifactId>parent</artifactId><version>1-SNAPSHOT</version></parent>";
        write(p, "app/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent + "<artifactId>app</artifactId><dependencies>"
                + (sibling ? "<dependency><groupId>" + group + "</groupId><artifactId>z-lib</artifactId><version>1-SNAPSHOT</version></dependency>" : "")
                + "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>"
                + "<dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit</artifactId><version>1.4.1</version><scope>test</scope></dependency>"
                + "</dependencies></project>");
        write(p, "app/src/main/java/fx/app/App.java", "package fx.app;\npublic class App { public int value() { return " + (sibling ? "fx.lib.Lib.value()" : "42") + "; } }\n");
        write(p, "app/src/main/java/fx/other/Other.java", "package fx.other; public class Other { }\n");
        write(p, "app/src/test/java/fx/app/AppTest.java", "package fx.app; class AppTest { @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals(42, new App().value()); new fx.other.Other(); } }\n");
        write(p, "app/src/test/java/fx/NoCyclesTest.java", Files.readString(kit.resolve("configs/archunit/NoCyclesTest.java.tmpl")).replace("{{ROOT_PACKAGE}}", "fx"));
        if (sibling) {
            write(p, "z-lib/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent + "<artifactId>z-lib</artifactId></project>");
            write(p, "z-lib/src/main/java/fx/lib/Lib.java", "package fx.lib; public class Lib { public static int value() { return 42; } }\n");
        } else {
            write(p, "test-support/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent + "<artifactId>test-support</artifactId></project>");
            write(p, "test-support/src/main/java/fx/Support.java", "package fx; public class Support { }\n");
        }
        if (nested) {
            write(p, "tools/docgen/pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>docgen</artifactId><version>1</version></project>");
            write(p, "tools/docgen/src/main/java/fx/Docgen.java", "package fx; public class Docgen { }\n");
        }
        return p;
    }

    static void decisions(Path p, String value) throws Exception { write(p, "config/guardrails/decisions.md", value); }
    static void write(Path p, String file, String value) throws Exception { Files.createDirectories(p.resolve(file).getParent()); Files.writeString(p.resolve(file), value); }
    static Result script(Path p, String script, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(kit.resolve("jbang").toString(), kit.resolve("scripts").resolve(script).toString(), p.toString()));
        command.addAll(List.of(args)); return run(p, command);
    }
    static Result maven(Path p, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Util.mvnw(p), "-B", "-q", "-P", "!errorprone", "-Dstyle.color=never"));
        command.addAll(List.of(args)); return run(p, command);
    }
    static Result run(Path p, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(p.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Result r = new Result(process.waitFor(), output);
        Files.writeString(root.resolve("command-" + sequence++ + ".log"), String.join(" ", command) + "\nexit=" + r.exit + "\n" + output);
        return r;
    }
    static void check(Result r, String name, String... expected) {
        require(r.exit == 0 && List.of(expected).stream().allMatch(r.output::contains), name, "exit=" + r.exit + "\n" + r.output);
    }
    static void require(boolean ok, String name, String output) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) { failures.add(name); System.out.println(output); }
    }
}
