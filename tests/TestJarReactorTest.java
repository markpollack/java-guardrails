///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Xml.java
import java.nio.file.*;
import java.util.*;

/** Test-jar consumers must see the packaged test contract, not every upstream test class. */
public class TestJarReactorTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception {
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-test-jar-reactor-");Path p=fixture("project",true,false);decisions(p,"TEST_SUPPORT_MODULES: gate\n");
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2");write(p,".mvn/maven.config","-Dmaven.repo.local="+repo+"\n");
  String group=Xml.text(Xml.read(p.resolve("pom.xml")).getDocumentElement(),"groupId");
  Path lib=p.resolve("z-lib/pom.xml");Files.writeString(lib,Files.readString(lib).replace("</project>","""
  <dependencies>
   <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
   <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version><scope>test</scope></dependency>
  </dependencies><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version>
   <executions><execution><goals><goal>test-jar</goal></goals><configuration><includes><include>fx/lib/SharedContractTest.class</include></includes></configuration></execution></executions>
  </plugin></plugins></build></project>
  """));
  Path app=p.resolve("app/pom.xml");String appXml=Files.readString(app).replace("</dependencies>","<dependency><groupId>"+group+"</groupId><artifactId>z-lib</artifactId><version>1-SNAPSHOT</version><type>test-jar</type><scope>test</scope></dependency></dependencies>");
  appXml=appXml.replace("</project>","<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><configuration><dependenciesToScan><dependency>"+group+":z-lib:test-jar:tests</dependency></dependenciesToScan></configuration></plugin></plugins></build></project>");Files.writeString(app,appXml);
  write(p,"z-lib/src/test/java/fx/lib/SharedContractTest.java","package fx.lib; public class SharedContractTest { @org.junit.jupiter.api.Test void shared() { org.junit.jupiter.api.Assertions.assertEquals(42,Lib.value()); } }");
  write(p,"z-lib/src/test/java/fx/lib/OwnOnlyTest.java","package fx.lib; public class OwnOnlyTest { @org.junit.jupiter.api.Test void own() { org.junit.jupiter.api.Assertions.assertTrue(org.apache.commons.lang3.StringUtils.isEmpty(\"\")); } }");
  check(run(p,List.of("git","init","-q")),"fixture init");
  Result control=maven(p,"-Djacoco.skip=true","-Dpmd.skip=true","-Dcpd.skip=true","-Dspotbugs.skip=true","clean","package");check(control,"normal package respects filtered test-jar");
  Result standalone=script(p,"JacocoMeasure.java");check(standalone,"standalone coverage uses packaged test contract","JACOCO_FLOORS");
  check(script(p,"SpotBugsMeasure.java"),"standalone SpotBugs resolves packaged test artifacts");
  check(script(p,"Falsify.java","pmd"),"PMD plant resolves packaged test artifacts");
  check(script(p,"Falsify.java","spotbugs"),"SpotBugs plant resolves packaged test artifacts");
  Result complete=script(p,"MeasurePass.java",root.resolve("measurement").toString(),kit.toString(),repo.toString());check(complete,"full pass uses packaged test contract","Full measurement complete");
  if(!failures.isEmpty())throw new AssertionError(failures+"; evidence "+root);System.out.println("PASS reactor test-jar packaging; evidence "+root);
 }
}
