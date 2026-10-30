//JAVA 21+
//SOURCES BytecodeSnapshot.java Util.java Xml.java Findings.java Run.java
//DEPS org.jacoco:org.jacoco.core:0.8.15
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.Element;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;

/** A report can be absent for opt-in IT-only projects without Surefire printing "No tests to run".
 * Ask Surefire to fail on zero executed tests in that module only. This proves remediation scope,
 * never a passing coverage gate. The target POM is untouched: this runs on the scratch copy. */
final class NoTestsProbe {
    static void prove(Path scratch,Path output,Path repo)throws Exception{
        String log=Files.readString(output.resolve("tests.log"));Set<String> known=BytecodeSnapshot.noTests(log);
        var decisions=Util.Decisions.read(scratch);
        for(var module:Util.modules(scratch)){
            if(decisions.excludedModules().stream().anyMatch(path->module.path().equals(path)||module.path().startsWith(path+"/")))continue;
            Path report=module.dir().resolve("target/site/jacoco/jacoco.xml"),classes=module.dir().resolve("target/classes");
            if(Files.exists(report)||!Files.isDirectory(classes))continue;
            CoverageBuilder inventory=new CoverageBuilder();new Analyzer(new ExecutionDataStore(),inventory).analyzeAll(classes.toFile());
            if(inventory.getClasses().stream().mapToInt(c->c.getInstructionCounter().getTotalCount()).sum()==0)continue;
            Path pom=module.dir().resolve("pom.xml");var doc=Xml.read(pom);String artifact=Xml.text(doc.getDocumentElement(),"artifactId");if(known.contains(artifact))continue;
            String original=Files.readString(pom);Element plugins=Xml.ensure(Xml.ensure(doc.getDocumentElement(),"build"),"plugins");
            Element plugin=Xml.children(plugins,"plugin").stream().filter(p->Xml.text(p,"artifactId").equals("maven-surefire-plugin")).findFirst().orElse(null);
            if(plugin==null){plugin=doc.createElement("plugin");plugins.appendChild(plugin);Xml.set(plugin,"groupId","org.apache.maven.plugins");Xml.set(plugin,"artifactId","maven-surefire-plugin");}
            Xml.set(Xml.ensure(plugin,"configuration"),"failIfNoTests","true");Xml.write(doc,pom);
            Path probe=output.resolve("no-tests-"+module.path().replace('/','_')+".log");
            try{
                var cmd=Util.maven(scratch,"jacoco",module.path());cmd.addAll(List.of("-Dmaven.repo.local="+repo,"package"));
                int exit=Run.logged(cmd,scratch,probe);String text=Files.readString(probe);
                String marker="No tests were executed!";
                if(exit==0||!text.contains(marker)||!text.contains(" on project "+artifact+":"))throw new IllegalStateException("Missing coverage is not proven zero-test execution for "+module.path()+"; diagnostic "+probe);
                log+="\n[GUARDRAILS NO TESTS PROOF] "+artifact+"\n";
            }finally{Files.writeString(pom,original);}
        }
        Files.writeString(output.resolve("tests.log"),log);
    }
}
