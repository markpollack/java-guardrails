///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;

/** A JPMS descriptor is real output, but has no classes for ArchUnit to import. */
public class DescriptorModuleTest extends ReactorScopeTest {
    public static void main(String[] args)throws Exception {
        kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        root=Files.createTempDirectory("guardrails-descriptor-");System.out.println("Evidence: "+root);
        Path target=fixture("target",true,false);
        Path source=target.resolve("z-lib/src/main/java/fx/lib/Lib.java");
        Files.delete(source);
        write(target,"app/src/main/java/fx/app/App.java","package fx.app; public class App { public int value() { return 42; } }");
        write(target,"z-lib/src/main/java/module-info.java","module descriptor.only { }");
        check(maven(target,"-Dmaven.repo.local="+root.resolve("m2"),"-DskipTests","compile"),"compile real descriptor module");
        check(script(target,"CycleSnapshot.java",root.resolve("cycles.json").toString()),"descriptor-only module has zero cycles");
        if(Files.exists(root.resolve("cycles.json")))require(Findings.read(root.resolve("cycles.json")).modules().contains("z-lib"),"descriptor module remains in measured inventory","");
        // A descriptor must not conceal a missing ordinary class.
        write(target,"z-lib/src/main/java/fx/lib/Missing.java","package fx.lib; public class Missing { }");
        Result missing=script(target,"CycleSnapshot.java",root.resolve("missing.json").toString());
        require(missing.exit()!=0,"uncompiled ordinary source is not descriptor-only",missing.output());
        Files.delete(target.resolve("z-lib/src/main/java/fx/lib/Missing.java"));
        Path descriptor=target.resolve("z-lib/target/classes/module-info.class");Files.delete(descriptor);
        Result empty=script(target,"CycleSnapshot.java",root.resolve("empty.json").toString());
        require(empty.exit()!=0,"empty output still fails",empty.output());
        if(!failures.isEmpty())throw new AssertionError(failures);
        System.out.println("PASS descriptor-only measurement");
    }
}
