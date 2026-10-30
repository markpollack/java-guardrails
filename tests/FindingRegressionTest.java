///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java ../scripts/Declarations.java
import java.nio.file.*;
import java.util.*;

/** Checker-backed plants, not arithmetic-only count tests. Gates need not be installed. */
public class FindingRegressionTest extends ReactorScopeTest {
    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length==0 ? "." : args[0]).toAbsolutePath().normalize(); root=Files.createTempDirectory("guardrails-findings-");
        write(root,"pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>fx</groupId><artifactId>fx</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin></plugins></build></project>");
        for(String file:List.of("mvnw","mvnw.cmd",".mvn/wrapper/maven-wrapper.properties")){Path dest=root.resolve(file);Files.createDirectories(dest.getParent());Files.copy(kit.resolve("fixtures/nullaway").resolve(file),dest);}root.resolve("mvnw").toFile().setExecutable(true);
        write(root,".mvn/maven.config","-Dmaven.repo.local="+(args.length>1?Path.of(args[1]):root.resolve("m2"))+"\n");
        StringBuilder code=new StringBuilder("package fx;\npublic class A {\n int work(int n) {\n");
        for(int i=0;i<20;i++) code.append("  if (n == ").append(i).append(") { n++; }\n");
        code.append(" return n;\n }\n}\n"); String original=code.toString();
        Path source=root.resolve("src/main/java/fx/A.java");write(root,"src/main/java/fx/A.java",original);
        Findings.Snapshot start=source("start"); require(start.findings().size()==3,"source fixture has inherited findings",start.toString());
        Files.writeString(source,"\n\n\n"+original);var shifted=source("shift");
        require(Findings.introduced(start,start,shifted).isEmpty(),"line shifts preserve bound declaration identity",shifted.toString());
        Files.writeString(source,original.replace("work(int n)","moved(int n)")); var moved=source("moved");
        require(Findings.introduced(start,start,moved).size()==3,"moving inherited debt is introduced",moved.toString());
        Files.writeString(source,original.replace("}\n", "}\n"));
        String method=original.substring(original.indexOf(" int work"),original.lastIndexOf('}'));
        Files.writeString(source,original.substring(0,original.lastIndexOf('}'))+method.replace("work(int n)","copy(int n)")+"}\n");var duplicate=source("duplicate");
        require(Findings.introduced(start,start,duplicate).stream().anyMatch(f->f.rule().equals("cpd")),"later absent CPD gate catches duplication now",duplicate.toString());
        check(script(root,"PmdMeasure.java"),"without comparison duplication reaches a real policy stop","STOP PMD_MECHANISM (decide)","CPD");
        Files.writeString(source,"package fx; public class A { int work(int n) { return n; } }");var fixed=source("fixed");
        require(Findings.introduced(start,start,fixed).isEmpty(),"repair clears introduced duplication before accepting the hotspot","");
        Files.writeString(source,original);var returned=source("returned");
        require(!Findings.introduced(start,fixed,returned).isEmpty(),"previously removed findings cannot return",returned.toString());
        var one=start.findings().getFirst();var multi=new ArrayList<>(start.findings());multi.add(one);
        var extra=new Findings.Snapshot(1,start.gate(),start.checker(),start.sourceHash(),start.modules(),start.command(),true,multi,0);
        require(Findings.introduced(start,start,extra).size()==1,"one old occurrence cannot match twice",extra.toString());
        write(root,"src/main/java/fx/A.java","package fx; public class A { B other; }");write(root,"src/main/java/fx/B.java","package fx; public class B {}");
        var noCycle=cycles("acyclic");write(root,"src/main/java/fx/B.java","package fx; public class B { A back; }");var cycle=cycles("cyclic");
        require(!Findings.introduced(noCycle,noCycle,cycle).isEmpty(),"earlier refactor introduces later absent ArchUnit gate finding",cycle.toString());
        require(Files.notExists(root.resolve("config/guardrails/decisions.md")),"introduced findings never enter a policy decision","");
        // Negative control: bypass comparison and invoke the actual later gate's policy path.
        check(script(root,"ArchUnitMeasure.java"),"without comparison the cycle reaches a real policy stop","STOP ARCHUNIT_CYCLES (decide)");
        write(root,"src/main/java/fx/B.java","package fx; public class B {}");var repaired=cycles("cycle-repaired");
        require(Findings.introduced(noCycle,noCycle,repaired).isEmpty(),"repair clears introduced cycle before accepting the hotspot","");
        Declarations bindings=new Declarations(source);String protectedText=bindings.named("fx.A").text();
        Files.writeString(source,"package fx; public class A { B other; int added; }");
        require(!new Declarations(source).named("fx.A").text().equals(protectedText),"hands-off declaration edits are observable","");
        if(!failures.isEmpty())throw new AssertionError(failures);
        System.out.println("PASS finding attribution; evidence "+root);
    }
    static Findings.Snapshot source(String name)throws Exception { Path out=root.resolve(name+".json");check(script(root,"SourceSnapshot.java",out.toString()),name);return Findings.read(out); }
    static Findings.Snapshot cycles(String name)throws Exception {
        Files.createDirectories(root.resolve("target/classes"));
        var p=new ProcessBuilder("javac","-d",root.resolve("target/classes").toString(),root.resolve("src/main/java/fx/A.java").toString(),root.resolve("src/main/java/fx/B.java").toString()).inheritIO().start();if(p.waitFor()!=0)throw new AssertionError("compile");
        Path out=root.resolve(name+".json");check(script(root,"CycleSnapshot.java",out.toString()),name);return Findings.read(out);
    }
}
