///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java ../scripts/Findings.java
import java.nio.file.*;
import java.util.*;
public class PassMeasurementTest extends ReactorScopeTest {
    public static void main(String[] args)throws Exception{
        kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-pass-measure-");
        Path target=fixture("target",true,false);decisions(target,"TEST_SUPPORT_MODULES: gate\n");
        String pom=Files.readString(target.resolve("pom.xml"));
        Files.writeString(target.resolve("pom.xml"),pom.replace("</modules>","<module>opt-in-it</module><module>test-support</module></modules>"));
        String siblingPom=Files.readString(target.resolve("z-lib/pom.xml"));
        for(String module:List.of("opt-in-it","test-support")){
            write(target,module+"/pom.xml",siblingPom.replace("<artifactId>z-lib</artifactId>","<artifactId>"+module+"</artifactId>").replace("</project>","<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies></project>"));
            write(target,module+"/src/main/java/fx/Support.java","package fx; public class Support { public int value() { return 1; } }");
        }
        write(target,"opt-in-it/src/test/java/fx/SupportIT.java","package fx; class SupportIT { @org.junit.jupiter.api.Test void works() { org.junit.jupiter.api.Assertions.assertEquals(1,new Support().value()); } }");
        check(run(target,List.of("git","init","-q")),"initialize scratch target");
        Path output=root.resolve("measurement"),repo=args.length>1?Path.of(args[1]):root.resolve("m2");
        Result result=script(target,"MeasurePass.java",output.toString(),kit.toString(),repo.toString());
        check(result,"full source and bytecode measurement", "Full measurement complete");
        if(result.exit()!=0)throw new AssertionError("Measurement failed; evidence "+root);
        for(String gate:List.of("pmd","archunit","jacoco","spotbugs","errorprone"))require(Findings.read(output.resolve(gate+".json")).complete(),gate+" complete","");
        require(Findings.read(output.resolve("jacoco.json")).findings().stream().anyMatch(f->f.module().equals("z-lib")&&f.detail().contains("unexecuted")),"no-tests sibling accounted for without zero floor","");
        for(String module:List.of("opt-in-it","test-support"))require(Findings.read(output.resolve("jacoco.json")).findings().stream().anyMatch(f->f.module().equals(module)&&f.detail().contains("unexecuted")),module+" remains visible until own-module tests run","");
        require(!Files.exists(target.resolve("target")),"scratch measurements leave target output untouched","");
        decisions(target,"TEST_SUPPORT_MODULES: exclude test-support\n");
        Path supportPom=target.resolve("test-support/pom.xml");Files.writeString(supportPom,Files.readString(supportPom).replace("</project>","<properties><jacoco.skip>true</jacoco.skip></properties></project>"));
        write(target,"test-support/src/test/java/fx/SupportTest.java","package fx; class SupportTest { @org.junit.jupiter.api.Test void works() { org.junit.jupiter.api.Assertions.assertEquals(1,new Support().value()); } }");
        Path excluded=root.resolve("excluded");Result excludedResult=script(target,"MeasurePass.java",excluded.toString(),kit.toString(),repo.toString());
        check(excludedResult,"excluded support without a coverage report does not require a zero-test proof","Full measurement complete");
        if(excludedResult.exit()==0)require(!Findings.read(excluded.resolve("jacoco.json")).modules().contains("test-support"),"excluded support remains outside measured coverage","");
        String source=Files.readString(target.resolve("app/src/main/java/fx/app/App.java"));
        Files.writeString(target.resolve("app/src/main/java/fx/app/App.java"),source.replace("return ","MissingType broken; return "));
        Result bad=script(target,"MeasurePass.java",root.resolve("broken").toString(),kit.toString(),repo.toString());
        require(bad.exit()!=0&&bad.output().contains("STOP KIT_DEFECT (halt)")&&bad.output().contains("cannot find symbol"),"Maven pre-test failure halts with its own diagnostic",bad.output());
        if(!failures.isEmpty())throw new AssertionError(failures);
        System.out.println("PASS full measurement; evidence "+root);
    }
}
