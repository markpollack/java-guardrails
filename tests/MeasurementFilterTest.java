///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;
/** A gate's Maven property must not filter the complete regression inventory. */
public class MeasurementFilterTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception {
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-measure-filter-");System.out.println("Evidence: "+root);
  Path target=fixture("target",true,false),repo=args.length>1?Path.of(args[1]):root.resolve("m2");
  decisions(target,"TEST_SUPPORT_MODULES: gate\n");
  write(target,"z-lib/src/main/java/fx/lib/Exposure.java","package fx.lib; public class Exposure { private final java.util.List<String> values; public Exposure(java.util.List<String> values) { this.values=values; } public java.util.List<String> values() { return values; } }");
  write(target,"config/hide-bugs.xml","<FindBugsFilter><Match><Class name=\"does.not.Exist\"/></Match></FindBugsFilter>");
  Path pom=target.resolve("pom.xml");String xml=Files.readString(pom);xml=xml.replaceAll("(?s)<spotbugs.includeFilterFile>.*?</spotbugs.includeFilterFile>","");
  Files.writeString(pom,xml.replace("<properties>","<properties><spotbugs.includeFilterFile>${maven.multiModuleProjectDirectory}/config/hide-bugs.xml</spotbugs.includeFilterFile>"));
  check(run(target,List.of("git","init","-q")),"fixture init");Path output=root.resolve("measurement");
  check(script(target,"MeasurePass.java",output.toString(),kit.toString(),repo.toString()),"measurement with restrictive gate property completes");
  if(!Files.exists(output.resolve("spotbugs.json")))throw new AssertionError("Missing measurement "+root);
  var findings=Findings.read(output.resolve("spotbugs.json")).findings();
  require(findings.stream().anyMatch(f->f.rule().equals("EI_EXPOSE_REP")),"gate include property cannot hide exposed representation",findings.toString());
  require(findings.stream().anyMatch(f->f.rule().equals("EI_EXPOSE_REP2")),"gate include property cannot hide constructor exposure",findings.toString());
  require(Files.readString(pom).contains("config/hide-bugs.xml"),"target gate property remains unchanged","");
  write(target,"config/exclude-everything.xml","<FindBugsFilter><Match><Rank value=\"1\"/></Match></FindBugsFilter>");
  String exclude=Files.readString(pom).replaceAll("(?s)<spotbugs.includeFilterFile>.*?</spotbugs.includeFilterFile>","").replace("<properties>","<properties><spotbugs.excludeFilterFile>${maven.multiModuleProjectDirectory}/config/exclude-everything.xml</spotbugs.excludeFilterFile><spotbugs.excludeFilterFiles>${maven.multiModuleProjectDirectory}/config/exclude-everything.xml</spotbugs.excludeFilterFiles><spotbugs.onlyAnalyze>does.not.Exist</spotbugs.onlyAnalyze>");Files.writeString(pom,exclude);
  Path second=root.resolve("exclude-measurement");check(script(target,"MeasurePass.java",second.toString(),kit.toString(),repo.toString()),"measurement with restrictive exclusion property completes");
  require(Findings.read(second.resolve("spotbugs.json")).findings().stream().anyMatch(f->f.rule().equals("EI_EXPOSE_REP2")),"gate exclusion property cannot hide constructor exposure","");
  require(Files.readString(pom).equals(exclude),"target exclusion property remains unchanged","");
  if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS measurement filter isolation; evidence "+root);
 }
}
