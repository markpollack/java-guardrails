///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;

/** Lifecycle goals can add Maven properties that cannot be serialized as XML names. */
public class LifecycleModelTest extends ReactorScopeTest {
    public static void main(String[] args)throws Exception{
        kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        root=Files.createTempDirectory("guardrails-lifecycle-model-");System.out.println("Evidence: "+root);
        Path target=fixture("target",true,false);decisions(target,"TEST_SUPPORT_MODULES: gate\n");
        Path pom=target.resolve("pom.xml");
        Files.writeString(pom,Files.readString(pom).replace("<build><plugins>","""
            <build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.8.1</version>
            <executions><execution><phase>initialize</phase><goals><goal>properties</goal></goals></execution></executions>
            </plugin>
            <plugin><groupId>org.codehaus.mojo</groupId><artifactId>flatten-maven-plugin</artifactId><version>1.5.0</version>
            <configuration><flattenMode>ossrh</flattenMode><updatePomFile>true</updatePomFile></configuration>
            <executions><execution><phase>process-resources</phase><goals><goal>flatten</goal></goals></execution></executions>
            </plugin>
            """));
        for(String module:List.of("app","z-lib")){
            Path child=target.resolve(module+"/pom.xml");
            Files.writeString(child,"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"+Files.readString(child));
        }
        check(run(target,List.of("git","init","-q")),"initialize lifecycle-mutating reactor");
        Path repo=args.length>1?Path.of(args[1]).toAbsolutePath():root.resolve("m2"),out=root.resolve("measurement");
        Result measured=script(target,"MeasurePass.java",out.toString(),kit.toString(),repo.toString());
        check(measured,"dependency properties and flattening complete shared measurement","Full measurement complete");
        if(measured.exit()!=0)throw new AssertionError("Lifecycle model measurement failed; evidence "+root);
        var graph=com.google.gson.JsonParser.parseString(Files.readString(out.resolve("reactor-graph.json"))).getAsJsonObject();
        require(graph.getAsJsonArray("app").asList().stream().anyMatch(v->v.getAsString().equals("z-lib")),"lifecycle retains reactor dependent edge",graph.toString());
        for(String gate:List.of("pmd","archunit","jacoco","spotbugs","errorprone"))require(Findings.read(out.resolve(gate+".json")).complete(),gate+" complete","");
        require(Files.exists(out.resolve("consumer/dependencies.json")),"publication closure still captured","");
        require(!Files.exists(target.resolve(".flattened-pom.xml")),"original target remains untouched","");
        if(!failures.isEmpty())throw new AssertionError(failures);
        System.out.println("PASS shared measurement with lifecycle model mutations");
    }
}
