///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Scope decisions must govern cost, test and concurrency facts consistently. */
public class AssessScopeTest extends ReactorScopeTest {
    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        root = Files.createTempDirectory("guardrails-assess-scope-");
        write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>fx</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties><modules><module>app</module><module>test-support</module></modules></project>");
        for (String module : List.of("app", "test-support")) write(root, module + "/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fx</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>" + module + "</artifactId></project>");
        write(root, "app/src/main/java/fx/App.java", "package fx; public class App { int value() { return 1; } }");
        StringBuilder body = new StringBuilder("package fx; public class Support { synchronized int work(int value) { ");
        for (int i = 0; i < 20; i++) body.append("if (value == ").append(i).append(") { value++; }");
        body.append("return value; } }");
        write(root, "test-support/src/main/java/fx/Support.java", body.toString());
        write(root, "test-support/src/test/java/fx/SupportTest.java", "package fx; public class SupportTest {}");
        for (String decision : List.of("", "TEST_SUPPORT_MODULES: exclude test-support", "TEST_SUPPORT_MODULES: gate", "TEST_SUPPORT_MODULES: gate\nEXCLUDED_MODULES: test-support")) {
            write(root, "config/guardrails/decisions.md", decision + "\n");
            Result r = script(root, "Assess.java");
            boolean gate = decision.equals("TEST_SUPPORT_MODULES: gate");
            check(r, "scope " + decision.replace('\n', ';'), "Measuring PMD and CPD over " + (gate ? 2 : 1) + " module(s)",
                    gate ? "3 violations" : "0 violations", gate ? "1 main-source files use concurrency" : "no concurrency primitives");
            require(r.output().contains("**no test sources**") != gate, "test facts follow scope " + decision, r.output());
        }
        if (!failures.isEmpty()) throw new AssertionError(failures);
        System.out.println("PASS assessment scope regressions");
    }
}
