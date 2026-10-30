///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.github.siom79.japicmp:japicmp:0.26.5
//SOURCES Xml.java

import java.nio.file.*;
import java.io.File;
import java.util.*;
import japicmp.cli.JApiCli;
import java.util.jar.*;
import java.io.*;
import java.lang.module.ModuleDescriptor;
import javassist.bytecode.ClassFile;
import javassist.bytecode.AccessFlag;
import javassist.*;
import japicmp.exception.JApiCmpException;

/** Frozen public/protected API: any shape change fails, even binary-compatible additions. */
public class ApiCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: ApiCheck.java OLD.jar NEW.jar OLD-classpath.txt NEW-classpath.txt REPORT.xml\nCompares protected/public API using japicmp 0.26.5. Classpaths are required, platform-separated. Exit 1: changed API; exit 2: incomplete comparison. No exclusions or missing-class overrides.");
            return;
        }
        if (args.length != 5) throw new IllegalArgumentException("Use --help for arguments");
        try {
            String oldcp = classpath(Path.of(args[2])), newcp = classpath(Path.of(args[3]));
            if (!Objects.equals(moduleDescriptor(Path.of(args[0])), moduleDescriptor(Path.of(args[1])))) {
                System.err.println("API changed: published module descriptor");System.exit(1);
            }
            Path oldApi=visiblePackages(Path.of(args[0])),newApi=visiblePackages(Path.of(args[1]));
            Path oldInherited = inherited(oldApi, oldcp);
            Path newInherited = inherited(newApi, newcp);
            new JApiCli().run(new String[] { "--old", oldApi + ";" + oldInherited, "--new", newApi + ";" + newInherited, "--old-classpath", oldcp,
                "--new-classpath", newcp, "-a", "protected", "--include-synthetic", "--only-modified",
                "--xml-file", args[4], "--reproducible-report", "--error-on-modifications", "--error-on-binary-incompatibility", "--error-on-source-incompatibility" });
            var elements=Xml.read(Path.of(args[4])).getElementsByTagName("*");
            for(int i=0;i<elements.getLength();i++){
                var element=(org.w3c.dom.Element)elements.item(i);
                if(element.hasAttribute("changeStatus")&&!element.getAttribute("changeStatus").equals("UNCHANGED")){
                    System.err.println("API changed: retained shape metadata ("+element.getTagName()+"); see "+args[4]);System.exit(1);
                }
            }
            System.out.println("API unchanged (japicmp 0.26.5, public/protected including synthetic members)");
        } catch (JApiCmpException e) {
            boolean changed = e.getReason() == JApiCmpException.Reason.IncompatibleChange;
            System.err.println((changed ? "API changed: " : "API incomplete: ") + e.getMessage());
            System.exit(changed ? 1 : 2);
        } catch (Exception e) {
            System.err.println("API incomplete: " + e.getMessage());
            System.exit(2);
        }
    }
    static ModuleDescriptor moduleDescriptor(Path artifact)throws Exception{
        try(JarFile jar=new JarFile(artifact.toFile())){
            var entry=jar.getJarEntry("module-info.class");if(entry==null)return null;
            try(var in=jar.getInputStream(entry)){return ModuleDescriptor.read(in);}
        }
    }
    /** package-info is package-private bytecode but its retained annotations define public contracts. */
    static Path visiblePackages(Path artifact)throws Exception{
        Path comparison=Files.createTempFile("guardrails-api-surface-", ".jar");comparison.toFile().deleteOnExit();
        try(JarFile jar=new JarFile(artifact.toFile());JarOutputStream out=new JarOutputStream(Files.newOutputStream(comparison))){
            for(var entry:Collections.list(jar.entries())){
                if(entry.getName().startsWith("META-INF/versions/"))throw new IllegalStateException("Multi-release API artifacts need an explicit versioned comparison: "+artifact);
                if(entry.isDirectory()||entry.getName().equals("module-info.class"))continue;
                byte[] bytes;try(var in=jar.getInputStream(entry)){bytes=in.readAllBytes();}
                if(entry.getName().endsWith("package-info.class")){
                    ClassFile type=new ClassFile(new DataInputStream(new ByteArrayInputStream(bytes)));type.setAccessFlags(type.getAccessFlags()|AccessFlag.PUBLIC);
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();type.write(new DataOutputStream(buffer));bytes=buffer.toByteArray();
                }
                out.putNextEntry(new JarEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        return comparison;
    }
    /** japicmp does not compare changed members of a superclass supplied only on the classpath.
     * Add the transitive external ancestry as comparison input; never ignore missing classes. */
    static Path inherited(Path artifact, String cp) throws Exception {
        ClassPool pool = new ClassPool(false);
        pool.appendSystemPath();
        pool.insertClassPath(artifact.toString());
        if (!cp.isEmpty()) for (String entry : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) pool.appendClassPath(entry);
        Set<String> own = new HashSet<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            jar.stream().filter(e -> e.getName().endsWith(".class") && !e.getName().equals("module-info.class"))
                .forEach(e -> own.add(e.getName().replace('/', '.').replaceFirst("\\.class$", "")));
        }
        Map<String,CtClass> ancestors = new TreeMap<>();
        for (String name : own) {
            CtClass type = pool.get(name);
            if (Modifier.isPublic(type.getModifiers()) || Modifier.isProtected(type.getModifiers())) ancestry(type, ancestors);
        }
        Path file = Files.createTempFile("guardrails-api-ancestry-", ".jar");
        file.toFile().deleteOnExit();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (var entry : ancestors.entrySet()) if (!own.contains(entry.getKey())) {
                out.putNextEntry(new JarEntry(entry.getKey().replace('.', '/') + ".class"));
                out.write(entry.getValue().toBytecode()); out.closeEntry();
            }
        }
        return file;
    }
    static void ancestry(CtClass type, Map<String,CtClass> result) throws Exception {
        List<CtClass> parents = new ArrayList<>(Arrays.asList(type.getInterfaces()));
        CtClass parent = type.getSuperclass(); if (parent != null) parents.add(parent);
        for (CtClass p : parents) {
            if (p.getName().startsWith("java.")) continue;
            if (result.putIfAbsent(p.getName(), p) == null) ancestry(p, result);
        }
    }
    static String classpath(Path file) throws Exception {
        String cp = Files.readString(file).trim();
        // An empty file is valid for a self-contained/JDK-only artifact; a nonexistent entry is not.
        if (!cp.isEmpty()) for (String part : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!Files.exists(Path.of(part))) throw new IllegalArgumentException("Missing classpath entry: " + part);
        }
        return cp;
    }
}
