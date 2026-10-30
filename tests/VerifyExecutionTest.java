///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Xml.java
import java.nio.file.*;
import java.util.*;
public class VerifyExecutionTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception {
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-verify-execution-");Path p=fixture("project",false,false);decisions(p,"TEST_SUPPORT_MODULES: gate\n");
  var doc=Xml.read(p.resolve("pom.xml"));var project=doc.getDocumentElement();project.removeChild(Xml.child(project,"profiles"));var plugins=Xml.child(Xml.child(project,"build"),"plugins");
  for(var plugin:Xml.children(plugins,"plugin")){
   String id=Xml.text(plugin,"artifactId");
   if(!List.of("maven-compiler-plugin","maven-surefire-plugin","maven-pmd-plugin").contains(id))plugins.removeChild(plugin);
   else if(id.equals("maven-pmd-plugin"))for(var execution:Xml.children(Xml.child(plugin,"executions"),"execution"))Xml.set(execution,"phase","none");
  }
  Xml.write(doc,p.resolve("pom.xml"));check(run(p,List.of("git","init","-q")),"fixture init");
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2");
  Result result=script(p,"VerifyPass.java",kit.toString(),repo.toString(),root.resolve("proof").toString(),"pmd");
  require(result.exit()!=0&&result.output().contains("pmd did not execute in the normal verify lifecycle"),"direct-goal plant cannot certify a dormant lifecycle gate",result.output());
  if(!failures.isEmpty())throw new AssertionError(failures+"; evidence "+root);System.out.println("PASS verify lifecycle enforcement; evidence "+root);
 }
}
