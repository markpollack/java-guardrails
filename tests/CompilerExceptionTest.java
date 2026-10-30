///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;
public class CompilerExceptionTest extends ReactorScopeTest {
 public static void main(String[] args)throws Exception{
  kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-compiler-exceptions-");Path target=fixture("target",false,false);decisions(target,"TEST_SUPPORT_MODULES: gate\n");
  write(target,"app/src/main/java/fx/app/Legacy.java","package fx.app; @Deprecated(forRemoval=true) public interface Legacy {}\n");
  write(target,"app/src/main/java/fx/app/Provider.java","package fx.app;\n@SuppressWarnings(\"removal\")\npublic class Provider implements Legacy {\n @SuppressWarnings(\"TypeParameterUnusedInFormals\")\n public <T> T value() { return null; }\n}\n");
  check(run(target,List.of("git","init","-q")),"fixture init");
  Path repo=args.length>1?Path.of(args[1]):root.resolve("m2"),first=root.resolve("first");
  Result measured=script(target,"MeasurePass.java",first.toString(),kit.toString(),repo.toString());check(measured,"inherited exceptions remain measurable");if(measured.exit()!=0)throw new AssertionError("measurement failed: "+root);
  var before=Findings.read(first.resolve("errorprone.json"));
  require(before.findings().stream().anyMatch(f->f.rule().equals("javac:removal")&&f.declaration().equals("fx.app.Provider")),"smallest class removal exception still visible",before.toString());
  require(before.findings().stream().anyMatch(f->f.rule().equals("TypeParameterUnusedInFormals")&&f.declaration().equals("fx.app.Provider#value()")),"generic API member exception still visible",before.toString());
  Path provider=target.resolve("app/src/main/java/fx/app/Provider.java");String original=Files.readString(provider);Files.writeString(provider,original.substring(0,original.lastIndexOf('}'))+" @SuppressWarnings(\"TypeParameterUnusedInFormals\") public <T> T added() { return null; }\n}\n");
  Path second=root.resolve("second");Result added=script(target,"MeasurePass.java",second.toString(),kit.toString(),repo.toString());check(added,"new suppressed member measured");if(added.exit()!=0)throw new AssertionError("measurement failed: "+root);
  var after=Findings.read(second.resolve("errorprone.json"));require(Findings.introduced(before,before,after).stream().anyMatch(f->f.rule().equals("TypeParameterUnusedInFormals")&&f.declaration().equals("fx.app.Provider#added()")),"new exception cannot hide introduced finding",after.toString());
  require(Files.readString(provider).contains("@SuppressWarnings"),"scratch suppression exposure leaves target annotations untouched","");
  if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS compiler exception visibility; evidence "+root);
 }
}
