///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Util.java Xml.java Findings.java Run.java
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.Element;

/** Configured optional checks participate; unavailable/incomplete configured checks halt. */
public class OptionalSnapshot {
    static List<String> selected(Path target)throws Exception{
        List<Path> poms=new ArrayList<>(List.of(target.resolve("pom.xml")));
        for(var module:Util.modules(target))if(!module.path().equals("."))poms.add(module.dir().resolve("pom.xml"));
        Set<String> configured=new HashSet<>();
        for(Path pom:poms){var doc=Xml.read(pom);
            var artifacts=doc.getElementsByTagName("artifactId");
            for(int i=0;i<artifacts.getLength();i++){
                String artifact=artifacts.item(i).getTextContent().trim();
                if(artifact.equals("pitest-maven"))configured.add("pit");
                if(artifact.equals("lincheck")||artifact.equals("lincheck-jvm"))configured.add("lincheck");
            }
            var arguments=doc.getElementsByTagName("arg");
            for(int i=0;i<arguments.getLength();i++){
                String text=arguments.item(i).getTextContent();
                if(text.matches("(?s).*-Xep:NullAway:(ERROR|WARN)(?:\\s.*|$)"))configured.add("nullaway");
            }
        }
        if(configured.contains("nullaway")){
            boolean adopted=false;
            for(var module:Util.modules(target))for(Path source:Util.javaFiles(module.sourceRoot())){
                String java=Files.readString(source);if(java.matches("(?s).*@(?:org\\.jspecify\\.annotations\\.)?NullMarked\\b.*"))adopted=true;
            }
            for(Path pom:poms)if(Files.readString(pom).contains("-XepOpt:NullAway:AnnotatedPackages="))adopted=true;
            if(!adopted)configured.remove("nullaway"); // A dormant processor is not an adopted nullness contract.
        }
        var order=Util.Decisions.read(target).gateOrder();List<String> result=new ArrayList<>();
        for(String gate:List.of("nullaway","pit","lincheck"))if(configured.contains(gate)||order.contains(gate))result.add(gate);
        return result;
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--help")){System.out.println("OptionalSnapshot.java TARGET OUTPUT_DIR PRIVATE_REPO: configured PIT and Lincheck checks; absent checks remain not assessed");return;}
        Path target=Path.of(args[0]),out=Path.of(args[1]),repo=Path.of(args[2]);var gates=selected(target);
        if(gates.contains("lincheck"))lincheck(target,out);
        if(gates.contains("pit"))pit(target,out,repo);
    }
    static void lincheck(Path target,Path out)throws Exception{
        List<Findings.Finding> failures=new ArrayList<>();List<String> modules=new ArrayList<>();List<Path> files=new ArrayList<>();int executed=0;
        for(var module:Util.modules(target)){
            List<Path> tests=Util.javaFiles(module.dir().resolve("src/test/java")).stream().filter(p->{try{return Files.readString(p).contains("lincheck");}catch(Exception e){throw new IllegalStateException(e);}}).toList();
            if(tests.isEmpty())continue;modules.add(module.path());files.addAll(tests);
            Path reports=module.dir().resolve("target/surefire-reports");if(!Files.isDirectory(reports))throw new IllegalStateException("Configured Lincheck tests have no fresh Surefire reports: "+module.path());
            for(Path test:tests){
                // Freeze the executable test contract across hotspots. An unused dependency/import
                // or a passing ordinary test must never turn a configured model checker green.
                Path relative=module.dir().resolve("src/test/java").relativize(test);
                Path compiled=module.dir().resolve("target/test-classes").resolve(relative.toString().replaceFirst("\\.java$",".class"));
                Path bytecode=out.resolve("lincheck-"+module.path().replace('/','_')+"-"+test.getFileName()+".bytecode.txt");
                String javap=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"javap.exe":"javap").toString();
                Run.checked(List.of(javap,"-c","-p",compiled.toString()),target,bytecode);
                boolean invocation=Files.readString(bytecode).matches("(?s).*invoke\\w+[^\\n]*org/jetbrains/(?:lincheck/datastructures|kotlinx/lincheck)[^\\s]*\\.check:.*");
                if(!invocation)throw new IllegalStateException("Cannot identify executable Lincheck invocation: "+test+"; unsupported model/test layout requires an explicit adapter, not an assessed zero");
                String name=test.getFileName().toString().replaceFirst("\\.java$","");boolean found=false;
                try(var paths=Files.list(reports)){for(Path report:paths.filter(p->p.getFileName().toString().startsWith("TEST-")&&p.toString().endsWith(".xml")).toList()){
                    Element suite=Xml.read(report).getDocumentElement();if(!suite.getAttribute("name").endsWith("."+name)&&!suite.getAttribute("name").equals(name))continue;
                    for(Element testcase:Xml.children(suite,"testcase")){
                        if(Xml.child(testcase,"skipped")!=null)throw new IllegalStateException("Configured Lincheck test skipped: "+suite.getAttribute("name"));
                        if(Xml.child(testcase,"failure")!=null||Xml.child(testcase,"error")!=null)failures.add(new Findings.Finding("lincheck-counterexample",module.path(),testcase.getAttribute("classname")+"#"+testcase.getAttribute("name"),test.toString(),testcase.getTextContent()));
                        found=true;executed++;
                    }
                }}
                if(!found)throw new IllegalStateException("Cannot prove configured Lincheck class executed: "+test);
            }
        }
        if(executed==0)throw new IllegalStateException("Lincheck selected/configured without executed tests; define its executable contract before the pass");
        String contract=Findings.fingerprint(target,files);
        Findings.write(out.resolve("lincheck.json"),new Findings.Snapshot(1,"lincheck","Configured Lincheck Java tests; contract "+contract,contract,modules,List.of("test"),true,failures,0));
    }
    static void pit(Path target,Path out,Path repo)throws Exception{
        long start=System.nanoTime();var modules=Util.modules(target);
        Path model=out.resolve("pit-effective.xml");
        Run.checked(List.of(Util.mvnw(target),"-B","-Ppit,!errorprone","-Dmaven.repo.local="+repo,"org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom","-Doutput="+model),target,out.resolve("pit-effective.log"));
        Element modelRoot=Xml.read(model).getDocumentElement();var projects=modelRoot.getTagName().equals("project")?List.of(modelRoot):Xml.children(modelRoot,"project");
        Map<String,String> contracts=new TreeMap<>(),versions=new TreeMap<>();
        for(Element project:projects){
            Element build=Xml.child(project,"build");if(build==null)continue;String source=Xml.text(build,"sourceDirectory");
            var module=modules.stream().filter(m->m.sourceRoot().toString().equals(source)).findFirst();if(module.isEmpty())continue;
            Element plugins=Xml.child(build,"plugins");if(plugins==null)continue;
            for(Element plugin:Xml.children(plugins,"plugin"))if(Xml.text(plugin,"artifactId").equals("pitest-maven")){
                Element config=Xml.child(plugin,"configuration");String skip=config==null?"":Xml.text(config,"skip");if(skip.equals("true"))continue;
                if(!skip.isBlank()&&!skip.equals("false"))throw new IllegalStateException("Unresolved PIT skip setting: "+module.get().path());
                contracts.put(module.get().path(),Xml.text(plugin,"version")+":"+(config==null?"defaults":contract(config)).replace(target.toString(),"<target>"));
                String version=Xml.text(plugin,"version");if(version.isBlank()||version.contains("${"))throw new IllegalStateException("PIT version must be resolved for "+module.get().path());versions.put(module.get().path(),version);
            }
        }
        if(contracts.isEmpty())throw new IllegalStateException("PIT selected/configured without an active source-module mutation contract");List<Path> poms=new ArrayList<>(List.of(target.resolve("pom.xml")));for(var module:modules)if(!module.path().equals("."))poms.add(module.dir().resolve("pom.xml"));
        Map<Path,String> originals=new LinkedHashMap<>();boolean configured=false;
        // Reporting overlay changes only fail thresholds/output, preserving configured class/test/mutator scope.
        for(Path pom:poms){var doc=Xml.read(pom);var plugins=doc.getElementsByTagName("plugin");boolean changed=false;
            for(int i=0;i<plugins.getLength();i++){Element plugin=(Element)plugins.item(i);if(!Xml.text(plugin,"artifactId").equals("pitest-maven"))continue;configured=true;changed=true;
                Element config=Xml.ensure(plugin,"configuration");Xml.set(config,"mutationThreshold","0");Xml.set(config,"coverageThreshold","0");Xml.set(config,"testStrengthThreshold","0");Xml.set(config,"timestampedReports","false");
                Element formats=Xml.ensure(config,"outputFormats");formats.setTextContent("");Xml.set(formats,"param","XML");
            }
            if(changed){originals.put(pom,Files.readString(pom));Xml.write(doc,pom);}
        }
        if(!configured)throw new IllegalStateException("PIT selected without a configured mutation contract");
        List<String> base=new ArrayList<>(List.of(Util.mvnw(target),"-B","-Ppit,!errorprone","-Dmaven.repo.local="+repo,"-Dspring-javaformat.skip=true","-Dpmd.skip=true","-Dcpd.skip=true","-Dspotbugs.skip=true","-Djacoco.skip=true"));
        List<String> cmd=new ArrayList<>();
        try{
            // Stage current reactor dependencies, including packaged test-jars, without running
            // lifecycle-bound PIT. A direct reactor-wide goal would invent contracts in every
            // unconfigured sibling and select a different plugin version there.
            Map<Path,String> reporting=new LinkedHashMap<>();
            for(Path pom:originals.keySet()){
                reporting.put(pom,Files.readString(pom));var doc=Xml.read(pom);var plugins=doc.getElementsByTagName("plugin");
                for(int i=0;i<plugins.getLength();i++){Element plugin=(Element)plugins.item(i);if(Xml.text(plugin,"artifactId").equals("pitest-maven"))Xml.set(Xml.ensure(plugin,"configuration"),"skip","true");}
                Xml.write(doc,pom);
            }
            var stage=new ArrayList<>(base);stage.addAll(List.of("-DskipTests","package","org.apache.maven.plugins:maven-install-plugin:3.1.4:install"));Run.checked(stage,target,out.resolve("pit-reactor-build.log"));
            for(var entry:reporting.entrySet())Files.writeString(entry.getKey(),entry.getValue());
            for(String module:contracts.keySet()){
                var selected=new ArrayList<>(base);selected.addAll(List.of("-pl",module,"test-compile","org.pitest:pitest-maven:"+versions.get(module)+":mutationCoverage"));cmd.addAll(selected);
                Run.checked(selected,target,out.resolve("pit-"+module.replace('/','_')+".log"));
            }
        }finally{for(var entry:originals.entrySet())Files.writeString(entry.getKey(),entry.getValue());}
        List<Findings.Finding> findings=new ArrayList<>();List<String> measured=new ArrayList<>();List<Path> reports=new ArrayList<>();
        for(var module:modules){if(!contracts.containsKey(module.path()))continue;Path report=module.dir().resolve("target/pit-reports/mutations.xml");if(!Files.isRegularFile(report))throw new IllegalStateException("Configured PIT module omitted its report: "+module.path());
            Element xml=Xml.read(report).getDocumentElement();if(!xml.getTagName().equals("mutations"))throw new IllegalStateException("Invalid PIT report: "+report);measured.add(module.path());reports.add(report);
            for(Element mutation:Xml.children(xml,"mutation")){
                String status=mutation.getAttribute("status");if(List.of("SURVIVED","NO_COVERAGE").contains(status))findings.add(new Findings.Finding("pit:"+Xml.text(mutation,"mutator"),module.path(),Xml.text(mutation,"mutatedClass")+"#"+Xml.text(mutation,"mutatedMethod")+Xml.text(mutation,"methodDescription"),Xml.text(mutation,"sourceFile")+":"+Xml.text(mutation,"lineNumber"),status));
                else if(!List.of("KILLED","TIMED_OUT").contains(status))throw new IllegalStateException("Incomplete PIT mutant outcome: "+status);
            }
        }
        if(measured.isEmpty())throw new IllegalStateException("Configured PIT produced no mutation reports");
        Findings.write(out.resolve("pit.json"),new Findings.Snapshot(1,"pit","PIT "+contracts+"; survivors and no-coverage mutants",Findings.fingerprint(target,reports),measured,cmd,true,findings,(System.nanoTime()-start)/1_000_000));
    }
    static String contract(Element element){
        StringBuilder out=new StringBuilder(element.getTagName()).append('(');
        for(org.w3c.dom.Node n=element.getFirstChild();n!=null;n=n.getNextSibling()){
            if(n instanceof Element child){if(!List.of("mutationThreshold","coverageThreshold","testStrengthThreshold","outputFormats","timestampedReports").contains(child.getTagName()))out.append(contract(child));}
            else if(n.getNodeType()==org.w3c.dom.Node.TEXT_NODE&&!n.getTextContent().isBlank())out.append(n.getTextContent().trim());
        }
        return out.append(')').toString();
    }

}
