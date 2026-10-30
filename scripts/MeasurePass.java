///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//DEPS org.jacoco:org.jacoco.core:0.8.15
//SOURCES Util.java Xml.java Declarations.java ApiArtifacts.java NoTestsProbe.java BytecodeSnapshot.java Run.java

import java.nio.file.*;
import java.util.*;
import org.w3c.dom.*;

/** Full scratch measurement for a candidate. No enforcement settings or target sources are edited. */
public class MeasurePass {
    static Path target, output, kit, repo, scratch;
    static String original;
    static Map<Path,String> originalPoms=new LinkedHashMap<>();
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--help")){System.out.println("MeasurePass.java TARGET OUTPUT_DIR KIT PRIVATE_MAVEN_REPO: all five default measurements in scratch, halt on any incomplete result");return;}
        if(args.length!=4)throw new IllegalArgumentException("Use --help");
        target=Path.of(args[0]).toAbsolutePath().normalize();output=Path.of(args[1]).toAbsolutePath().normalize();kit=Path.of(args[2]).toAbsolutePath().normalize();repo=Path.of(args[3]).toAbsolutePath().normalize();
        if(repo.startsWith(Path.of(System.getProperty("user.home"),".m2")))throw new IllegalArgumentException("Use a private Maven repository outside ~/.m2");
        Files.createDirectories(output);Files.createDirectories(repo);scratch=Files.createTempDirectory(output,"source-");
        long start=System.nanoTime();
        try{
            copy(); original=Files.readString(scratch.resolve("pom.xml"));
            script("SourceSnapshot.java",scratch.toString(),output.resolve("pmd.json").toString());
            // Tests run with the original POM, only the standalone JaCoCo agent/report goals added.
            // Package upstream test-jars before consumers run: test-classes bypasses their
            // published includes/excludes and can expose tests with non-transitive dependencies.
            List<String> test=maven("jacoco");test.addAll(List.of("clean","org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent","package","org.jacoco:jacoco-maven-plugin:0.8.15:report"));
            run(test,scratch,output.resolve("tests.log"));
            NoTestsProbe.prove(scratch,output,repo);
            script("CycleSnapshot.java",scratch.toString(),output.resolve("archunit.json").toString());
            spotbugs();List<String> spot=maven("spotbugs");spot.addAll(List.of("-Dspotbugs.skip=false","-DskipTests","package","com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5:spotbugs"));run(spot,scratch,output.resolve("spotbugs.log"));
            script("BytecodeSnapshot.java",scratch.toString(),output.toString(),output.resolve("tests.log").toString());
            restorePoms();
            script("OptionalSnapshot.java",scratch.toString(),output.toString(),repo.toString());
            List<String> api=maven("archunit");api.addAll(List.of("-DskipTests","package","org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath","-Dmdep.outputFile=target/guardrails-api-classpath.txt"));
            run(api,scratch,output.resolve("api-build.log"));ApiArtifacts.capture(scratch,output.resolve("api"));
            script("ConsumerSnapshot.java","capture",scratch.toString(),output.resolve("consumer").toString(),repo.toString());
            preserveRaw();
            compiler();List<String> compile=maven("errorprone");compile.addAll(List.of("-X","-DskipTests","-Derrorprone.failOnWarning=false","-Derrorprone.extraArgs=-XepAllErrorsAsWarnings -XepIgnoreSuppressionAnnotations","clean","compile"));
            run(compile,scratch,output.resolve("compiler.log"));
            script("CompilerSnapshot.java",scratch.toString(),output.toString(),output.resolve("compiler.log").toString());
            Files.writeString(output.resolve("elapsed-millis.txt"),Long.toString((System.nanoTime()-start)/1_000_000)+"\n");
            System.out.println("Full measurement complete: "+output);
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
    static void preserveRaw()throws Exception{
        Path raw=output.resolve("raw");
        try(var paths=Files.walk(scratch)){
            for(Path source:paths.filter(Files::isRegularFile).toList()){
                Path destination=raw.resolve(scratch.relativize(source));Files.createDirectories(destination.getParent());Files.copy(source,destination,StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    static void script(String name,String... args)throws Exception{
        var cmd=new ArrayList<>(List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/"+name).toString()));cmd.addAll(List.of(args));run(cmd,scratch,output.resolve(name+".log"));
    }
    static void run(List<String> command,Path cwd,Path log)throws Exception{Run.checked(command,cwd,log);}
    static void spotbugs()throws Exception{
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
