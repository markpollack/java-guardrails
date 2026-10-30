//DEPS com.google.code.gson:gson:2.13.2

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import com.google.gson.GsonBuilder;

/** Evidence, never an enforcement baseline. Absence/partial output is not a zero count. */
final class Findings {
    static final int SCHEMA = 1;
    record Finding(String rule, String module, String declaration, String location, String detail) {
        String identity() { return rule + "\u0000" + module + "\u0000" + declaration; }
    }
    record Snapshot(int schema, String gate, String checker, String sourceHash, List<String> modules,
                    List<String> command, boolean complete, List<Finding> findings, long elapsedMillis) {
        Snapshot {
            if (schema != SCHEMA || gate == null || checker == null || sourceHash == null || modules == null || modules.isEmpty()
                    || command == null || findings == null || elapsedMillis < 0) throw new IllegalArgumentException("Invalid measurement envelope");
            if (!complete) throw new IllegalArgumentException("Incomplete " + gate + " measurement cannot be compared");
            if (findings.stream().anyMatch(f -> !modules.contains(f.module()) || f.rule() == null || f.declaration() == null))
                throw new IllegalArgumentException("Unbound finding or module outside measured scope");
        }
    }
    static void write(Path file, Snapshot snapshot) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(snapshot) + "\n");
    }
    static Snapshot read(Path file) throws Exception {
        return new GsonBuilder().create().fromJson(Files.readString(file), Snapshot.class);
    }
    static List<Finding> introduced(Snapshot old, Snapshot now) {
        if (!old.gate.equals(now.gate) || !old.checker.equals(now.checker) || !old.modules.equals(now.modules))
            throw new IllegalStateException("Measurement contract/scope changed; remeasure the original start with the same contract");
        Map<String,Integer> remaining = new HashMap<>();
        old.findings.forEach(f -> remaining.merge(f.identity(), 1, Integer::sum));
        List<Finding> result = new ArrayList<>();
        for (Finding f : now.findings) {
            int count = remaining.getOrDefault(f.identity(), 0);
            if (count > 0) remaining.put(f.identity(), count - 1); else result.add(f);
        }
        return result;
    }
    static List<Finding> introduced(Snapshot start, Snapshot previous, Snapshot now) {
        // The stricter of immutable start and previous accepted multiplicities: fixed debt cannot return.
        var a = introduced(start, now); var b = introduced(previous, now);
        List<Finding> result = new ArrayList<>(a);
        Map<String,Integer> counts = new HashMap<>(); a.forEach(f -> counts.merge(f.identity(), 1, Integer::sum));
        for (Finding f : b) { int n = counts.getOrDefault(f.identity(), 0); if (n == 0) result.add(f); else counts.put(f.identity(), n-1); }
        return result;
    }
    static String fingerprint(Path root, List<Path> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files.stream().sorted().toList()) {
            digest.update(root.relativize(file).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte)0); digest.update(Files.readAllBytes(file)); digest.update((byte)0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
