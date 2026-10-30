///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.jacoco:org.jacoco.core:0.8.15
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ValidationPlan.java Util.java Findings.java Xml.java

import java.nio.file.*;
import java.util.*;
import org.w3c.dom.Element;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

/** Reads fresh bytecode reports. Requires successful clean test/analysis proof from the caller. */
public class BytecodeSnapshot {
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--help")){System.out.println("BytecodeSnapshot.java TARGET OUTPUT_DIR TEST_BUILD_LOG: complete JaCoCo and SpotBugs machine snapshots");return;}
        Path target=Path.of(args[0]).toAbsolutePath().normalize(),output=Path.of(args[1]);long start=System.nanoTime();
        var d=Util.Decisions.read(target);var modules=ValidationPlan.modules(target).stream().filter(m->d.excludedModules().stream().noneMatch(x->m.path().equals(x)||m.path().startsWith(x+"/"))).toList();
        List<Findings.Finding> coverage=new ArrayList<>(),bugs=new ArrayList<>();List<Path> classes=new ArrayList<>();
        Set<String> noTests=noTests(Files.readString(Path.of(args[2])));
        for(var m:modules){
            Path dir=m.dir().resolve("target/classes");if(!Files.isDirectory(dir))throw new IllegalStateException("No compiled classes: "+m.path());
            CoverageBuilder inventory=new CoverageBuilder();new Analyzer(new ExecutionDataStore(),inventory).analyzeAll(dir.toFile());
            try(var paths=Files.walk(dir)){classes.addAll(paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).toList());}
            int executable=inventory.getClasses().stream().mapToInt(c->c.getInstructionCounter().getTotalCount()).sum();
            Path report=m.dir().resolve("target/site/jacoco/jacoco.xml");
            if(executable>0){
                if(!Files.isRegularFile(report)){
                    String artifact=Xml.text(Xml.read(m.dir().resolve("pom.xml")).getDocumentElement(),"artifactId");
                    if(!noTests.contains(artifact))throw new IllegalStateException("Missing JaCoCo report for executable module "+m.path()+"; no proof of zero executed tests");
                    for(var cls:inventory.getClasses())for(var method:cls.getMethods())addUncovered(coverage,m.path(),cls.getName(),method.getName()+method.getDesc(),method.getInstructionCounter().getTotalCount(),"unexecuted: Maven proved no default-phase tests; own-module tests required");
                }else{
                    Path execution=m.dir().resolve("target/jacoco.exec");
                    if(!Files.isRegularFile(execution))throw new IllegalStateException("JaCoCo report lacks its execution provenance: "+m.path());
                    ExecFileLoader data=new ExecFileLoader();data.load(execution.toFile());
                    CoverageBuilder measured=new CoverageBuilder();new Analyzer(data.getExecutionDataStore(),measured).analyzeAll(dir.toFile());
                    if(!measured.getNoMatchClasses().isEmpty())throw new IllegalStateException("JaCoCo execution data does not match compiled classes: "+m.path());
                    Map<String,IClassCoverage> actual=new HashMap<>();for(var cls:measured.getClasses())actual.put(cls.getName(),cls);
                    Element xml=Xml.read(report).getDocumentElement();if(!xml.getTagName().equals("report"))throw new IllegalStateException("Invalid JaCoCo report: "+report);
                    Set<String> seen=new HashSet<>();var entries=xml.getElementsByTagName("class");
                    for(int i=0;i<entries.getLength();i++){
                        Element cls=(Element)entries.item(i);String name=cls.getAttribute("name");if(!seen.add(name)||!actual.containsKey(name))throw new IllegalStateException("Duplicate/unknown JaCoCo class: "+name);
                        var classData=actual.get(name);validateCounters(cls,classData);
                        Map<String,IMethodCoverage> methods=new HashMap<>();for(var method:classData.getMethods())if(method.getInstructionCounter().getTotalCount()>0)methods.put(method.getName()+method.getDesc(),method);
                        for(Element method:Xml.children(cls,"method")){
                            String signature=method.getAttribute("name")+method.getAttribute("desc");
                            var methodData=methods.remove(signature);if(methodData==null)throw new IllegalStateException("Duplicate/unknown JaCoCo method: "+name+"#"+signature);validateCounters(method,methodData);
                            var counter=Xml.children(method,"counter").stream().filter(c->c.getAttribute("type").equals("INSTRUCTION")).findFirst().orElseThrow();
                            addUncovered(coverage,m.path(),name,method.getAttribute("name")+method.getAttribute("desc"),Integer.parseInt(counter.getAttribute("missed")),"uncovered executable instructions");
                        }
                        if(!methods.isEmpty())throw new IllegalStateException("JaCoCo omitted methods in "+name+": "+methods.keySet());
                    }
                    for(var cls:inventory.getClasses())if(cls.getInstructionCounter().getTotalCount()>0&&!seen.contains(cls.getName()))throw new IllegalStateException("JaCoCo omitted executable class: "+m.path()+"/"+cls.getName());
                }
            }
            Path spot=m.dir().resolve("target/spotbugsXml.xml");
            if(!Files.isRegularFile(spot)){if(executable>0)throw new IllegalStateException("Missing SpotBugs report: "+m.path());continue;}
            Element xml=Xml.read(spot).getDocumentElement();if(!xml.getTagName().equals("BugCollection"))throw new IllegalStateException("Invalid SpotBugs report: "+spot);
            if(!xml.getAttribute("version").equals("4.10.4"))throw new IllegalStateException("Unexpected SpotBugs engine: "+xml.getAttribute("version"));
            var errors=Xml.child(xml,"Errors");if(errors!=null&&(!errors.getAttribute("errors").equals("0")||!errors.getAttribute("missingClasses").equals("0")))throw new IllegalStateException("Incomplete SpotBugs analysis: "+errors.getTextContent());
            for(Element bug:Xml.children(xml,"BugInstance")){
                Element cls=Xml.child(bug,"Class"),method=Xml.child(bug,"Method"),field=Xml.child(bug,"Field");
                if(cls==null)throw new IllegalStateException("Unbound SpotBugs finding: "+bug.getAttribute("type"));
                String bound=cls.getAttribute("classname");if(method!=null)bound+="#"+method.getAttribute("name")+method.getAttribute("signature");else if(field!=null)bound+="#"+field.getAttribute("name");
                bugs.add(new Findings.Finding(bug.getAttribute("type"),m.path(),bound,m.path(),"rank="+bug.getAttribute("rank")+" "+Xml.text(bug,"LongMessage")));
            }
        }
        String hash=Findings.fingerprint(target,classes);var paths=modules.stream().map(Util.Module::path).toList();long elapsed=(System.nanoTime()-start)/1_000_000;
        Findings.write(output.resolve("jacoco.json"),new Findings.Snapshot(1,"jacoco","JaCoCo 0.8.15; missed instructions per method",hash,paths,List.of("BytecodeSnapshot",target.toString()),true,coverage,elapsed));
        Findings.write(output.resolve("spotbugs.json"),new Findings.Snapshot(1,"spotbugs","SpotBugs 4.10.4; Max/Medium; all ranks; no exclusions",hash,paths,List.of("BytecodeSnapshot",target.toString()),true,bugs,elapsed));
    }
    static void validateCounters(Element element,ICoverageNode actual){
        Map<String,Element> counters=new HashMap<>();for(var counter:Xml.children(element,"counter"))if(counters.put(counter.getAttribute("type"),counter)!=null)throw new IllegalStateException("Duplicate JaCoCo counter");
        for(var entity:ICoverageNode.CounterEntity.values()){
            var expected=actual.getCounter(entity);var xml=counters.remove(entity.name());
            if(xml==null){if(expected.getTotalCount()>0)throw new IllegalStateException("Missing JaCoCo counter: "+entity);continue;}
            if(Integer.parseInt(xml.getAttribute("covered"))!=expected.getCoveredCount()||Integer.parseInt(xml.getAttribute("missed"))!=expected.getMissedCount())throw new IllegalStateException("JaCoCo counter disagrees with execution data/bytecode: "+entity);
        }
        if(!counters.isEmpty())throw new IllegalStateException("Unknown JaCoCo counters: "+counters.keySet());
    }
    static void addUncovered(List<Findings.Finding> list,String module,String cls,String method,int count,String detail){for(int i=0;i<count;i++)list.add(new Findings.Finding("uncovered-instruction",module,cls.replace('/','.')+"#"+method,module,detail));}
    static Set<String> noTests(String log){
        Set<String> modules=new HashSet<>();String artifact="";boolean surefire=false;
        for(String line:log.split("\n")){
            if(line.startsWith("[GUARDRAILS NO TESTS PROOF] "))modules.add(line.substring("[GUARDRAILS NO TESTS PROOF] ".length()));
            if(line.startsWith("[INFO] --- ")){surefire=line.contains("surefire:")||line.contains("maven-surefire-plugin:");int at=line.indexOf(" @ ");artifact=at<0?"":line.substring(at+3).replace(" ---","").trim();}
            if(surefire&&line.contains("No tests to run.")&&!artifact.isEmpty())modules.add(artifact);
        }
        return modules;
    }
}
