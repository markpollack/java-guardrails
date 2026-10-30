///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ../scripts/ValidationPlan.java
import java.nio.file.*;
import java.util.*;

public class ValidationPlanTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("guardrails-validation-plan-");
        Path previous=Files.createDirectory(root.resolve("previous"));
        Path current=Files.createDirectory(root.resolve("current"));
        var inherited=new Findings.Finding("rule","a","A#m()","A.java:1","inherited");
        var added=new Findings.Finding("rule","b","B#m()","B.java:1","plant");
        Findings.write(previous.resolve("pmd.json"),snapshot(List.of("a","b"),List.of(inherited)));
        boolean rejected=false;
        try { ValidationPlan.merge(previous,current,List.of("b")); }
        catch(IllegalStateException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Missing selected measurement was reused as green");
        Findings.write(current.resolve("pmd.json"),snapshot(List.of("b"),List.of(added)));
        ValidationPlan.merge(previous,current,List.of("b"));
        var merged=Findings.read(current.resolve("pmd.json"));
        if(!merged.findings().equals(List.of(inherited,added)))throw new AssertionError("Scoped merge lost findings");
        Path full=Files.createDirectory(root.resolve("full"));
        Findings.write(full.resolve("pmd.json"),snapshot(List.of("a","b"),List.of(inherited,added)));
        ValidationPlan.equivalent(current,full,List.of("pmd"));
        Findings.write(full.resolve("pmd.json"),snapshot(List.of("a","b"),List.of(inherited,added,added)));
        rejected=false;try{ValidationPlan.equivalent(current,full,List.of("pmd"));}catch(IllegalStateException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Multiplicity mismatch accepted");
        System.out.println("PASS missing measurement rejected; merged findings and equivalence multiplicities proven");
    }
    static Findings.Snapshot snapshot(List<String> modules,List<Findings.Finding> findings){
        return new Findings.Snapshot(1,"pmd","fixture","hash",modules,List.of("fixture"),true,findings,0);
    }
}
