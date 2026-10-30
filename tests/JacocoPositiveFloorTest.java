///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java
import java.nio.file.*;
public class JacocoPositiveFloorTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception{
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-positive-floor-");Path p=fixture("target",false,false);decisions(p,"TEST_SUPPORT_MODULES: exclude test-support\n");
  StringBuilder source=new StringBuilder("package fx.app;\npublic class App {\n public int value() { return 42; }\n");for(int i=0;i<200;i++)source.append(" public int unused").append(i).append("() { return ").append(i).append("; }\n");source.append("}\n");write(p,"app/src/main/java/fx/app/App.java",source.toString());
  Result result=script(p,"JacocoMeasure.java");require(result.exit()!=0&&result.output().contains("STOP JACOCO_OWN_TESTS")&&!result.output().contains("Record as:  JACOCO_FLOORS"),"real nonzero coverage cannot mint zero floor",result.output());
  if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS positive coverage floor; evidence "+root);
 }
}
