///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Util.java Findings.java Declarations.java Xml.java

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Compiler findings remain visible even when an enforcement exception is recorded. */
public class CompilerSnapshot {
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--help")){System.out.println("CompilerSnapshot.java TARGET OUTPUT_DIR COMPILER_LOG: requires complete debug compile with Error Prone active in every selected module");return;}
        Path target=Path.of(args[0]).toAbsolutePath().normalize(),out=Path.of(args[1]);long start=System.nanoTime();
        var d=Util.Decisions.read(target);var modules=Util.modules(target).stream().filter(m->d.excludedModules().stream().noneMatch(x->m.path().equals(x)||m.path().startsWith(x+"/"))).toList();
        Pattern diagnostic=Pattern.compile("^\\[(?:WARNING|ERROR)\\] (.+\\.java):\\[(\\d+),(\\d+)\\] (.*)$");
        Map<Path,Declarations> bindings=new HashMap<>();List<Findings.Finding> ep=new ArrayList<>(),nullaway=new ArrayList<>();Set<String> active=new HashSet<>(),hidden=new HashSet<>();SortedSet<String> nullnessContract=new TreeSet<>();String artifact="";
        for(String raw:Files.readAllLines(Path.of(args[2]))){String line=raw.replaceAll("\\u001B\\[[;\\d]*m","");
            if(line.startsWith("[INFO] --- ")){int at=line.indexOf(" @ ");artifact=at<0?"":line.substring(at+3).replace(" ---","").trim();}
            if(line.startsWith("[DEBUG] -")&&line.contains("-Xplugin:ErrorProne")&&line.contains("-XepIgnoreSuppressionAnnotations")){
                active.add(artifact);
                var options=Pattern.compile("-XepOpt:NullAway:[^\\s]+").matcher(line);while(options.find())nullnessContract.add(artifact+":"+options.group());
                if(line.contains("-XepDisableAll")||line.contains("-XepExcludedPaths:")||line.matches(".*-Xep:[^ :]+:OFF.*"))hidden.add(artifact);
            }
            Matcher m=diagnostic.matcher(line);if(!m.matches()){
                if(line.startsWith("[WARNING]")&&(line.contains("unknown enum constant")||line.contains("class file for")||line.contains("warnings have been truncated")))throw new IllegalStateException("Incomplete compiler diagnostics: "+line);
                continue;
            }
            Path file=Path.of(m.group(1)).normalize();var module=modules.stream().filter(mod->file.startsWith(mod.sourceRoot())).findFirst();if(module.isEmpty())continue;
            String message=m.group(4),check="javac:warning";
            if(message.startsWith("[")){int end=message.indexOf(']');check=message.substring(1,end);}
            else if(message.contains("marked for removal"))check="javac:removal";
            if(!bindings.containsKey(file))bindings.put(file,new Declarations(file));
            String bound;
            try { bound=bindings.get(file).at(Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),false); }
            catch(IllegalStateException e){ throw new IllegalStateException("Unbound compiler finding: "+file+":"+m.group(2)+":"+m.group(3)+" "+check,e); }
            var finding=new Findings.Finding(check,module.get().path(),bound,target.relativize(file)+":"+m.group(2),message);
            (check.equals("NullAway")?nullaway:ep).add(finding);
        }
        List<Path> files=new ArrayList<>();for(var module:modules){
            String artifactId=Xml.text(Xml.read(module.dir().resolve("pom.xml")).getDocumentElement(),"artifactId");
            if(hidden.contains(artifactId))throw new IllegalStateException("Compiler configuration still hides findings in "+module.path());
            if(!active.contains(artifactId))throw new IllegalStateException("Cannot prove Error Prone ran for "+module.path()+"; expected debug compiler invocation with suppression-independent measurement");
            files.addAll(Util.javaFiles(module.sourceRoot()));
        }
        for(Path file:files){
            if(!bindings.containsKey(file))bindings.put(file,new Declarations(file));
            for(String scope:bindings.get(file).nullnessScope)nullnessContract.add(target.relativize(file).toString().replace('\\','/')+":"+scope);
        }
        String hash=Findings.fingerprint(target,files);var paths=modules.stream().map(Util.Module::path).toList();long elapsed=(System.nanoTime()-start)/1_000_000;
        Findings.write(out.resolve("errorprone.json"),new Findings.Snapshot(1,"errorprone","Error Prone 2.50.0 defaults + SystemOut/CheckReturnValue; javac warnings; no suppressions",hash,paths,List.of("CompilerSnapshot",target.toString()),true,ep,elapsed));
        Findings.write(out.resolve("nullaway.json"),new Findings.Snapshot(1,"nullaway","NullAway 0.13.8; scope "+nullnessContract,hash,paths,List.of("CompilerSnapshot",target.toString()),true,nullaway,elapsed));
    }
}
