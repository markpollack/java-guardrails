///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//DEPS com.github.siom79.japicmp:japicmp:0.26.5
//SOURCES ReactorScopeTest.java ../scripts/ApiInventoryCheck.java
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Maven POM dependencies are metadata, never renamed to bytecode archives. */
public class ApiClasspathTest extends ReactorScopeTest {
    public static void main(String[] args) throws Exception {
        kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        root=Files.createTempDirectory("guardrails-api-classpath-");
        System.out.println("Evidence: "+root);
        Path p=fixture("target",true,false);
        String group=Xml.text(Xml.read(p.resolve("pom.xml")).getDocumentElement(),"groupId");
        Path app=p.resolve("app/pom.xml");
        Files.writeString(app,Files.readString(app).replace("<dependencies>","""
            <dependencies>
            <dependency><groupId>%s</groupId><artifactId>parent</artifactId><version>1-SNAPSHOT</version><type>pom</type></dependency>
            <dependency><groupId>org.jboss.shrinkwrap</groupId><artifactId>shrinkwrap-depchain</artifactId><version>1.2.6</version><type>pom</type><scope>test</scope></dependency>
            """.formatted(group)));
        Path repo=args.length>1?Path.of(args[1]).toAbsolutePath():root.resolve("m2");
        build(p,repo);
        String cp=Files.readString(p.resolve("app/target/guardrails-api-classpath.txt"));
        require(cp.contains("pom.xml")&&cp.contains(".pom"),"real Maven classpath contains reactor and repository POMs",cp);
        Path before=root.resolve("before");ApiArtifacts.capture(p,before);
        int green=ApiInventoryCheck.compare(new String[]{before.toString(),before.toString(),kit.toString(),root.resolve("same").toString()});
        require(green==0,"unchanged API with POM dependencies is complete",Integer.toString(green));
        if(!failures.isEmpty())throw new AssertionError(failures+"; "+root);
        try(var entries=Files.list(before.resolve("dependencies"))){
            for(Path entry:entries.toList())try(var archive=new JarFile(entry.toFile())){archive.size();}
        }
        Path source=p.resolve("app/src/main/java/fx/app/App.java");
        Files.writeString(source,Files.readString(source).replace("public int value()","public long value()"));
        build(p,repo);
        Path after=root.resolve("after");ApiArtifacts.capture(p,after);
        require(ApiInventoryCheck.compare(new String[]{before.toString(),after.toString(),kit.toString(),root.resolve("changed").toString()})==1,
            "POM handling does not hide a planted public return-type change","");
        Path classpath=p.resolve("app/target/guardrails-api-classpath.txt");String valid=Files.readString(classpath);
        for(String file:List.of("corrupt.jar","invalid.pom")){
            Path bad=root.resolve(file);Files.writeString(bad,"<not-a-maven-project/>\n");
            Files.writeString(classpath,valid.trim()+java.io.File.pathSeparator+bad);
            boolean rejected=false;
            try{ApiArtifacts.capture(p,root.resolve(file+"-capture"));}catch(Exception expected){rejected=true;}
            require(rejected,"invalid classpath entry rejected: "+file,"");
            Files.writeString(classpath,valid);
        }
        if(!failures.isEmpty())throw new AssertionError(failures+"; "+root);
        System.out.println("PASS Maven POM classpaths, frozen API plant and malformed artifact controls");
    }
    static void build(Path p,Path repo)throws Exception{
        var command=new ArrayList<>(List.of(p.resolve("mvnw").toString(),"-B","-Dmaven.repo.local="+repo,"-P!errorprone",
            "-DskipTests","-Dpmd.skip=true","-Dcpd.skip=true","-Dspotbugs.skip=true","-Djacoco.skip=true","package",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath","-Dmdep.outputFile=target/guardrails-api-classpath.txt"));
        Result result=run(p,command);check(result,"real Maven classpath build");
        if(result.exit()!=0)throw new AssertionError(result.output());
    }
}
