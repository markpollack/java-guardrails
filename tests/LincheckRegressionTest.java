///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;

/** Executes a real model checker, its lost-update counterexample and an inactive contract. */
public class LincheckRegressionTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception {
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-lincheck-");Path p=root.resolve("project");
  for(String file:List.of("mvnw","mvnw.cmd",".mvn/wrapper/maven-wrapper.properties")){
   Path dest=p.resolve(file);Files.createDirectories(dest.getParent());Files.copy(kit.resolve("fixtures/nullaway").resolve(file),dest);
  }
  p.resolve("mvnw").toFile().setExecutable(true);
  write(p,"pom.xml","""
  <project><modelVersion>4.0.0</modelVersion><groupId>fx</groupId><artifactId>concurrency</artifactId><version>1</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies>
   <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency>
   <dependency><groupId>org.jetbrains.lincheck</groupId><artifactId>lincheck</artifactId><version>3.7</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
   <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin>
   <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version><configuration><argLine>-XX:+EnableDynamicAgentLoading</argLine></configuration></plugin>
  </plugins></build></project>
  """);
  String source="package fx; public class Counter { private int value; public synchronized int increment() { return ++value; } }";
  String test="""
  package fx;
  import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
  import org.jetbrains.lincheck.datastructures.Operation;
  public class CounterTest {
   private final Counter counter = new Counter();
   @Operation public int increment() { return counter.increment(); }
   @org.junit.jupiter.api.Test public void linearizable() {
    new ModelCheckingOptions().iterations(5).invocationsPerIteration(100).threads(2).actorsPerThread(2).check(CounterTest.class);
   }
  }
  """;
  write(p,"src/main/java/fx/Counter.java",source);write(p,"src/test/java/fx/CounterTest.java",test);
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2");
  List<String> command=List.of(p.resolve("mvnw").toString(),"-B","-Dmaven.repo.local="+repo,"clean","test");
  Result green=run(p,command);check(green,"real Lincheck model passes","Tests run: 1, Failures: 0");
  Path first=root.resolve("first");Files.createDirectories(first);check(script(p,"OptionalSnapshot.java",first.toString(),repo.toString()),"configured Lincheck snapshot");
  write(p,"src/main/java/fx/Counter.java",source.replace("synchronized ",""));
  Result red=run(p,command);require(red.exit()!=0&&red.output().contains("Invalid execution results"),"lost update is falsified by model checking",red.output());
  Path second=root.resolve("second");Files.createDirectories(second);check(script(p,"OptionalSnapshot.java",second.toString(),repo.toString()),"counterexample captured");
  var before=Findings.read(first.resolve("lincheck.json"));var after=Findings.read(second.resolve("lincheck.json"));
  require(!Findings.introduced(before,before,after).isEmpty(),"new counterexample is introduced",after.toString());
  write(p,"src/main/java/fx/Counter.java",source);
  write(p,"src/test/java/fx/CounterTest.java",test.replace("new ModelCheckingOptions().iterations(5).invocationsPerIteration(100).threads(2).actorsPerThread(2).check(CounterTest.class);","counter.increment();"));
  check(run(p,command),"ordinary test still passes after checker removal");
  Path inactive=root.resolve("inactive");Files.createDirectories(inactive);Result missing=script(p,"OptionalSnapshot.java",inactive.toString(),repo.toString());
  require(missing.exit()!=0&&missing.output().contains("executable Lincheck invocation"),"unused Lincheck import is not a model-checking contract",missing.output());
  if(!failures.isEmpty())throw new AssertionError(failures+"; evidence "+root);
  System.out.println("PASS Lincheck execution/counterexample/inactive contract; evidence "+root);
 }
}
