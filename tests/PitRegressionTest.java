///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;
public class PitRegressionTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception{
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-pit-");Path p=root.resolve("target");Files.createDirectories(p.resolve(".mvn/wrapper"));
  for(String f:List.of("mvnw","mvnw.cmd",".mvn/wrapper/maven-wrapper.properties"))Files.copy(kit.resolve("fixtures/nullaway").resolve(f),p.resolve(f));p.resolve("mvnw").toFile().setExecutable(true);
  write(p,"pom.xml","""
  <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>pit</artifactId><version>1</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies>
  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin></plugins></build>
  <profiles><profile><id>pit</id><build><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><version>1.30.1</version>
  <dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>1.2.3</version></dependency></dependencies>
  <configuration><targetClasses><param>fx.*</param></targetClasses><threads>1</threads><mutationThreshold>90</mutationThreshold></configuration>
  </plugin></plugins></build></profile></profiles></project>
  """);
  write(p,"src/main/java/fx/Value.java","package fx; public class Value { public int magnitude(int n) { return n < 0 ? -n : n; } }");
  String test="package fx; class ValueTest { @org.junit.jupiter.api.Test void works() { org.junit.jupiter.api.Assertions.assertEquals(3,new Value().magnitude(3)); org.junit.jupiter.api.Assertions.assertEquals(3,new Value().magnitude(-3)); } }";write(p,"src/test/java/fx/ValueTest.java",test);
  // Only one reactor module configures PIT. Upstream libraries must build without receiving
  // an unconfigured mutation invocation or silently selecting another PIT version.
  Path measured=p.resolve("measured");Files.createDirectories(measured);Files.move(p.resolve("src"),measured.resolve("src"));Files.move(p.resolve("pom.xml"),measured.resolve("pom.xml"));
  String measuredPom=Files.readString(measured.resolve("pom.xml")).replaceFirst("<dependencies>","<dependencies><dependency><groupId>fixture</groupId><artifactId>upstream</artifactId><version>1</version></dependency>");
  Files.writeString(measured.resolve("pom.xml"),measuredPom);
  write(p,"pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><modules><module>upstream</module><module>measured</module></modules></project>");
  write(p,"upstream/pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>upstream</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version></plugin></plugins></build></project>");
  write(p,"upstream/src/main/java/upstream/Value.java","package upstream; public class Value { public static int number() { return 3; } }");
  Path upstreamPom=p.resolve("upstream/pom.xml");Files.writeString(upstreamPom,Files.readString(upstreamPom).replace("</project>","<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies></project>"));
  write(p,"upstream/src/test/java/upstream/ValueTest.java","package upstream; class ValueTest { @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals(3,Value.number()); } }");
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2"),a=root.resolve("before"),b=root.resolve("after");Files.createDirectories(a);Files.createDirectories(b);
  Result first=script(p,"OptionalSnapshot.java",a.toString(),repo.toString());check(first,"configured PIT measured");if(first.exit()!=0)throw new AssertionError("PIT failed: "+root);
  write(measured,"src/test/java/fx/ValueTest.java","package fx; class ValueTest { @org.junit.jupiter.api.Test void works() { new Value().magnitude(3); new Value().magnitude(-3); } }");
  Result second=script(p,"OptionalSnapshot.java",b.toString(),repo.toString());check(second,"PIT candidate measured");if(second.exit()!=0)throw new AssertionError("PIT failed: "+root);
  var old=Findings.read(a.resolve("pit.json"));var now=Findings.read(b.resolve("pit.json"));require(!Findings.introduced(old,old,now).isEmpty(),"removing assertions creates introduced PIT survivors despite unchanged execution",now.toString());
  require(Files.readString(measured.resolve("pom.xml")).contains("<mutationThreshold>90</mutationThreshold>"),"PIT report overlay preserves enforced threshold","");
  require(old.modules().equals(List.of("measured")),"PIT measures exactly the configured module",old.modules().toString());
  if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS configured PIT regression; evidence "+root);
 }
}
