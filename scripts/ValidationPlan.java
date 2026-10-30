//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Util.java Xml.java Findings.java
import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;

/** Explicit evidence for measurements reused between full gate boundaries. */
final class ValidationPlan {
    record Plan(String baseTree,String candidateTree,List<String> changedFiles,boolean full,
                String reason,List<String> modules,boolean resolveConsumer,String previous){}
    static Plan create(Path target,String base,String candidate,Path previous,boolean boundary,boolean finish)throws Exception {
        Process p=new ProcessBuilder("git","diff","--name-only","--no-renames","-z",base,candidate).directory(target.toFile()).start();
        String diff=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(p.waitFor()!=0)throw new IllegalStateException("Cannot prove candidate diff");
        List<String> changed=Arrays.stream(diff.split("\u0000")).filter(s->!s.isBlank()).toList();
        boolean pom=changed.stream().anyMatch(s->s.equals("pom.xml")||s.endsWith("/pom.xml"));
        List<String> all=Util.modules(target).stream().map(Util.Module::path).toList();
        Set<String> touched=new TreeSet<>();boolean full=boundary||finish||pom;String reason=finish?"finish":boundary?"gate boundary":pom?"POM changed":"changed modules and transitive reactor dependents";
        for(String file:changed){
            var module=all.stream().filter(m->file.startsWith((m.equals(".")?"":m+"/")+"src/")).max(Comparator.comparingInt(String::length));
            if(module.isEmpty()||file.endsWith("NoCyclesTest.java")){
                full=true;reason="build, gate, shared input or unclassified path changed: "+file;
            }else touched.add(module.get());
        }
        Path graph=previous.resolve("reactor-graph.json");
        if(!Files.isRegularFile(graph)){full=true;reason="no recorded reactor graph; full measurement required";}
        if(touched.isEmpty()){full=true;if(changed.isEmpty())reason="no source diff; explicit full validation";}
        if(!full){
            var json=com.google.gson.JsonParser.parseString(Files.readString(graph)).getAsJsonObject();
            boolean added;
            do {added=false;for(var entry:json.entrySet())for(var dep:entry.getValue().getAsJsonArray())if(touched.contains(dep.getAsString()))added|=touched.add(entry.getKey());}while(added);
        }
        return new Plan(base,candidate,changed,full,reason,full?all:all.stream().filter(touched::contains).toList(),pom||finish,previous.toString());
    }
    static void write(Path file,Plan plan)throws Exception {Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(plan)+"\n");}
    static Plan read(Path file)throws Exception {return new GsonBuilder().create().fromJson(Files.readString(file),Plan.class);}
    static List<Util.Module> modules(Path target)throws Exception {
        var all=Util.modules(target);Path file=target.resolve(".guardrails-measure-scope.json");
        if(!Files.exists(file))return all;
        Plan plan=read(file);return all.stream().filter(m->plan.modules().contains(m.path())).toList();
    }
    static void graph(Path target,Path effective,Path out)throws Exception {
        var root=Xml.read(effective).getDocumentElement();var projects=root.getTagName().equals("project")?List.of(root):Xml.children(root,"project");
        Map<String,String> coordinates=new HashMap<>();Map<String,org.w3c.dom.Element> source=new TreeMap<>();
        for(var project:projects){var build=Xml.child(project,"build");if(build==null)continue;String dir=Xml.text(build,"sourceDirectory");if(dir.isBlank())continue;
            Path path=Path.of(dir).normalize();if(!path.startsWith(target)||!Files.isDirectory(path))continue;
            Path module=path.getParent().getParent().getParent();String name=module.equals(target)?".":target.relativize(module).toString().replace('\\','/');
            coordinates.put(Xml.text(project,"groupId")+":"+Xml.text(project,"artifactId"),name);source.put(name,project);
        }
        Map<String,Set<String>> graph=new TreeMap<>();
        for(var entry:source.entrySet()){
            Set<String> dependencies=new TreeSet<>();var declared=Xml.child(entry.getValue(),"dependencies");
            if(declared!=null)for(var dependency:Xml.children(declared,"dependency")){
                String name=coordinates.get(Xml.text(dependency,"groupId")+":"+Xml.text(dependency,"artifactId"));if(name!=null)dependencies.add(name);
            }
            // Build plugins can themselves be reactor modules.
            var build=Xml.child(entry.getValue(),"build");var plugins=build==null?null:Xml.child(build,"plugins");
            if(plugins!=null)for(var plugin:Xml.children(plugins,"plugin")){
                String name=coordinates.get(Xml.text(plugin,"groupId")+":"+Xml.text(plugin,"artifactId"));if(name!=null)dependencies.add(name);
            }
            graph.put(entry.getKey(),dependencies);
        }
        Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(graph)+"\n");
    }
    static void merge(Path previous,Path output,List<String> selected)throws Exception {
        try(var paths=Files.list(previous)){
            for(Path old:paths.filter(p->p.getFileName().toString().matches("(pmd|archunit|jacoco|spotbugs|errorprone)\\.json")).toList()){
                Path file=output.resolve(old.getFileName());var before=Findings.read(old);
                if(!Files.exists(file)){Files.copy(old,file);continue;}
                var measured=Findings.read(file);
                if(!before.gate().equals(measured.gate())||!before.checker().equals(measured.checker()))throw new IllegalStateException("Scoped measurement contract changed: "+before.gate());
                List<Findings.Finding> findings=new ArrayList<>(before.findings().stream().filter(f->!selected.contains(f.module())).toList());findings.addAll(measured.findings());
                Findings.write(file,new Findings.Snapshot(1,before.gate(),before.checker(),measured.sourceHash(),before.modules(),List.of("scoped",previous.toString(),String.join(",",selected)),true,findings,measured.elapsedMillis()));
            }
        }
    }
    static void equivalent(Path scoped,Path full,List<String> gates)throws Exception {
        for(String gate:gates){var a=Findings.read(scoped.resolve(gate+".json"));var b=Findings.read(full.resolve(gate+".json"));
            if(!Findings.introduced(a,b).isEmpty()||!Findings.introduced(b,a).isEmpty())throw new IllegalStateException("Scoped/full findings differ for "+gate);
        }
    }
}
