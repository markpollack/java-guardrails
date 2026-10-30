import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;

/** Parse only to bind checker locations to declarations. No token/AST normalization or move matching. */
final class Declarations {
    record Declaration(String name, String kind, long start, long end, String text) {}
    final List<Declaration> declarations = new ArrayList<>();
    final SortedSet<String> nullnessScope = new TreeSet<>();
    final CompilationUnitTree unit;
    Declarations(Path file) throws Exception {
        String source = Files.readString(file);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Declaration binding needs a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, manager.getJavaFileObjects(file));
            unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                throw new IllegalStateException("Cannot bind declarations in " + file + ": " + diagnostics.getDiagnostics());
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            scope(String.valueOf(unit.getPackageName()),unit.getPackageAnnotations());
            new TreePathScanner<Void,String>() {
                void add(Tree node, String name, String kind) {
                    long start = positions.getStartPosition(unit, node), end = positions.getEndPosition(unit, node);
                    if (start < 0 || end < start) throw new IllegalStateException("Unbound declaration in " + file);
                    int before=(int)start-1;while(before>=0&&Character.isWhitespace(source.charAt(before)))before--;
                    if(before>0&&source.charAt(before)=='/'&&source.charAt(before-1)=='*'){
                        int comment=source.lastIndexOf("/**",before-1);
                        if(comment>=0&&source.indexOf("*/",comment)==before-1)start=comment;
                    }
                    declarations.add(new Declaration(name, kind, start, end, source.substring((int)start, (int)end)));
                    if(node instanceof ClassTree type)scope(name,type.getModifiers().getAnnotations());
                    if(node instanceof MethodTree method)scope(name,method.getModifiers().getAnnotations());
                    if(node instanceof VariableTree variable)scope(name,variable.getModifiers().getAnnotations());
                }
                @Override public Void visitClass(ClassTree node, String owner) {
                    String simple = node.getSimpleName().toString();
                    // Anonymous/local classes bind to their containing declaration; no unstable numbering.
                    if (simple.isEmpty() || getCurrentPath().getParentPath().getLeaf() instanceof BlockTree) return super.visitClass(node, owner);
                    String name = owner == null ? (unit.getPackageName() == null ? "" : unit.getPackageName() + ".") + simple : owner + "$" + simple;
                    add(node, name, "class"); return super.visitClass(node, name);
                }
                @Override public Void visitMethod(MethodTree node, String owner) {
                    String name = owner + "#" + node.getName() + "(" + String.join(",", node.getParameters().stream().map(v -> v.getType().toString()).toList()) + ")";
                    add(node, name, "member"); return super.visitMethod(node, owner);
                }
                @Override public Void visitVariable(VariableTree node, String owner) {
                    if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) add(node, owner + "#" + node.getName(), "member");
                    return super.visitVariable(node, owner);
                }
            }.scan(unit, null);
        }
    }
    private void scope(String declaration,List<? extends AnnotationTree> annotations){
        for(var annotation:annotations){String name=annotation.getAnnotationType().toString();
            if(name.equals("NullMarked")||name.equals("NullUnmarked")||name.equals("org.jspecify.annotations.NullMarked")||name.equals("org.jspecify.annotations.NullUnmarked"))
                nullnessScope.add(declaration+":"+name.substring(name.lastIndexOf('.')+1));
        }
    }
    /** Scratch-only removal keeps offsets/newlines; javac removal warnings must remain measurable. */
    static void exposeSuppressedWarnings(Path file) throws Exception {
        String source=Files.readString(file);char[] chars=source.toCharArray();
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,null)){
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjects(file));
            var unit=task.parse().iterator().next();var positions=Trees.instance(task).getSourcePositions();
            new TreeScanner<Void,Void>(){
                @Override public Void visitAnnotation(AnnotationTree annotation,Void unused){
                    String name=annotation.getAnnotationType().toString();
                    if(name.equals("SuppressWarnings")||name.equals("java.lang.SuppressWarnings")){
                        int start=(int)positions.getStartPosition(unit,annotation),end=(int)positions.getEndPosition(unit,annotation);
                        if(start<0||end<start)throw new IllegalStateException("Unbound suppression in "+file);
                        for(int i=start;i<end;i++)if(chars[i]!='\n'&&chars[i]!='\r')chars[i]=' ';
                    }
                    return super.visitAnnotation(annotation,unused);
                }
            }.scan(unit,null);
        }
        Files.writeString(file,new String(chars));
    }
    String at(int line, int column, boolean classOnly) {
        long offset = unit.getLineMap().getPosition(line, Math.max(column, 1));
        return declarations.stream().filter(d -> d.start <= offset && offset < d.end && (!classOnly || d.kind.equals("class")))
            .min(Comparator.comparingLong(d -> d.end - d.start)).orElseThrow(() -> new IllegalStateException("No bound declaration at " + line + ":" + column)).name;
    }
    Declaration named(String selector) {
        var found = declarations.stream().filter(d -> d.name.equals(selector)).toList();
        if (found.size() != 1) throw new IllegalArgumentException("Selector must match exactly one declaration: " + selector + " (" + found.size() + ")");
        return found.getFirst();
    }
}
