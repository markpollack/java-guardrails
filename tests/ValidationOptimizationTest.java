///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/ValidationPlan.java ../scripts/Findings.java ../scripts/Util.java ../scripts/Xml.java
import java.nio.file.*;
import java.util.*;

/** Real reactor proof: downstream diagnostics, scope equivalence and POM skip evidence. */
public class ValidationOptimizationTest extends ReactorScopeTest {
    public static void main(String[] args)throws Exception {
        kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-validation-");System.out.println("Evidence: "+root);
        Path target=fixture("target",true,false),repo=args.length>1?Path.of(args[1]):root.resolve("m2");
        decisions(target,"TEST_SUPPORT_MODULES: gate\n");
        Path pom=target.resolve("pom.xml");Files.writeString(pom,Files.readString(pom).replace("</modules>","<module>shared</module><module>unrelated</module></modules>"));
        write(target,"unrelated/pom.xml",Files.readString(target.resolve("z-lib/pom.xml")).replace("<artifactId>z-lib</artifactId>","<artifactId>unrelated</artifactId>"));
        write(target,"unrelated/src/main/java/fx/unrelated/Other.java","package fx.unrelated; public class Other { }");
        write(target,"shared/pom.xml",Files.readString(target.resolve("z-lib/pom.xml")).replace("<artifactId>z-lib</artifactId>","<artifactId>shared</artifactId>").replace("</project>","<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies></project>"));
        write(target,"shared/src/main/java/fx/shared/Shared.java","package fx.shared; public class Shared { public int value() { return 42; } }");
        write(target,"shared/src/test/java/fx/shared/SharedTest.java","package fx.shared; class SharedTest { @org.junit.jupiter.api.Test void works() { org.junit.jupiter.api.Assertions.assertEquals(42,new Shared().value()); } }");
        Path appPom=target.resolve("app/pom.xml");String app=Files.readString(appPom);String dep=app.substring(app.indexOf("<dependency>"),app.indexOf("</dependency>")+13);Files.writeString(appPom,app.replace("</dependencies>",dep.replace("<artifactId>z-lib</artifactId>","<artifactId>shared</artifactId>")+"</dependencies>"));
        check(run(target,List.of("git","init","-q")),"init");check(run(target,List.of("git","add","-A")),"stage");
        check(run(target,List.of("git","-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-qm","fixture")),"commit");
        String before=run(target,List.of("git","rev-parse","HEAD")).output().trim();
        Path baseline=root.resolve("baseline");check(script(target,"MeasurePass.java",baseline.toString(),kit.toString(),repo.toString()),"shared-build baseline");
        if(!Files.exists(baseline.resolve("reactor-graph.json")))throw new AssertionError("Baseline incomplete: "+root);
        Path lib=target.resolve("z-lib/src/main/java/fx/lib/Lib.java");Files.writeString(lib,Files.readString(lib).replace("public static int value", "@Deprecated(forRemoval=true) public static int value"));
        check(run(target,List.of("git","add","-A")),"stage hotspot");String tree=run(target,List.of("git","write-tree")).output().trim();
        var plan=ValidationPlan.create(target,before,tree,baseline,false,false);
        require(!plan.full()&&plan.modules().equals(List.of("app","z-lib")),"changed library includes consumer but excludes independent module",plan.toString());
        require(!plan.resolveConsumer(),"source-only candidate skips consumer resolution",plan.toString());
        Path scoped=root.resolve("scoped"),full=root.resolve("full"),planFile=root.resolve("plan.json");ValidationPlan.write(planFile,plan);
        check(script(target,"MeasurePass.java",scoped.toString(),kit.toString(),repo.toString(),planFile.toString()),"scoped shared build");
        Path scratch;try(var children=Files.list(scoped)){scratch=children.filter(p->p.getFileName().toString().startsWith("source-")).findFirst().orElseThrow();}
        require(Files.exists(scratch.resolve("shared/target/shared-1-SNAPSHOT.jar")),"unchanged upstream artifact is freshly packaged","");
        require(!Files.exists(scratch.resolve("shared/target/surefire-reports"))&&!Files.exists(scratch.resolve("shared/target/spotbugsXml.xml")),"unchanged upstream tests and analysis are skipped","");
        require(Files.readString(scoped.resolve("consumer/reuse-proof.txt")).contains("no POM changed"),"skip has explicit diff proof","");
        var fullPlan=new ValidationPlan.Plan(before,tree,plan.changedFiles(),true,"equivalence proof",List.of("app","shared","unrelated","z-lib"),false,baseline.toString());ValidationPlan.write(planFile,fullPlan);
        check(script(target,"MeasurePass.java",full.toString(),kit.toString(),repo.toString(),planFile.toString()),"full shared build");
        var gates=List.of("pmd","archunit","spotbugs","jacoco","errorprone");ValidationPlan.equivalent(scoped,full,gates);System.out.println("PASS scoped/full identical finding multisets");
        var compiler=Findings.read(scoped.resolve("errorprone.json"));
        require(compiler.findings().stream().anyMatch(f->f.module().equals("app")&&f.rule().equals("javac:removal")),"upstream edit produces measured downstream diagnostic","");
        Findings.write(scoped.resolve("errorprone.json"),new Findings.Snapshot(1,compiler.gate(),compiler.checker(),compiler.sourceHash(),compiler.modules(),compiler.command(),true,List.of(),0));
        boolean rejected=false;try{ValidationPlan.equivalent(scoped,full,gates);}catch(IllegalStateException e){rejected=true;}require(rejected,"omitted dependent findings falsify equivalence","");
        Files.writeString(pom,Files.readString(pom)+"\n");run(target,List.of("git","add","-A"));tree=run(target,List.of("git","write-tree")).output().trim();
        var changedPom=ValidationPlan.create(target,before,tree,baseline,false,false);require(changedPom.full()&&changedPom.resolveConsumer(),"POM edit forces full scope and fresh closure",changedPom.toString());
        require(ValidationPlan.create(target,before,before,baseline,true,true).resolveConsumer(),"finish resolves even without any diff","");
        if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS validation optimizations; evidence "+root);
    }
}
