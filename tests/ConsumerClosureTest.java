///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java
import java.nio.file.*;
import java.util.*;

/** Real consumer mediation: the producer's dependencyManagement must not mask a reorder. */
public class ConsumerClosureTest extends ReactorScopeTest {
 static Path repo;
 static final String MODEL="<dependency><groupId>closure.fixture</groupId><artifactId>model</artifactId><version>1</version></dependency>";
 static final String EXEC="<dependency><groupId>org.zeroturnaround</groupId><artifactId>zt-exec</artifactId><version>1.13.0</version></dependency>";
 public static void main(String[] args)throws Exception {
  kit=Path.of(args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-consumer-closure-");repo=args.length>1?Path.of(args[1]):root.resolve("m2");
  Path p=root.resolve("producer");wrapper(p);
  write(p,"pom.xml","""
   <project><modelVersion>4.0.0</modelVersion><groupId>closure.fixture</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
   <modules><module>model</module><module>tck</module></modules><properties><maven.compiler.release>17</maven.compiler.release></properties>
   <dependencyManagement><dependencies><dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.18</version></dependency></dependencies></dependencyManagement>
   <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build>
   <profiles><profile><id>release</id><build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>flatten-maven-plugin</artifactId><version>1.5.0</version><configuration><flattenMode>ossrh</flattenMode></configuration><executions><execution><phase>process-resources</phase><goals><goal>flatten</goal></goals></execution></executions></plugin></plugins></build></profile></profiles></project>
   """);
  write(p,"model/pom.xml",module("model","<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>"));
  write(p,"tck/pom.xml",module("tck",MODEL+EXEC));
  write(p,"model/src/main/java/api/Model.java","package api; public class Model { public int value() { return 1; } }");
  write(p,"tck/src/main/java/api/Tck.java","package api; public class Tck { public int value() { return 1; } }");
  write(p,"tools/unrelated/pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>unrelated</groupId><artifactId>tool</artifactId><version>1</version><profiles><profile><id>unrelated-publish</id><build><plugins><plugin><artifactId>flatten-maven-plugin</artifactId></plugin></plugins></build></profile></profiles></project>");
  capture(p,"baseline");String old=resolve("before");require(old.contains("org.slf4j:slf4j-api:jar:2.0.18"),"consumer starts on SLF4J 2.0.18",old);
  Path oldJar=root.resolve("before.jar");Files.copy(p.resolve("tck/target/tck-1.jar"),oldJar);
  write(p,"tck/pom.xml",module("tck",EXEC+MODEL));capture(p,"reordered");String now=resolve("after");require(now.contains("org.slf4j:slf4j-api:jar:1.7.32"),"reorder downgrades the external consumer",now);
  Result internal=maven(p,"-Dmaven.repo.local="+repo,"-pl","tck","-am","org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree","-DoutputFile=target/producer-tree.txt");check(internal,"producer still builds");
  String producer=Files.readString(p.resolve("tck/target/producer-tree.txt"));require(producer.contains("org.slf4j:slf4j-api:jar:2.0.18"),"parent dependencyManagement masks the consumer regression",producer);
  Path cp=root.resolve("empty-classpath");Files.writeString(cp,"");Result api=run(root,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/ApiCheck.java").toString(),oldJar.toString(),p.resolve("tck/target/tck-1.jar").toString(),cp.toString(),cp.toString(),root.resolve("api.xml").toString()));
  check(api,"japicmp alone cannot see the consumer regression","API unchanged");
  Result changed=compare(p,"reordered");require(changed.exit()==1&&changed.output().contains("2.0.18@compile -> 1.7.32@compile"),"R2 rejects the consumer dependency regression",changed.output());
  decisions(p,"DEPENDENCY_CHANGE: closure.fixture:tck:jar: | org.slf4j:slf4j-api:jar: | 2.0.18@compile | 1.7.32@compile | \n");
  require(compare(p,"reordered").exit()==2,"blank reasons cannot authorize dependency changes","");
  decisions(p,"DEPENDENCY_CHANGE: closure.fixture:model:jar: | org.slf4j:slf4j-api:jar: | 2.0.18@compile | 1.7.32@compile | fixture exception on another module\n");
  require(compare(p,"reordered").exit()==1,"exception cannot cover another publication","");
  decisions(p,"DEPENDENCY_CHANGE: closure.fixture:tck:jar: | org.slf4j:slf4j-api:jar: | 2.0.18@compile | 1.7.32@compile | explicit test of an intentional version change\n");
  check(compare(p,"reordered"),"exact reasoned version change is reported","changes recorded with reasons: 1");
  decisions(p,"");write(p,"tck/pom.xml",module("tck",MODEL+EXEC));write(p,"tck/src/main/java/api/Tck.java","package api; public class Tck { public int value() { return 2; } }");capture(p,"restored");
  check(compare(p,"restored"),"restoring dependency order and changing only a method body stays green","closures unchanged");
  Files.move(root.resolve("restored/dependencies.json"),root.resolve("restored/saved.json"));require(compare(p,"restored").exit()==2,"missing closure evidence halts","");
  Path undocumented=repo.resolve("closure/fixture/undocumented/1");Files.createDirectories(undocumented);Files.copy(p.resolve("tck/target/tck-1.jar"),undocumented.resolve("undocumented-1.jar"),StandardCopyOption.REPLACE_EXISTING);
  write(p,"tck/pom.xml",module("tck",MODEL+EXEC+"<dependency><groupId>closure.fixture</groupId><artifactId>undocumented</artifactId><version>1</version></dependency>"));
  Result metadata=run(root,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/ConsumerSnapshot.java").toString(),"capture",p.toString(),root.resolve("missing-pom").toString(),repo.toString()));
  require(metadata.exit()==2&&metadata.output().contains("metadata incomplete"),"Maven's successful tree with a missing dependency POM cannot certify closure",metadata.output());
  if(!failures.isEmpty())throw new AssertionError(failures+"; evidence "+root);System.out.println("PASS consumer dependency freeze; evidence "+root);
 }
 static void wrapper(Path p)throws Exception{Files.createDirectories(p.resolve(".mvn/wrapper"));for(String f:List.of("mvnw","mvnw.cmd",".mvn/wrapper/maven-wrapper.properties"))Files.copy(kit.resolve("fixtures/nullaway").resolve(f),p.resolve(f));p.resolve("mvnw").toFile().setExecutable(true);}
 static String module(String name,String deps){return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>closure.fixture</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>"+name+"</artifactId><dependencies>"+deps+"</dependencies></project>";}
 static void publish(Path p)throws Exception{check(maven(p,"-Dmaven.repo.local="+repo,"-Prelease","-DskipTests","clean","package","org.apache.maven.plugins:maven-install-plugin:3.1.4:install"),"stage actual publication POMs in private repository");}
 static void capture(Path p,String name)throws Exception{check(run(root,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/ConsumerSnapshot.java").toString(),"capture",p.toString(),root.resolve(name).toString(),repo.toString())),"capture published consumer closure "+name,"closure captured");}
 static Result compare(Path p,String name)throws Exception{return run(root,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/ConsumerSnapshot.java").toString(),"compare",root.resolve("baseline/dependencies.json").toString(),root.resolve(name+"/dependencies.json").toString(),p.resolve("config/guardrails/decisions.md").toString(),root.resolve("comparison-"+sequence+".json").toString()));}
 static String resolve(String name)throws Exception{Path c=root.resolve(name);wrapper(c);write(c,"pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId><artifactId>consumer</artifactId><version>1</version><dependencies><dependency><groupId>closure.fixture</groupId><artifactId>tck</artifactId><version>1</version></dependency></dependencies></project>");check(maven(c,"-Dmaven.repo.local="+repo,"org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree","-DoutputFile=tree.txt"),"resolve isolated no-parent consumer "+name);return Files.readString(c.resolve("tree.txt"));}
}
