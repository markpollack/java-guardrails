///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;

/** A new private opt-out cannot hide a NullAway regression behind an unchanged API. */
public class NullawayScopeTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception {
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-nullaway-scope-");Path p=fixture("project",false,false);
  decisions(p,"TEST_SUPPORT_MODULES: gate\nGATE_ORDER: jacoco, spotbugs, errorprone, pmd, archunit, nullaway\n");
  Path pom=p.resolve("pom.xml");Files.writeString(pom,Files.readString(pom).replace("</project>","<dependencies><dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>1.0.0</version></dependency></dependencies></project>"));
  write(p,"app/src/main/java/fx/app/package-info.java","@org.jspecify.annotations.NullMarked package fx.app;");
  String original="package fx.app; public class Hidden { public int size() { return value().length(); } private String value() { return \"ok\"; } }";
  write(p,"app/src/main/java/fx/app/Hidden.java",original);check(run(p,List.of("git","init","-q")),"fixture init");
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2"),first=root.resolve("first"),second=root.resolve("second");
  Result a=script(p,"MeasurePass.java",first.toString(),kit.toString(),repo.toString());check(a,"adopted NullAway scope measured");if(a.exit()!=0)throw new AssertionError(root);
  write(p,"app/src/main/java/fx/app/Hidden.java",original.replace("private String", "@org.jspecify.annotations.NullUnmarked private String").replace("return \"ok\";", "return null;"));
  Result b=script(p,"MeasurePass.java",second.toString(),kit.toString(),repo.toString());check(b,"changed private scope measured");if(b.exit()!=0)throw new AssertionError(root);
  boolean refused=false;try{Findings.introduced(Findings.read(first.resolve("nullaway.json")),Findings.read(second.resolve("nullaway.json")));}catch(IllegalStateException expected){refused=expected.getMessage().contains("contract/scope changed");}
  require(refused,"private NullUnmarked opt-out changes the measured contract rather than minting zero","");
  if(!failures.isEmpty())throw new AssertionError(failures+"; evidence "+root);System.out.println("PASS NullAway scope freeze; evidence "+root);
 }
}
