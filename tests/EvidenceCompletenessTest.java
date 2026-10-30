///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Xml.java
import java.nio.file.*;
import java.util.*;
public class EvidenceCompletenessTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception{
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-evidence-");Path p=fixture("target",true,false);decisions(p,"TEST_SUPPORT_MODULES: gate\n");check(run(p,List.of("git","init","-q")),"fixture init");
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2"),out=root.resolve("run");Result baseline=script(p,"MeasurePass.java",out.toString(),kit.toString(),repo.toString());check(baseline,"real complete evidence");if(baseline.exit()!=0)throw new AssertionError(root.toString());
  Path raw=out.resolve("raw"),report=raw.resolve("app/target/site/jacoco/jacoco.xml");String original=Files.readString(report);
  var doc=Xml.read(report);var methods=doc.getElementsByTagName("method");var method=methods.item(0);method.getParentNode().removeChild(method);Xml.write(doc,report);
  Result missing=script(raw,"BytecodeSnapshot.java",root.resolve("missing-method").toString(),out.resolve("tests.log").toString());require(missing.exit()!=0,"dropped coverage method cannot become zero",missing.output());Files.writeString(report,original);
  doc=Xml.read(report);var counters=doc.getElementsByTagName("counter");((org.w3c.dom.Element)counters.item(0)).setAttribute("covered","99999");Xml.write(doc,report);
  Result corrupt=script(raw,"BytecodeSnapshot.java",root.resolve("invented-count").toString(),out.resolve("tests.log").toString());require(corrupt.exit()!=0,"fabricated coverage counter rejected",corrupt.output());Files.writeString(report,original);
  Path exec=raw.resolve("app/target/jacoco.exec");byte[] bytes=Files.readAllBytes(exec);Files.write(exec,new byte[]{1,2,3});Result execBad=script(raw,"BytecodeSnapshot.java",root.resolve("corrupt-exec").toString(),out.resolve("tests.log").toString());require(execBad.exit()!=0,"corrupt execution provenance rejected",execBad.output());Files.write(exec,bytes);
  check(script(raw,"BytecodeSnapshot.java",root.resolve("recomputed").toString(),out.resolve("tests.log").toString()),"independent raw-evidence recomputation green");
  if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS complete evidence controls; "+root);
 }
}
