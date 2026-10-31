///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//DEPS org.jacoco:org.jacoco.core:0.8.15
//SOURCES ValidationPlan.java ConsumerSnapshot.java Util.java Xml.java Declarations.java ApiArtifacts.java NoTestsProbe.java BytecodeSnapshot.java Run.java

import java.nio.file.*;
import java.util.*;
import org.w3c.dom.*;

/** Full scratch measurement for a candidate. No enforcement settings or target sources are edited. */
public class MeasurePass {
    static Path target, output, kit, repo, scratch;
    static String original;
    static ValidationPlan.Plan plan;
    static Map<Path,String> originalPoms=new LinkedHashMap<>();
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--help")){System.out.println("MeasurePass.java TARGET OUTPUT_DIR KIT PRIVATE_MAVEN_REPO: all five default measurements in scratch, halt on any incomplete result");return;}
        if(args.length!=4&&args.length!=5)throw new IllegalArgumentException("Use --help");
        target=Path.of(args[0]).toAbsolutePath().normalize();output=Path.of(args[1]).toAbsolutePath().normalize();kit=Path.of(args[2]).toAbsolutePath().normalize();repo=Path.of(args[3]).toAbsolutePath().normalize();
        if(repo.startsWith(Path.of(System.getProperty("user.home"),".m2")))throw new IllegalArgumentException("Use a private Maven repository outside ~/.m2");
        if(args.length==5)plan=ValidationPlan.read(Path.of(args[4]));
        Files.createDirectories(output);Files.createDirectories(repo);scratch=Files.createTempDirectory(output,"source-");
        long start=System.nanoTime();
        try{
            copy();
            if(plan!=null&&!plan.full())ValidationPlan.write(scratch.resolve(".guardrails-measure-scope.json"),plan);
            original=Files.readString(scratch.resolve("pom.xml"));
            script("SourceSnapshot.java",scratch.toString(),output.resolve("pmd.json").toString());
            // One fresh compilation supplies compiler diagnostics, tests, coverage, bytecode
            // analysis and API artifacts. Overlays are reporting-only and remain in scratch.
            compiler();spotbugs();scopeBuild();descriptorSpotbugs();
            List<String> build=maven("errorprone");
            build.remove("-Djacoco.skip=true");build.remove("-Dspotbugs.skip=true");
            // Capture the effective input model before lifecycle goals mutate it.
            // dependency:properties adds coordinate-named keys containing colons;
            // a subsequent help:effective-pom cannot serialize those as valid XML.
            if(plan==null||plan.full())build.addAll(List.of("org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom","-Doutput="+output.resolve("reactor-model.xml")));
            build.addAll(List.of("-X","-Derrorprone.failOnWarning=false","-Derrorprone.extraArgs=-XepAllErrorsAsWarnings -XepIgnoreSuppressionAnnotations",
                "-Dspotbugs.includeFilterFile="+scratch.resolve(".guardrails-spotbugs-all.xml"),"-Dspotbugs.excludeFilterFile="+scratch.resolve(".guardrails-spotbugs-none.xml"),
                "-Dspotbugs.skip=false","clean","org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent","verify",
                "org.jacoco:jacoco-maven-plugin:0.8.15:report","com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5:spotbugs",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath","-Dmdep.outputFile=target/guardrails-api-classpath.txt"));
            for(String property:List.of("includeFilterFiles","excludeFilterFiles","excludeBugsFile","excludeBugsFiles","onlyAnalyze","visitors","omitVisitors","chooseVisitors","userPrefs"))build.add("-Dspotbugs."+property+"=");
            if(plan!=null&&!plan.full())build.addAll(List.of("-pl",String.join(",",plan.modules()),"-am"));
            System.out.println("Validation: compile, test and analyze shared outputs");
            run(build,scratch,output.resolve("tests.log"));
            Files.copy(output.resolve("tests.log"),output.resolve("compiler.log"));
            NoTestsProbe.prove(scratch,output,repo);
            script("CycleSnapshot.java",scratch.toString(),output.resolve("archunit.json").toString());
            script("BytecodeSnapshot.java",scratch.toString(),output.toString(),output.resolve("tests.log").toString());
            script("CompilerSnapshot.java",scratch.toString(),output.toString(),output.resolve("compiler.log").toString());
            ApiArtifacts.capture(scratch,output.resolve("api"));
            if(plan!=null&&!plan.full()){
                ApiArtifacts.merge(Path.of(plan.previous(),"api"),output.resolve("api"),plan.modules());
                Files.copy(Path.of(plan.previous(),"reactor-graph.json"),output.resolve("reactor-graph.json"));
                ValidationPlan.merge(Path.of(plan.previous()),output,plan.modules());
            }else ValidationPlan.graph(scratch,output.resolve("reactor-model.xml"),output.resolve("reactor-graph.json"));
            restorePoms();
            script("OptionalSnapshot.java",scratch.toString(),output.toString(),repo.toString());
            if(plan==null||plan.resolveConsumer()){
                script("ConsumerSnapshot.java","capture",scratch.toString(),output.resolve("consumer").toString(),repo.toString());
            }else{
                Path prior=Path.of(plan.previous(),"consumer/dependencies.json");
                var closure=ConsumerSnapshot.read(prior);
                if(!closure.profiles().equals(ConsumerSnapshot.profiles(scratch)))throw new IllegalStateException("Publication profile changed without a POM diff; full finish capture required");
                Files.createDirectories(output.resolve("consumer"));Files.copy(prior,output.resolve("consumer/dependencies.json"));
                Files.writeString(output.resolve("consumer/reuse-proof.txt"),"no POM changed\nbase tree: "+plan.baseTree()+"\ncandidate tree: "+plan.candidateTree()+"\nsource evidence: "+prior+"\nchanged files:\n"+String.join("\n",plan.changedFiles())+"\n");
                System.out.println("Consumer resolution skipped: no POM changed (diff recorded)");
            }
            preserveRaw();
            Files.writeString(output.resolve("elapsed-millis.txt"),Long.toString((System.nanoTime()-start)/1_000_000)+"\n");
            System.out.println((plan!=null&&!plan.full()?"Scoped measurement complete (unchanged modules reused with diff proof): ":"Full measurement complete: ")+output);
        }catch(Exception e){
            String message=Run.defect(target,kit,output,e);
            Files.writeString(output.resolve("defect.md"),message);System.err.println(message);System.exit(2);
        }
    }
    static List<String> maven(String gate){
        var command=Util.maven(scratch,gate,"");command.add("-Dmaven.repo.local="+repo);return command;
    }
    static void copy()throws Exception{
        Process p=new ProcessBuilder("git","ls-files","-z","--cached","--others","--exclude-standard").directory(target.toFile()).start();
        String files=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(p.waitFor()!=0)throw new IllegalStateException("Cannot inventory target files");
        for(String name:files.split("\u0000")){
            if(name.isBlank())continue;Path source=target.resolve(name),dest=scratch.resolve(name);
            if(!dest.normalize().startsWith(scratch)||Files.isSymbolicLink(source))throw new IllegalStateException("Unsupported source link/path: "+name);
            if(!Files.exists(source))continue;Files.createDirectories(dest.getParent());Files.copy(source,dest,StandardCopyOption.COPY_ATTRIBUTES);
        }
        // Preserve wrapper/runtime options; override the repository on every Maven call.
        Path config=target.resolve(".mvn/maven.config");if(Files.isRegularFile(config)&&!Files.exists(scratch.resolve(".mvn/maven.config"))){Files.createDirectories(scratch.resolve(".mvn"));Files.copy(config,scratch.resolve(".mvn/maven.config"));}
        Path localConfig=scratch.resolve(".mvn/maven.config");Files.createDirectories(localConfig.getParent());
        String existingConfig=Files.exists(localConfig)?Files.readString(localConfig):"";
        existingConfig=existingConfig.replaceAll("(?m)^-Dmaven\\.repo\\.local=.*$", "");
        Files.writeString(localConfig,existingConfig+"\n-Dmaven.repo.local="+repo+"\n");
        if(!Files.isRegularFile(scratch.resolve("pom.xml")))throw new IllegalStateException("Maven target required");
        try(var paths=Files.walk(scratch)){for(Path pom:paths.filter(file->file.getFileName().toString().equals("pom.xml")&&!Util.isBuildOutput(scratch.relativize(file))).toList())originalPoms.put(pom,Files.readString(pom));}
    }
    static void restorePoms()throws Exception{for(var entry:originalPoms.entrySet())Files.writeString(entry.getKey(),entry.getValue());}
    static void scopeBuild()throws Exception{
        if(plan==null||plan.full())return;
        // -am supplies current packaged upstream dependencies (including filtered test-jars).
        // Unchanged upstream modules do not need their tests or analyzers rerun. Their
        // accepted measurements are reused; selected modules and dependents run normally.
        for(var module:Util.modules(scratch))if(!plan.modules().contains(module.path())){
            Path pom=module.dir().resolve("pom.xml");Document doc=Xml.read(pom);Element project=doc.getDocumentElement();
            Element props=Xml.ensure(project,"properties");Xml.set(props,"skipTests","true");Xml.set(props,"skipITs","true");Xml.set(props,"jacoco.skip","true");
            Element plugins=Xml.ensure(Xml.ensure(project,"build"),"plugins");
            for(String artifact:List.of("maven-surefire-plugin","maven-failsafe-plugin","spotbugs-maven-plugin")){
                Element plugin=Xml.children(plugins,"plugin").stream().filter(p->Xml.text(p,"artifactId").equals(artifact)).findFirst().orElse(null);
                if(plugin==null){plugin=doc.createElement("plugin");plugins.appendChild(plugin);Xml.set(plugin,"groupId",artifact.startsWith("spotbugs")?"com.github.spotbugs":"org.apache.maven.plugins");Xml.set(plugin,"artifactId",artifact);}
                Xml.set(Xml.ensure(plugin,"configuration"),artifact.startsWith("spotbugs")?"skip":"skipTests","true");
            }
            Xml.write(doc,pom);
        }
    }
    static void preserveRaw()throws Exception{
        Path raw=output.resolve("raw");
        try(var paths=Files.walk(scratch)){
            for(Path source:paths.filter(Files::isRegularFile).toList()){
                Path destination=raw.resolve(scratch.relativize(source));Files.createDirectories(destination.getParent());Files.copy(source,destination,StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    static void descriptorSpotbugs()throws Exception{
        for(var module:Util.modules(scratch)){
            var sources=Util.javaFiles(module.sourceRoot());
            if(sources.isEmpty()||!sources.stream().allMatch(p->p.getFileName().toString().equals("module-info.java")))continue;
            // SpotBugs cannot analyze a JPMS descriptor. This is reporting-only;
            // CycleSnapshot still requires a valid compiled descriptor, and
            // BytecodeSnapshot rejects a missing report if executable code appears.
            Path pom=module.dir().resolve("pom.xml");Document doc=Xml.read(pom);
            Element plugins=Xml.ensure(Xml.ensure(doc.getDocumentElement(),"build"),"plugins");
            Element plugin=Xml.children(plugins,"plugin").stream().filter(p->Xml.text(p,"artifactId").equals("spotbugs-maven-plugin")).findFirst().orElse(null);
            if(plugin==null){plugin=doc.createElement("plugin");plugins.appendChild(plugin);Xml.set(plugin,"groupId","com.github.spotbugs");Xml.set(plugin,"artifactId","spotbugs-maven-plugin");}
            Xml.set(Xml.ensure(plugin,"configuration"),"skip","true");Xml.write(doc,pom);
        }
    }
    static void script(String name,String... args)throws Exception{
        var cmd=new ArrayList<>(List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/"+name).toString()));cmd.addAll(List.of(args));run(cmd,scratch,output.resolve(name+".log"));
    }
    static void run(List<String> command,Path cwd,Path log)throws Exception{Run.checked(command,cwd,log);}
    static void spotbugs()throws Exception{
        Path includeAll=scratch.resolve(".guardrails-spotbugs-all.xml"),none=scratch.resolve(".guardrails-spotbugs-none.xml");
        Files.copy(kit.resolve("configs/spotbugs/spotbugs-include-all.xml"),includeAll);
        Files.writeString(none,"<FindBugsFilter/>\n");
        Document doc=Xml.read(scratch.resolve("pom.xml"));Element plugins=Xml.ensure(Xml.ensure(doc.getDocumentElement(),"build"),"plugins");
        for(Element e:Xml.children(plugins,"plugin"))if(Xml.text(e,"artifactId").equals("spotbugs-maven-plugin"))plugins.removeChild(e);
        Element plugin=doc.createElement("plugin");plugins.appendChild(plugin);Xml.set(plugin,"groupId","com.github.spotbugs");Xml.set(plugin,"artifactId","spotbugs-maven-plugin");Xml.set(plugin,"version","4.9.8.5");
        Element dependencies=Xml.ensure(plugin,"dependencies"),dependency=doc.createElement("dependency");dependencies.appendChild(dependency);
        Xml.set(dependency,"groupId","com.github.spotbugs");Xml.set(dependency,"artifactId","spotbugs");Xml.set(dependency,"version","4.10.4");
        Element config=Xml.ensure(plugin,"configuration");config.setAttribute("combine.self","override");Xml.set(config,"effort","Max");Xml.set(config,"threshold","Medium");Xml.set(config,"maxRank","20");Xml.set(config,"xmlOutput","true");Xml.set(config,"includeTests","false");Xml.set(config,"failOnError","true");
        Xml.write(doc,scratch.resolve("pom.xml"));
        for(Path pom:originalPoms.keySet()){
            Document model=Xml.read(pom);var all=model.getElementsByTagName("plugin");boolean changed=false;
            for(int i=0;i<all.getLength();i++){
                Element entry=(Element)all.item(i);if(!Xml.text(entry,"artifactId").equals("spotbugs-maven-plugin"))continue;changed=true;
                Xml.set(entry,"version","4.9.8.5");Element prior=Xml.child(entry,"configuration");if(prior!=null)entry.removeChild(prior);
                Element settings=Xml.ensure(entry,"configuration");settings.setAttribute("combine.self","override");
                Xml.set(settings,"effort","Max");Xml.set(settings,"threshold","Medium");Xml.set(settings,"maxRank","20");Xml.set(settings,"xmlOutput","true");Xml.set(settings,"includeTests","false");Xml.set(settings,"failOnError","true");Xml.set(settings,"skip","false");
                // An omitted element falls back to the plugin's Maven user property.
                // Explicit neutral files prevent enforcement filters leaking into measurement.
                Xml.set(settings,"includeFilterFile",includeAll.toString());Xml.set(settings,"excludeFilterFile",none.toString());
                // The reporting overlay includes inherited bugs outside enforcement filters.
                // Do not run a filtered gate against this unfiltered report. The ordinary
                // gate is verified separately at boundaries; findings comparisons enforce
                // intermediate changes. JaCoCo floors and ordinary tests still run in verify.
                Element executions=Xml.child(entry,"executions");
                if(executions!=null)for(Element execution:Xml.children(executions,"execution")){
                    Element goals=Xml.child(execution,"goals");
                    if(goals!=null&&Xml.children(goals,"goal").stream().anyMatch(g->g.getTextContent().trim().equals("check")))executions.removeChild(execution);
                }
                var deps=entry.getElementsByTagName("dependency");for(int j=0;j<deps.getLength();j++){Element dep=(Element)deps.item(j);if(Xml.text(dep,"artifactId").equals("spotbugs"))Xml.set(dep,"version","4.10.4");}
            }
            if(changed)Xml.write(model,pom);
        }
    }
    static void compiler()throws Exception{
        Document doc=Xml.read(scratch.resolve("pom.xml"));Element project=doc.getDocumentElement(),profiles=Xml.ensure(project,"profiles");
        StringBuilder configuredArgs=new StringBuilder();
        var oldArgs=doc.getElementsByTagName("arg");
        for(int i=0;i<oldArgs.getLength();i++){
            String value=oldArgs.item(i).getTextContent();if(value.startsWith("-Xplugin:ErrorProne"))configuredArgs.append(' ').append(value.substring("-Xplugin:ErrorProne".length()));
        }
        // Keep adopted optional contracts and project-specific raised checks; remove suppressing flags.
        String extra=configuredArgs.toString().replaceAll("-Xep:[^\\s:]+:OFF", "")
            .replaceAll("-XepOpt:CheckReturnValue:ApiExclusionList=[^\\s]+", "")
            .replace("${errorprone.extraArgs}", "").replace("-XepDisableAllChecks", "")
            .replace("-XepDisableAllWarnings", "").replaceAll("-XepExcludedPaths:[^\\s]+", "");
        // Replace only in scratch. Existing exclusions cannot hide inherited or introduced findings.
        for(Element e:Xml.children(profiles,"profile"))if(Xml.text(e,"id").equals("errorprone"))profiles.removeChild(e);
        Path template=scratch.resolve(".guardrails-compiler-profile.xml");Files.writeString(template,"<root>"+Files.readString(kit.resolve("maven/errorprone.profile.xml"))+"</root>");
        Element profile=Xml.child(Xml.read(template).getDocumentElement(),"profile");
        var arguments=profile.getElementsByTagName("arg");for(int i=0;i<arguments.getLength();i++){
            Element arg=(Element)arguments.item(i);if(arg.getTextContent().startsWith("-Xplugin:ErrorProne"))arg.setTextContent("-Xplugin:ErrorProne -Xep:SystemOut:WARN -XepOpt:CheckReturnValue:Packages=reactor.core.publisher -Xep:NullAway:WARN -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true "+extra+" ${errorprone.extraArgs}");
        }
        Element compilerPlugins=Xml.child(Xml.child(profile,"build"),"plugins");
        Element executions=Xml.child(Xml.children(compilerPlugins,"plugin").getFirst(),"executions");
        Element templateExecution=Xml.children(executions,"execution").getFirst();
        Set<String> ids=new HashSet<>();ids.add("default-compile");
        try(var paths=Files.walk(scratch)){
            for(Path pom:paths.filter(file->file.getFileName().toString().equals("pom.xml")&&!Util.isBuildOutput(scratch.relativize(file))).toList()){
                var all=Xml.read(pom).getElementsByTagName("plugin");
                for(int i=0;i<all.getLength();i++){Element plugin=(Element)all.item(i);if(!Xml.text(plugin,"artifactId").equals("maven-compiler-plugin"))continue;
                    Element existingExecutions=Xml.child(plugin,"executions");if(existingExecutions==null)continue;
                    for(Element execution:Xml.children(existingExecutions,"execution")){
                        Element goals=Xml.child(execution,"goals");String id=Xml.text(execution,"id");
                        if(goals!=null&&Xml.children(goals,"goal").stream().anyMatch(g->g.getTextContent().equals("compile"))&&ids.add(id)){
                            Element copy=(Element)templateExecution.cloneNode(true);Xml.set(copy,"id",id);executions.appendChild(copy);
                        }
                    }
                }
            }
        }
        for(var module:Util.modules(scratch))for(Path java:Util.javaFiles(module.sourceRoot()))Declarations.exposeSuppressedWarnings(java);
        profiles.appendChild(doc.importNode(profile,true));Element props=Xml.ensure(project,"properties");Xml.set(props,"errorprone.version","2.50.0");Xml.set(props,"nullaway.version","0.13.8");Xml.set(props,"errorprone.failOnWarning","false");Xml.set(props,"errorprone.extraArgs","");
        Xml.write(doc,scratch.resolve("pom.xml"));Path jvm=scratch.resolve(".mvn/jvm.config");Files.createDirectories(jvm.getParent());
        String existing=Files.exists(jvm)?Files.readString(jvm):"";Files.writeString(jvm,existing+"\n"+Files.readString(kit.resolve("maven/jvm.config")));
    }
}
