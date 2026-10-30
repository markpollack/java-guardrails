///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Xml.java
import java.nio.file.*;
import java.util.*;
public class PassTransactionTest extends ReactorScopeTest {
 static Path worktree;
 static Result pass(String action,String...extra)throws Exception{List<String> cmd=new ArrayList<>(List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),action,worktree.toString(),kit.toString()));cmd.addAll(List.of(extra));return run(worktree,cmd);}
 public static void main(String[] args)throws Exception{
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-pass-transaction-");Path target=fixture("target",false,false);
  var doc=Xml.read(target.resolve("pom.xml"));var project=doc.getDocumentElement();project.removeChild(Xml.child(project,"profiles"));var plugins=Xml.child(Xml.child(project,"build"),"plugins");
  for(var plugin:Xml.children(plugins,"plugin"))if(!List.of("maven-compiler-plugin","maven-surefire-plugin").contains(Xml.text(plugin,"artifactId")))plugins.removeChild(plugin);Xml.write(doc,target.resolve("pom.xml"));
  write(target,"test-support/src/main/java/fx/Support.java","package fx; public class Support { public int value() { return 42; } }");
  decisions(target,"HANDS_OFF: member:fx.Support#value() | reserved behavior\n");
  check(run(target,List.of("git","init","-q")),"fixture init");check(run(target,List.of("git","config","user.name","Fixture")),"fixture identity");check(run(target,List.of("git","config","user.email","fixture@example.invalid")),"fixture email");
  check(run(target,List.of("git","add","-A")),"fixture stage");check(run(target,List.of("git","commit","-qm","fixture")),"fixture commit");
  Result begin=run(target,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),"start",target.toString(),kit.toString()));check(begin,"start");worktree=Path.of(begin.output().lines().filter(l->l.startsWith("Work on ")).findFirst().orElseThrow().substring(8));
  if(args.length>1){
   Path cache=Path.of(args[1]),copy=worktree.resolve(".guardrails-run/m2");Files.createDirectories(copy);
   try(var paths=Files.walk(cache)){for(Path file:paths.filter(Files::isRegularFile).toList()){Path dest=copy.resolve(cache.relativize(file));Files.createDirectories(dest.getParent());Files.copy(file,dest,StandardCopyOption.REPLACE_EXISTING);}}
  }
  Path brokenKit=root.resolve("broken-kit");Files.createDirectories(brokenKit);Files.copy(kit.resolve("SKILL.md"),brokenKit.resolve("SKILL.md"));
  Result failedStart=run(worktree,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),"baseline",worktree.toString(),brokenKit.toString()));
  require(failedStart.exit()!=0&&failedStart.output().contains("STOP KIT_DEFECT (halt)"),"spawn failure has a ready-to-file halt",failedStart.output());
  Result baselineResult=pass("baseline");check(baselineResult,"retry failed baseline from immutable original","Original findings and API captured");if(baselineResult.exit()!=0)throw new AssertionError("Baseline failed; evidence "+root);
  Path originalEvidence=Path.of(com.google.gson.JsonParser.parseString(Files.readString(worktree.resolve(".guardrails-run/state.json"))).getAsJsonObject().get("baseline").getAsString());
  if(!Files.exists(originalEvidence.resolve("pmd.json")))throw new AssertionError("baseline failed: "+root);
  Path supportPom=worktree.resolve("test-support/pom.xml");Files.writeString(supportPom,Files.readString(supportPom).replace("</project>","<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies></project>"));
  write(worktree,"test-support/src/test/java/fx/SupportTest.java","package fx; class SupportTest { @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals(42,new Support().value()); } }");
  check(pass("check"),"own-module test candidate verified","Candidate verified");check(pass("accept","Test support behavior in its owning module"),"only verified tree accepted","Accepted ");
  Result tracked=run(worktree,List.of("git","ls-files"));require(!tracked.output().contains(".guardrails-run"),"run evidence stays out of accepted commits",tracked.output());
  Path reserved=worktree.resolve("test-support/src/main/java/fx/Support.java");String reservedText=Files.readString(reserved);
  Files.writeString(reserved,reservedText.replace("42","43"));Result reservedEdit=pass("check");
  require(reservedEdit.exit()!=0&&reservedEdit.output().contains("HANDS_OFF scope/content changed"),"reserved member edit rejected before measurement",reservedEdit.output());Files.writeString(reserved,reservedText);
  Path source=worktree.resolve("app/src/main/java/fx/app/App.java");String original=Files.readString(source);Files.writeString(source,original.replace("public class App {","public class App { public static final int ADDED = 1;"));
  Result api=pass("check");require(api.exit()!=0&&api.output().contains("API changed"),"compatible API addition rejected before acceptance",api.output());Files.writeString(source,original);
  require(pass("accept","must not commit").exit()!=0,"failed candidate cannot reuse old validation","");
  Path baseline=originalEvidence.resolve("pmd.json");String bytes=Files.readString(baseline);Files.writeString(baseline,bytes+" ");Result tamper=pass("resume");require(tamper.exit()!=0&&tamper.output().contains("Original evidence changed"),"modified evidence cannot mint zero",tamper.output());Files.writeString(baseline,bytes);
  check(pass("resume"),"same-version resume keeps original start","Resume from original");
  check(pass("report"),"incomplete scope yields honest report","report.md");String report=Files.readString(worktree.resolve("config/guardrails/report.md"));require(report.contains("Quality pass incomplete")&&report.contains("not assessed")&&!report.contains("Quality gates ready"),"uninstalled gates cannot produce completion",report);
  check(pass("finish"),"report-only finish commit","Final report committed");
  require(!run(worktree,List.of("git","ls-files")).output().contains(".guardrails-run"),"finished branch still omits run records","");
  if(!failures.isEmpty())throw new AssertionError(failures+"; evidence "+root);System.out.println("PASS pass transactions; evidence "+root);
 }
}
