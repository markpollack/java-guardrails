//JAVA 21+
//SOURCES Util.java
//DEPS com.google.code.gson:gson:2.13.2
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import com.google.gson.GsonBuilder;

/** All reactor source modules, including gate-excluded modules, remain in the frozen API inventory. */
final class ApiArtifacts {
    record Artifact(String module,String jar,String classpath){}
    static void capture(Path target,Path output)throws Exception{
        List<Artifact> artifacts=new ArrayList<>();Files.createDirectories(output);
        for(var module:Util.modules(target)){
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
