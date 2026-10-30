///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ReactorScopeTest.java ../scripts/Util.java

import java.nio.file.*;
import java.util.*;

/** Actual javac/jar artifacts; each incompatible shape is planted independently. */
public class ApiFreezeTest extends ReactorScopeTest {
    static final String ORIGINAL = "package api; public class Provider extends Base implements Legacy { public Legacy bean() { return this; } protected Legacy client; public java.util.List<String> values(String p) throws java.io.IOException { return java.util.List.of(p); } }";
    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        root = Files.createTempDirectory("guardrails-api-freeze-");
        Path old = artifact("old", ORIGINAL, "public int inherited() { return 1; }");
        Path body = artifact("body", ORIGINAL.replace("return this;", "Legacy answer = this; return answer;"), "public int inherited() { return 2; }");
        expect(old, body, true, "method bodies");
        Map<String,String> plants = new LinkedHashMap<>();
        plants.put("provider superinterface", ORIGINAL.replace("implements Legacy", "implements Current").replace("return this;", "return null;"));
        plants.put("bean return descriptor", ORIGINAL.replace("public Legacy bean()", "public Current bean()"));
        plants.put("TCK field descriptor", ORIGINAL.replace("protected Legacy client", "protected Current client"));
        plants.put("parameter", ORIGINAL.replace("values(String p)", "values(Object p)").replace("List.of(p)", "List.of(p.toString())"));
        plants.put("generic", ORIGINAL.replace("List<String>", "List<CharSequence>"));
        plants.put("superclass", ORIGINAL.replace("extends Base", "extends Other"));
        plants.put("visibility", ORIGINAL.replace("public Legacy bean", "protected Legacy bean"));
        plants.put("public addition", ORIGINAL.replace("protected Legacy client;", "protected Legacy client; public void added() {}"));
        plants.put("checked exception", ORIGINAL.replace("throws java.io.IOException", "throws Exception"));
        for (var e : plants.entrySet()) expect(old, artifact("plant" + sequence++, e.getValue(), "public int inherited() { return 1; }"), false, e.getKey());
        Path removed = artifact("removed", ORIGINAL.replace("public class Provider", "class Provider"), "public int inherited() { return 1; }");
        expect(old, removed, false, "public type removed");
        Path inherited = artifact("inherited", ORIGINAL, "public long inherited() { return 1; }");
        expect(old, inherited, false, "inherited external descriptor");
        Path contract = artifact("contract", ORIGINAL, "public int inherited() { return 1; }");
        write(contract,"src/api/package-info.java","@api.Contract(\"changed\") package api;");
        run(contract,"javac","-cp","api.jar","-d","classes","src/api/package-info.java");run(contract,"jar","cf","api.jar","-C","classes",".");
        expect(old,contract,false,"retained package contract");
        Path dependency = old.resolve("dependency.jar");
        Files.move(dependency, dependency.resolveSibling("dependency.hidden"));
        try {
            expect(old, body, false, "missing external classpath");
            for(Path artifact:List.of(old,body))Files.writeString(artifact.resolve("artifacts.json"),"[{\"module\":\".\",\"jar\":\""+artifact.resolve("api.jar").toString().replace("\\","\\\\")+"\",\"classpath\":\""+artifact.resolve("classpath.txt").toString().replace("\\","\\\\")+"\"}]");
            Result incomplete=run(root,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/ApiInventoryCheck.java").toString(),old.toString(),body.toString(),kit.toString(),root.resolve("inventory").toString()));
            require(incomplete.exit()==2&&incomplete.output().contains("API incomplete"),"inventory preserves incomplete comparison status",incomplete.output());
        }
        finally { Files.move(dependency.resolveSibling("dependency.hidden"), dependency); }
        Path moduleOld=module("module-old","module frozen { exports modularapi; }",1);
        expect(moduleOld,module("module-body","module frozen { exports modularapi; }",2),true,"named module body");
        expect(moduleOld,module("module-open","module frozen { exports modularapi; opens modularapi; }",1),false,"module descriptor");
        if (!failures.isEmpty()) throw new AssertionError(failures);
        System.out.println("PASS API freeze and incompleteness plants; evidence " + root);
    }
    static Path module(String name,String descriptor,int value)throws Exception{
        Path dir=root.resolve(name);Files.createDirectories(dir.resolve("classes"));
        write(dir,"src/module-info.java",descriptor);write(dir,"src/modularapi/Value.java","package modularapi; public class Value { public int value() { return "+value+"; } }");
        run(dir,"javac","-d","classes","src/module-info.java","src/modularapi/Value.java");run(dir,"jar","cf","api.jar","-C","classes",".");Files.writeString(dir.resolve("classpath.txt"),"");return dir;
    }
    static Path artifact(String name, String provider, String inherited) throws Exception {
        Path dir = root.resolve(name); Files.createDirectories(dir.resolve("classes")); Files.createDirectories(dir.resolve("dependency"));
        write(dir, "src/api/Current.java", "package api; public interface Current {}");
        write(dir, "src/api/Legacy.java", "package api; public interface Legacy extends Current {}");
        write(dir, "src/api/Provider.java", provider);
        write(dir,"src/api/Contract.java","package api; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.PACKAGE) public @interface Contract { String value(); }");
        write(dir,"src/api/package-info.java","@api.Contract(\"frozen\") package api;");
        write(dir, "dep/api/Base.java", "package api; public class Base { " + inherited + " }");
        write(dir, "dep/api/Other.java", "package api; public class Other {}");
        run(dir, "javac", "-d", "dependency", "dep/api/Base.java", "dep/api/Other.java");
        run(dir, "jar", "cf", "dependency.jar", "-C", "dependency", ".");
        run(dir, "javac", "-cp", "dependency.jar", "-d", "classes", "src/api/Current.java", "src/api/Legacy.java", "src/api/Provider.java", "src/api/Contract.java", "src/api/package-info.java");
        run(dir, "jar", "cf", "api.jar", "-C", "classes", ".");
        Files.writeString(dir.resolve("classpath.txt"), dir.resolve("dependency.jar").toString());
        return dir;
    }
    static void run(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new AssertionError(Arrays.toString(command));
    }
    static void expect(Path old, Path now, boolean green, String name) throws Exception {
        List<String> cmd = List.of(kit.resolve("jbang").toString(), kit.resolve("scripts/ApiCheck.java").toString(), old.resolve("api.jar").toString(), now.resolve("api.jar").toString(), old.resolve("classpath.txt").toString(), now.resolve("classpath.txt").toString(), root.resolve("report-" + sequence++ + ".xml").toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes()); int exit = p.waitFor();
        require(green ? exit == 0 && text.contains("API unchanged") : name.equals("missing external classpath") ? exit == 2 && text.contains("API incomplete") : exit == 1 && text.contains("API changed"), name, text);
    }
}
