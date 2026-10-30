///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Findings.java
import java.nio.file.Path;
public class CompareSnapshots {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) { System.out.println("CompareSnapshots.java START.json PREVIOUS.json CURRENT.json: reject introduced findings before rendering any policy stop"); return; }
        var added = Findings.introduced(Findings.read(Path.of(args[0])),Findings.read(Path.of(args[1])),Findings.read(Path.of(args[2])));
        for (var finding : added) System.out.println("INTRODUCED " + finding.rule() + " " + finding.module() + " " + finding.declaration() + " (" + finding.location() + ")");
        if (!added.isEmpty()) { System.err.println("Repair the candidate or undo its move; introduced findings cannot enter decisions, exclusions or allowances."); System.exit(1); }
        System.out.println("Introduced findings: 0 (complete, same checker and module scope)");
    }
}
