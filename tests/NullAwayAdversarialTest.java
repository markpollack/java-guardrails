///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES NullAwayTest.java

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Compiler-backed regression challenges. Optional args: kit, retained fixture, single case. */
public class NullAwayAdversarialTest {
    static Path kit, seed, root;
    static String selected;
    static final List<String> failures = new ArrayList<>();
    static final String GREEN = "package fx; public final class Example { public String value() { return \"ok\"; } }\n";
    record Result(int exit, String output) {}
    interface Case { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        kit=Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        if (args.length < 2) {
            NullAwayTest.main(new String[] {kit.toString()});
            seed=NullAwayTest.target;
        } else {
            seed=Path.of(args[1]);
        }
        selected=args.length>2?args[2]:"";
        root=Files.createTempDirectory("nullaway-adversarial-"); System.out.println("Evidence: "+root);
        test("warn-unrelated", () -> {
            Path p=fixture("warn-unrelated"); editPom(p, s->s.replace("-Xep:NullAway:ERROR","-Xep:NullAway:WARN").replace("<errorprone.failOnWarning>true","<errorprone.failOnWarning>false"));
            Files.writeString(p.resolve("src/main/java/fx/Other.java"),"package fx; final class Other { void f() { new IllegalArgumentException(\"dropped\"); } }");
            Result r=script(p,"Falsify.java","nullaway"); require(r.exit!=0 && r.output.contains("NOT PROVEN"),"unrelated error must not prove WARN-only NullAway",r);
        });
        test("warn-enforced", () -> {
            Path p=fixture("warn-enforced");editPom(p,s->s.replace("-Xep:NullAway:ERROR","-Xep:NullAway:WARN"));
            Result r=script(p,"Falsify.java","nullaway");require(r.exit==0,"WARN plus failOnWarning must still be provable",r);
        });
        test("optout-boundary", () -> {
            Path p=fixture("optout-boundary");Files.writeString(p.resolve("src/main/java/fx/Example.java"),GREEN.replace("public final class","@org.jspecify.annotations.NullUnmarked public final class").replace("return \"ok\"","return null"));
            Result m=script(p,"NullAwayMeasure.java"); require(m.exit==0 && m.output.contains("NullAway findings: 0"),"opted-out class remains unchecked",m);
            Result r=script(p,"Falsify.java","nullaway");require(r.exit==0 && r.output.contains("new unannotated probe only"),"proof must state its declaration-coverage limit",r);
        });
        test("javac-warning", () -> {
            Path p=fixture("javac-warning");editPom(p,s->s.replaceAll("(?s)<arg>-Xplugin:ErrorProne.*?</arg>","<arg>-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true \\${errorprone.extraArgs}</arg>"));
            Files.writeString(p.resolve("src/main/java/fx/Example.java"),"package fx; public final class Example { Integer boxed() { return new Integer(1); } }");
            Result r=script(p,"Falsify.java","nullaway"); require(r.exit!=0 && r.output.contains("javac warnings"),"identify javac warnings rather than implying NullAway was disabled",r);
        });
        test("collision", () -> {
            Path p=fixture("collision"), probe=p.resolve("src/main/java/fx/GuardrailsNullAwayProbe.java");Files.writeString(probe,"target-owned sentinel");
            Result r=script(p,"Falsify.java","nullaway");require(r.exit!=0 && r.output.contains("NOT PROVEN") && !r.output.contains("Exception in thread") && Files.readString(probe).equals("target-owned sentinel"),"clean refusal preserves existing file",r);
        });
        test("same-line", () -> {
            Path p=fixture("same-line"); Files.writeString(p.resolve("src/main/java/fx/Example.java"),"package fx; public final class Example { static void take(String a, String b) {} void g() { take(null, null); } }");
            Result r=script(p,"NullAwayMeasure.java");require(r.exit==0 && r.output.contains("NullAway findings: 2"),"retain separate source columns without counting Maven's repeated summary",r);
        });
        test("module-scope", () -> {
            Path p=fixture("module-scope"); deleteTree(p.resolve("src"));editPom(p,s->s.replace("<artifactId>nullaway</artifactId><version>1</version>","<artifactId>nullaway</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"));
            for(String mod:List.of("a","b")){
                String pkg="p"+mod, cls=mod.toUpperCase();Path d=p.resolve(mod+"/src/main/java/"+pkg);Files.createDirectories(d);
                Files.writeString(d.resolve("package-info.java"),"@org.jspecify.annotations.NullMarked package "+pkg+";");
                Files.writeString(d.resolve(cls+".java"),"package "+pkg+"; public class "+cls+" { public String value() { return "+(mod.equals("a")?"null":"\"ok\"")+"; } }");
                String dep=mod.equals("b")?"<dependencies><dependency><groupId>fixture</groupId><artifactId>a</artifactId><version>1</version></dependency></dependencies>":"";
                Files.writeString(p.resolve(mod+"/pom.xml"),"<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>nullaway</artifactId><version>1</version></parent><artifactId>"+mod+"</artifactId>"+dep+"</project>");
            }
            Result r=script(p,"NullAwayMeasure.java","--module","b");require(r.exit==0 && r.output.contains("NullAway findings: 0") && r.output.contains("Upstream NullAway findings: 1"),"separate selected-module findings from -am dependencies",r);
        });
        test("color", () -> {
            Path p=fixture("color");Files.writeString(p.resolve("src/main/java/fx/Example.java"),GREEN.replace("return \"ok\"","return null"));Files.writeString(p.resolve(".mvn/maven.config"),"--color=always\n");
            Result r=script(p,"NullAwayMeasure.java");require(r.exit==0 && r.output.contains("NullAway findings: 1"),"ANSI color must not erase diagnostics",r);
        });
        test("quiet", () -> {
            Path p=fixture("quiet");Files.writeString(p.resolve("src/main/java/fx/Example.java"),GREEN.replace("return \"ok\"","return null"));Files.writeString(p.resolve(".mvn/maven.config"),"-q\n");
            Result r=script(p,"NullAwayMeasure.java");require((r.exit==0 && r.output.contains("NullAway findings: 1")) || (r.exit!=0 && r.output.contains("report unavailable")),"quiet output must be measured or explicitly refused, never reported as zero",r);
        });
        test("excluded-package", () -> {
            Path p=fixture("excluded-package");editPom(p,s->s.replace("-XepOpt:NullAway:OnlyNullMarked=true ","-XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:UnannotatedSubPackages=fx "));
            Result r=script(p,"Falsify.java","nullaway");require(r.exit!=0,"a self-marked probe must not defeat the target's excluded package",r);
        });
        test("string-marker", () -> {
            Path p=fixture("string-marker");Files.writeString(p.resolve("src/main/java/fx/package-info.java"),"@Deprecated(since=\"@org.jspecify.annotations.NullMarked\") package fx;");
            Result r=script(p,"NullAwayMeasure.java","--inventory");require(r.exit==0 && r.output.contains("0 of 2 main-source packages"),"annotation argument string is not a package marker",r);
        });
        test("interrupt", () -> {
            if(!System.getProperty("os.name").equals("Linux")){System.out.println("SKIP process-group SIGINT: requires Linux");return;}
            Path p=fixture("interrupt"),probe=p.resolve("src/main/java/fx/GuardrailsNullAwayProbe.java"),log=root.resolve("interrupt-process.log");
            Process process=new ProcessBuilder("setsid",kit.resolve("jbang").toString(),kit.resolve("scripts/Falsify.java").toString(),p.toString(),"nullaway").redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
                while(!Files.exists(probe) && process.isAlive() && System.nanoTime()<deadline)Thread.sleep(20);
                require(Files.exists(probe),"probe appears before interrupt",new Result(-1,Files.readString(log)));
                new ProcessBuilder("kill","-INT","--","-"+process.pid()).start().waitFor();
                require(process.waitFor(20,TimeUnit.SECONDS),"interrupted process terminates",new Result(-1,Files.readString(log)));
                require(!Files.exists(probe),"SIGINT removes only the owned probe",new Result(process.exitValue(),Files.readString(log)));
            } finally {if(process.isAlive()) new ProcessBuilder("kill","-KILL","--","-"+process.pid()).start().waitFor();}
        });
        if(!failures.isEmpty())throw new AssertionError("Failed cases: "+String.join(", ",failures));
        System.out.println("PASS adversarial regression cases");
    }
    static void test(String name, Case body) throws Exception {
        if(!selected.isEmpty()&&!selected.equals(name))return;
        try {body.run();System.out.println("PASS "+name);}catch(Exception|AssertionError e){failures.add(name);System.out.println("FAIL "+name+": "+e.getMessage());}
    }
    static Path fixture(String name) throws Exception {
        Path p=root.resolve(name);try(var paths=Files.walk(seed)){
            for(Path s:paths.toList()){Path relative=seed.relativize(s);if(relative.toString().equals("target")||relative.startsWith("target"))continue;Path d=p.resolve(relative);if(Files.isDirectory(s))Files.createDirectories(d);else Files.copy(s,d);}
        }
        p.resolve("mvnw").toFile().setExecutable(true);
        Files.writeString(p.resolve("src/main/java/fx/Example.java"),GREEN);
        Files.writeString(p.resolve("src/main/java/fx/package-info.java"),"@org.jspecify.annotations.NullMarked package fx;\n");return p;
    }
    static void editPom(Path p, java.util.function.UnaryOperator<String> change) throws Exception {Path f=p.resolve("pom.xml");Files.writeString(f,change.apply(Files.readString(f)));}
    static void deleteTree(Path p) throws Exception {try(var paths=Files.walk(p)){for(Path f:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}}
    static Result script(Path p,String file,String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of(kit.resolve("jbang").toString(),kit.resolve("scripts").resolve(file).toString(),p.toString()));command.addAll(List.of(args));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);Result r=new Result(process.waitFor(),output);
        Files.writeString(root.resolve(p.getFileName()+"-"+file+".log"),"exit="+r.exit+"\n"+output);return r;
    }
    static void require(boolean ok,String message,Result result){if(!ok)throw new AssertionError(message+"; exit "+result.exit+"\n"+result.output);}
}
