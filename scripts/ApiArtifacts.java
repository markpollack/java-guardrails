//JAVA 21+
//SOURCES ValidationPlan.java Util.java Xml.java
//DEPS com.google.code.gson:gson:2.13.2
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import com.google.gson.GsonBuilder;

/** All reactor source modules, including gate-excluded modules, remain in the frozen API inventory. */
final class ApiArtifacts {
    record Artifact(String module,String jar,String classpath){}
    static void merge(Path previous,Path output,List<String> selected)throws Exception{
        var gson=new GsonBuilder().create();
        var old=gson.fromJson(Files.readString(previous.resolve("artifacts.json")),Artifact[].class);
        var now=gson.fromJson(Files.readString(output.resolve("artifacts.json")),Artifact[].class);
        List<Artifact> merged=new ArrayList<>();
        for(Artifact artifact:old)if(!selected.contains(artifact.module())){
            Path jar=output.resolve(artifact.module().replace('/','_')+".jar"),classpath=output.resolve(artifact.module().replace('/','_')+".classpath");
            Files.copy(Path.of(artifact.jar()),jar);List<String> entries=new ArrayList<>();
            String cp=Files.readString(Path.of(artifact.classpath())).trim();
            if(!cp.isEmpty())for(String entry:cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))){
                Path source=Path.of(entry),destination=output.resolve("dependencies").resolve(source.getFileName());Files.createDirectories(destination.getParent());
                if(!Files.exists(destination))Files.copy(source,destination);entries.add(destination.toString());
            }
            Files.writeString(classpath,String.join(java.io.File.pathSeparator,entries));merged.add(new Artifact(artifact.module(),jar.toString(),classpath.toString()));
        }
        merged.addAll(List.of(now));
        merged.sort(Comparator.comparing(Artifact::module));Files.writeString(output.resolve("artifacts.json"),gson.toJson(merged)+"\n");
    }
    static void capture(Path target,Path output)throws Exception{
        List<Artifact> artifacts=new ArrayList<>();Files.createDirectories(output);
        for(var module:ValidationPlan.modules(target)){
            Path classes=module.dir().resolve("target/classes");if(!Files.isDirectory(classes))throw new IllegalStateException("Missing API classes: "+module.path());
            Path jar=output.resolve(module.path().replace('/','_')+".jar");int count=0;
            try(var stream=Files.walk(classes);var out=new JarOutputStream(Files.newOutputStream(jar))){
                for(Path file:stream.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).sorted().toList()){
                    out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));out.write(Files.readAllBytes(file));out.closeEntry();count++;
                }
            }
            if(count==0)throw new IllegalStateException("Empty API artifact: "+module.path());
            Path cp=module.dir().resolve("target/guardrails-api-classpath.txt");if(!Files.isRegularFile(cp))throw new IllegalStateException("Missing API dependency classpath: "+module.path());
            // Preserve all dependency jars before later clean/compiler phases remove reactor output.
            Path copied=output.resolve(module.path().replace('/','_')+".classpath");List<String> entries=new ArrayList<>();
            String text=Files.readString(cp).trim();
            if(!text.isEmpty())for(String entry:text.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))){
                Path path=Path.of(entry);if(!Files.isRegularFile(path))throw new IllegalStateException("Missing API dependency artifact: "+path);
                // dependency:build-classpath also includes dependencies of type pom,
                // including reactor pom.xml paths. They contain no bytecode; retaining
                // them as .jar files makes japicmp's class pool fail on valid reactors.
                // Validate the metadata, and never treat a malformed JAR as a POM.
                String name=path.getFileName().toString();
                if(name.endsWith(".pom")||name.equals("pom.xml")){
                    var project=Xml.read(path).getDocumentElement();
                    if(!project.getTagName().equals("project")||!Xml.text(project,"modelVersion").equals("4.0.0"))
                        throw new IllegalStateException("Invalid API dependency POM: "+path);
                    continue;
                }
                try(var archive=new JarFile(path.toFile())){archive.size();}
                byte[] bytes=Files.readAllBytes(path);
                String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                Path save=output.resolve("dependencies").resolve(digest+".jar");Files.createDirectories(save.getParent());
                if(!Files.exists(save))Files.write(save,bytes);entries.add(save.toString());
            }
            Files.writeString(copied,String.join(java.io.File.pathSeparator,entries));artifacts.add(new Artifact(module.path(),jar.toString(),copied.toString()));
        }
        Files.writeString(output.resolve("artifacts.json"),new GsonBuilder().setPrettyPrinting().create().toJson(artifacts)+"\n");
    }
}
