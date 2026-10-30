///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//DEPS com.github.siom79.japicmp:japicmp:0.26.5
//SOURCES Util.java ApiArtifacts.java ApiCheck.java Run.java
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
public class ApiInventoryCheck {
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--help")){System.out.println("ApiInventoryCheck.java START_API_DIR CURRENT_API_DIR KIT REPORT_DIR: compare every original source module including gate exclusions");return;}
        try{System.exit(compare(args));}catch(Exception e){System.err.println("API incomplete: "+e.getMessage());System.exit(2);}
    }
    static int compare(String[] args)throws Exception{
        var gson=new Gson();var old=List.of(gson.fromJson(Files.readString(Path.of(args[0],"artifacts.json")),ApiArtifacts.Artifact[].class));
        var now=List.of(gson.fromJson(Files.readString(Path.of(args[1],"artifacts.json")),ApiArtifacts.Artifact[].class));
        if(!old.stream().map(ApiArtifacts.Artifact::module).toList().equals(now.stream().map(ApiArtifacts.Artifact::module).toList())){System.err.println("API changed: published module inventory differs; cannot shrink or add API scope during the pass");return 1;}
        Path kit=Path.of(args[2]),out=Path.of(args[3]);Files.createDirectories(out);boolean failed=false,incomplete=false;
        for(int i=0;i<old.size();i++){
            var a=old.get(i);var b=now.get(i);String name=a.module().replace('/','_');Path log=out.resolve(name+".log");
            // One JVM for the inventory; each comparison still gets independent japicmp
            // inputs/class pools and a complete per-module diagnostic report.
            int exit;var stdout=System.out;var stderr=System.err;
            try(var stream=new java.io.PrintStream(Files.newOutputStream(log))){
                System.setOut(stream);System.setErr(stream);
                exit=ApiCheck.compare(new String[]{a.jar(),b.jar(),a.classpath(),b.classpath(),out.resolve(name+".xml").toString()});
            }finally{System.setOut(stdout);System.setErr(stderr);}
            System.out.println(a.module()+": "+(exit==0?"unchanged":"FAILED; "+log));if(exit!=0){System.err.print(Files.readString(log));failed=true;}
            if(exit!=0&&exit!=1)incomplete=true;
        }
        if(incomplete)return 2;if(failed)return 1;System.out.println("All original API shapes unchanged");return 0;
    }
}
